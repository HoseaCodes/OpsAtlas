package com.ambitiousconcepts.opsatlas.operations.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One reported deployment. Maps {@code V8__deployments.sql}. */
@Entity
@Table(name = "deployment")
public class DeploymentEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "environment_id", nullable = false, updatable = false)
    private UUID environmentId;

    @Column(name = "version", nullable = false, updatable = false)
    private String version;

    @Column(name = "commit_sha", updatable = false)
    private String commitSha;

    @Column(name = "deployed_by", updatable = false)
    private String deployedBy;

    @Column(name = "deployed_at", nullable = false, updatable = false)
    private Instant deployedAt;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DeploymentEntity() {
        // for JPA
    }

    /**
     * A deployment as reported.
     *
     * <p>Nothing here is derived. The caller supplies the time the deploy
     * happened rather than this class reading a clock, because a pipeline
     * reporting late must be able to state the real time - otherwise "in place
     * for" silently measures how long ago OpsAtlas was told.
     */
    static DeploymentEntity reported(
            UUID id,
            UUID orgId,
            UUID environmentId,
            String version,
            String commitSha,
            String deployedBy,
            Instant deployedAt,
            String idempotencyKey,
            Instant now) {
        DeploymentEntity entity = new DeploymentEntity();
        entity.id = id;
        entity.orgId = orgId;
        entity.environmentId = environmentId;
        entity.version = version;
        entity.commitSha = commitSha;
        entity.deployedBy = deployedBy;
        entity.deployedAt = deployedAt;
        entity.idempotencyKey = idempotencyKey;
        entity.createdAt = now;
        return entity;
    }

    public UUID getId() {
        return id;
    }

    public UUID getEnvironmentId() {
        return environmentId;
    }

    public String getVersion() {
        return version;
    }

    public String getCommitSha() {
        return commitSha;
    }

    public String getDeployedBy() {
        return deployedBy;
    }

    public Instant getDeployedAt() {
        return deployedAt;
    }
}
