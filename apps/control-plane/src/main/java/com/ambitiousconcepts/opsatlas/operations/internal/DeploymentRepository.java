package com.ambitiousconcepts.opsatlas.operations.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

interface DeploymentRepository extends JpaRepository<DeploymentEntity, UUID> {

    /** What a replayed report resolves to, rather than a second row. */
    Optional<DeploymentEntity> findByOrgIdAndEnvironmentIdAndIdempotencyKey(
            UUID orgId, UUID environmentId, String idempotencyKey);

    /**
     * Deployments across a service's environments, newest first.
     *
     * <p>One ordered scan, with the caller taking the first row per environment
     * for the "current" view. A service is capped at 20 environments by the
     * schema and this index is already in {@code deployed_at DESC} order, so a
     * lateral join per environment would be more machinery for the same answer.
     */
    List<DeploymentEntity> findByOrgIdAndEnvironmentIdInOrderByDeployedAtDesc(
            UUID orgId, List<UUID> environmentIds, Limit limit);
}
