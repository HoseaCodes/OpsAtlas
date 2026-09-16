package com.ambitiousconcepts.opsatlas.operations.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Recording and reading what was deployed where. */
public interface DeploymentLedger {

    /**
     * Records a deployment, or returns the existing one when this report has
     * already been seen.
     *
     * <p>Idempotent on {@code (environment, idempotencyKey)}, enforced by a
     * unique constraint rather than a read-then-write, which races with itself
     * under exactly the retry storm it exists to survive.
     */
    Recorded record(UUID orgId, UUID environmentId, String environmentName, DeploymentReport report);

    /**
     * @param created false when this report had already been recorded. Stated by
     *     the ledger rather than inferred by the caller comparing fields, which
     *     cannot distinguish a replay from a genuine redeploy of the same version
     */
    record Recorded(Deployment deployment, boolean created) {}

    /**
     * The most recent deployment for each environment of a service.
     *
     * <p>An environment absent from the map has never had a deployment
     * reported, which is <strong>not</strong> the same as never having been
     * deployed - it means nothing told OpsAtlas. The distinction matters enough
     * that the console is required to render it as absence.
     */
    Map<UUID, Deployment> currentByEnvironment(UUID orgId, UUID serviceId);

    /** Recent deployments for a service, newest first, across all environments. */
    List<Deployment> history(UUID orgId, UUID serviceId, int limit);
}
