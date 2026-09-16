package com.ambitiousconcepts.opsatlas.operations.web;

import com.ambitiousconcepts.opsatlas.catalog.api.EnvironmentLookup;
import com.ambitiousconcepts.opsatlas.identity.api.CurrentPrincipal;
import com.ambitiousconcepts.opsatlas.operations.api.Deployment;
import com.ambitiousconcepts.opsatlas.operations.api.DeploymentLedger;
import com.ambitiousconcepts.opsatlas.operations.api.DeploymentReport;
import com.ambitiousconcepts.opsatlas.shared.NotFoundException;
import com.ambitiousconcepts.opsatlas.shared.ValidationFailedException;
import com.ambitiousconcepts.opsatlas.shared.Violation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What version is running where, as reported by whoever deployed it.
 *
 * <p>Served from {@code operations} rather than folded into the catalog, for the
 * same reason health is (ADR 0010): the dependency runs one way, and a slow
 * deployment read must not slow the service list.
 *
 * <p><strong>Reported, never discovered.</strong> OpsAtlas does not read a
 * registry, does not ask a cluster, and does not verify any of this. A row says
 * a pipeline claimed a version went out. Comparing it to what is actually
 * running - drift - needs an observed version, which needs services to expose
 * one; ADR 0017 records why that is a later phase and not this one.
 */
@RestController
@RequestMapping("/api/v1")
class DeploymentController {

    /** Deployments a detail view carries. Enough to see a promotion sequence. */
    private static final int HISTORY = 20;

    private final DeploymentLedger ledger;
    private final EnvironmentLookup environments;
    private final CurrentPrincipal currentPrincipal;

    DeploymentController(
            DeploymentLedger ledger, EnvironmentLookup environments, CurrentPrincipal currentPrincipal) {
        this.ledger = ledger;
        this.environments = environments;
        this.currentPrincipal = currentPrincipal;
    }

    /**
     * Records a deployment a pipeline is reporting.
     *
     * <p>201 for a new report, 200 for one already seen. A retrying pipeline
     * gets the original deployment back rather than creating a second one, and
     * the status is the only way to tell - which is what an idempotency key is
     * for (§9).
     */
    @PostMapping("/services/{slug}/environments/{environment}/deployments")
    ResponseEntity<Deployment> report(
            @PathVariable String slug,
            @PathVariable String environment,
            @RequestBody DeployedVersion body) {
        UUID orgId = currentPrincipal.get().orgId();
        UUID serviceId = environments
                .serviceIdBySlug(orgId, slug)
                .orElseThrow(() -> new NotFoundException("Service", slug));
        UUID environmentId = environments
                .environmentIdByName(orgId, serviceId, environment)
                .orElseThrow(() -> new NotFoundException("Environment", slug + "/" + environment));

        validate(body);

        DeploymentLedger.Recorded recorded = ledger.record(
                orgId,
                environmentId,
                environment,
                new DeploymentReport(
                        body.version().trim(),
                        Optional.ofNullable(body.commitSha()).map(String::trim).filter(s -> !s.isEmpty()),
                        Optional.ofNullable(body.deployedBy()).map(String::trim).filter(s -> !s.isEmpty()),
                        Optional.ofNullable(body.deployedAt()),
                        body.idempotencyKey().trim()));

        // 201 when this report produced the row, 200 when it was a replay. The
        // ledger says which; comparing fields here could not tell a retry from a
        // genuine redeploy of the same version.
        return ResponseEntity.status(recorded.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(recorded.deployment());
    }

    /** Current version per environment, plus recent history. */
    @GetMapping("/services/{slug}/deployments")
    ServiceDeployments forService(@PathVariable String slug) {
        UUID orgId = currentPrincipal.get().orgId();
        UUID serviceId = environments
                .serviceIdBySlug(orgId, slug)
                .orElseThrow(() -> new NotFoundException("Service", slug));

        Map<UUID, Deployment> current = ledger.currentByEnvironment(orgId, serviceId);
        return new ServiceDeployments(
                serviceId, current, ledger.history(orgId, serviceId, HISTORY), current.isEmpty());
    }

    private static void validate(DeployedVersion body) {
        List<Violation> violations = new java.util.ArrayList<>();
        if (body.version() == null || body.version().isBlank()) {
            violations.add(new Violation(
                    "/version", "required", "a non-blank version", "absent or blank", "A version is required."));
        }
        if (body.idempotencyKey() == null || body.idempotencyKey().isBlank()) {
            // Required rather than generated here: a key this endpoint invented
            // would be unique per request, which makes every retry a new
            // deployment - the exact failure the key exists to prevent.
            violations.add(new Violation(
                    "/idempotencyKey",
                    "required",
                    "a key stable across retries of the same deploy",
                    "absent or blank",
                    "An idempotency key is required, so a retried report does not become a second deployment."));
        }
        if (body.commitSha() != null
                && !body.commitSha().isBlank()
                && !body.commitSha().trim().matches("[0-9a-fA-F]{7,40}")) {
            violations.add(new Violation(
                    "/commitSha",
                    "pattern",
                    "7 to 40 hexadecimal characters",
                    body.commitSha(),
                    "A commit SHA is 7 to 40 hexadecimal characters."));
        }
        if (!violations.isEmpty()) {
            throw new ValidationFailedException("This deployment report could not be accepted.", violations);
        }
    }

    /**
     * @param deployedAt when the deploy happened. Absent means now - a pipeline
     *     reporting as it finishes should not have to send a clock reading
     * @param idempotencyKey required, not generated: a key invented here would
     *     differ on every retry, which is the opposite of the point
     */
    record DeployedVersion(
            @NotBlank String version,
            @Schema(nullable = true) String commitSha,
            @Schema(nullable = true) String deployedBy,
            @Schema(nullable = true) Instant deployedAt,
            @NotBlank String idempotencyKey) {}

    /**
     * @param current environment id to its most recent deployment. An
     *     environment absent from this map has had <strong>nothing reported</strong>,
     *     which is not the same as never having been deployed
     * @param nothingReported true when no environment of this service has any
     *     deployment. Stated rather than inferred from an empty map, so the
     *     console does not have to decide what empty means - the same choice
     *     {@code neverObserved} makes on the health endpoint
     */
    record ServiceDeployments(
            UUID serviceId,
            Map<UUID, Deployment> current,
            Collection<Deployment> history,
            boolean nothingReported,
            @Schema(nullable = true) String notice) {
        ServiceDeployments(
                UUID serviceId, Map<UUID, Deployment> current, Collection<Deployment> history, boolean nothingReported) {
            this(
                    serviceId,
                    current,
                    history,
                    nothingReported,
                    nothingReported
                            ? "No deployment has been reported for this service. That means nothing told OpsAtlas,"
                                    + " not that nothing was deployed."
                            : "Every version here was reported by whoever deployed it. OpsAtlas does not read a"
                                    + " registry or ask a cluster, and does not verify that the version reported is"
                                    + " the version running.");
        }
    }
}
