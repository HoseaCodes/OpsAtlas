package com.ambitiousconcepts.opsatlas.operations.web;

import com.ambitiousconcepts.opsatlas.catalog.api.EnvironmentLookup;
import com.ambitiousconcepts.opsatlas.identity.api.CurrentPrincipal;
import com.ambitiousconcepts.opsatlas.operations.api.EnvironmentHealth;
import com.ambitiousconcepts.opsatlas.operations.api.HealthReadModel;
import com.ambitiousconcepts.opsatlas.shared.NotFoundException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What probing has found.
 *
 * <p>Served separately from the catalog rather than folded into it, so that the
 * dependency between {@code catalog} and {@code operations} runs one way. See
 * {@code docs/adr/0010-health-is-served-separately-from-the-catalog.md}. The
 * practical effect is that a slow or failing health read does not slow or fail
 * the service list.
 *
 * <p><strong>Nothing here is an SLO measurement.</strong> Availability is the
 * share of probes that succeeded, from one vantage point, against a health
 * endpoint. A service can serve errors to every real user while its readiness
 * endpoint answers happily. The field is called {@code probeAvailability} for
 * that reason and the console repeats it.
 */
@RestController
@RequestMapping("/api/v1")
class HealthController {

    /** Days of history a detail view carries. The prototype's ribbon is 30. */
    private static final int HISTORY_DAYS = 30;

    private final HealthReadModel health;
    private final EnvironmentLookup environments;
    private final CurrentPrincipal currentPrincipal;

    HealthController(HealthReadModel health, EnvironmentLookup environments, CurrentPrincipal currentPrincipal) {
        this.health = health;
        this.environments = environments;
        this.currentPrincipal = currentPrincipal;
    }

    /**
     * Worst status per service, for the catalog list.
     *
     * @param serviceIds the services to report on. A service with no entry in
     *     the response has never been observed - which is every service until an
     *     observer runs, and is a different fact from being unhealthy.
     */
    @GetMapping("/health")
    HealthSummary summary(@RequestParam(required = false) List<UUID> serviceIds) {
        UUID orgId = currentPrincipal.get().orgId();
        Map<UUID, String> statuses =
                serviceIds == null || serviceIds.isEmpty() ? Map.of() : health.rollupStatuses(orgId, serviceIds);
        return new HealthSummary(statuses);
    }

    /** Per-environment state and daily history for one service. */
    @GetMapping("/services/{slug}/health")
    ServiceHealth forService(@PathVariable String slug) {
        UUID orgId = currentPrincipal.get().orgId();
        UUID serviceId = environments
                .serviceIdBySlug(orgId, slug)
                .orElseThrow(() -> new NotFoundException("Service", slug));

        Map<UUID, EnvironmentHealth> byEnvironment = health.forService(orgId, serviceId, HISTORY_DAYS);
        return new ServiceHealth(serviceId, byEnvironment.values(), byEnvironment.isEmpty());
    }

    /**
     * @param statuses service id to worst status. Absent means never observed.
     * @param notice plain text the console shows beside any availability figure.
     *     It travels in the payload rather than living in the frontend so the
     *     two cannot drift apart - the same reason the policy rules carry theirs.
     */
    record HealthSummary(Map<UUID, String> statuses, String notice) {
        HealthSummary(Map<UUID, String> statuses) {
            this(
                    statuses,
                    "A service absent from this map has never been probed, which is not the same as being healthy."
                            + " Statuses come from probing a health endpoint on an interval from one vantage point;"
                            + " they are not SLO measurements.");
        }
    }

    /**
     * @param neverObserved true when no environment of this service has been
     *     probed. Stated rather than inferred from an empty list, so the console
     *     does not have to decide what empty means.
     */
    record ServiceHealth(
            UUID serviceId,
            Collection<EnvironmentHealth> environments,
            boolean neverObserved,
            @Schema(nullable = true) String notice) {
        ServiceHealth(UUID serviceId, Collection<EnvironmentHealth> environments, boolean neverObserved) {
            this(
                    serviceId,
                    environments,
                    neverObserved,
                    neverObserved
                            ? "No observer has probed this service. Health is unknown, which is not the same as healthy."
                            : "probeAvailability is the share of probes that succeeded from one vantage point against"
                                    + " a health endpoint. It is not an SLO, and response times are means and maxima,"
                                    + " never percentiles.");
        }
    }
}
