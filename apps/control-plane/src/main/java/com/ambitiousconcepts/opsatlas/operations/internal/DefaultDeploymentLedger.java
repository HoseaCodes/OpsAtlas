package com.ambitiousconcepts.opsatlas.operations.internal;

import com.ambitiousconcepts.opsatlas.catalog.api.EnvironmentLookup;
import com.ambitiousconcepts.opsatlas.operations.api.Deployment;
import com.ambitiousconcepts.opsatlas.operations.api.DeploymentLedger;
import com.ambitiousconcepts.opsatlas.operations.api.DeploymentReport;
import com.ambitiousconcepts.opsatlas.shared.Ids;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DefaultDeploymentLedger implements DeploymentLedger {

    /**
     * How many deployments a history read returns.
     *
     * <p>A cap rather than pagination: this is a detail-page sidebar, not a
     * browsable log, and an unbounded read of a table that grows with release
     * cadence is the kind of query that is fine for a year and then is not.
     */
    private static final int MAX_HISTORY = 50;

    private final DeploymentRepository deployments;
    private final DeploymentWriter writer;
    private final EnvironmentLookup environments;
    private final Clock clock;

    DefaultDeploymentLedger(
            DeploymentRepository deployments,
            DeploymentWriter writer,
            EnvironmentLookup environments,
            Clock clock) {
        this.deployments = deployments;
        this.writer = writer;
        this.environments = environments;
        this.clock = clock;
    }

    /**
     * Deliberately not {@code @Transactional}. The insert runs in its own
     * transaction ({@link DeploymentWriter}) so that a unique-constraint
     * violation - which is the expected outcome of a replayed report - aborts
     * only that transaction and leaves this method able to read the row that
     * beat it.
     */
    @Override
    public Recorded record(UUID orgId, UUID environmentId, String environmentName, DeploymentReport report) {
        Instant now = clock.instant();
        Instant deployedAt = report.deployedAt().orElse(now);

        DeploymentEntity entity = DeploymentEntity.reported(
                Ids.newId(),
                orgId,
                environmentId,
                report.version(),
                report.commitSha().orElse(null),
                report.deployedBy().orElse(null),
                deployedAt,
                report.idempotencyKey(),
                now);

        try {
            return new Recorded(view(writer.insert(entity), environmentName), true);
        } catch (DataIntegrityViolationException duplicate) {
            // The unique constraint fired, which means this exact report was
            // already recorded. Returning the existing row is the whole point:
            // a retried deploy notification must be a no-op, not a second
            // deployment and a rollback that never happened.
            return deployments
                    .findByOrgIdAndEnvironmentIdAndIdempotencyKey(orgId, environmentId, report.idempotencyKey())
                    .map(existing -> new Recorded(view(existing, environmentName), false))
                    .orElseThrow(() -> duplicate);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Deployment> currentByEnvironment(UUID orgId, UUID serviceId) {
        Map<UUID, String> names = environmentNames(orgId, serviceId);
        if (names.isEmpty()) {
            return Map.of();
        }

        Map<UUID, Deployment> current = new HashMap<>();
        // Newest first, so the first row seen for an environment is its current
        // one and every later row for it is history.
        for (DeploymentEntity entity : deployments.findByOrgIdAndEnvironmentIdInOrderByDeployedAtDesc(
                orgId, List.copyOf(names.keySet()), Limit.of(MAX_HISTORY))) {
            current.computeIfAbsent(
                    entity.getEnvironmentId(), id -> view(entity, names.getOrDefault(id, "unknown")));
        }
        return Map.copyOf(current);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Deployment> history(UUID orgId, UUID serviceId, int limit) {
        Map<UUID, String> names = environmentNames(orgId, serviceId);
        if (names.isEmpty()) {
            return List.of();
        }
        return deployments
                .findByOrgIdAndEnvironmentIdInOrderByDeployedAtDesc(
                        orgId, List.copyOf(names.keySet()), Limit.of(Math.min(limit, MAX_HISTORY)))
                .stream()
                .map(entity -> view(entity, names.getOrDefault(entity.getEnvironmentId(), "unknown")))
                .toList();
    }

    /**
     * Environment ids to names, for this service and this organization only.
     *
     * <p>Every read starts here rather than from the deployment table, so an
     * environment id belonging to another organization has nowhere to enter:
     * the {@code IN} clause is built from what this caller can already see.
     */
    private Map<UUID, String> environmentNames(UUID orgId, UUID serviceId) {
        Map<UUID, String> names = new LinkedHashMap<>();
        environments.namesOf(orgId, serviceId).entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.naturalOrder()))
                .forEach(entry -> names.put(entry.getKey(), entry.getValue()));
        return names;
    }

    private static Deployment view(DeploymentEntity entity, String environmentName) {
        return new Deployment(
                entity.getId(),
                entity.getEnvironmentId(),
                environmentName,
                entity.getVersion(),
                entity.getCommitSha(),
                entity.getDeployedBy(),
                entity.getDeployedAt());
    }
}
