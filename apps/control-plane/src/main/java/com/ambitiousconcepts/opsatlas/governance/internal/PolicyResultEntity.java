package com.ambitiousconcepts.opsatlas.governance.internal;

import com.ambitiousconcepts.opsatlas.shared.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One stored evaluation.
 *
 * <p>No {@code @Version} column: these rows are inserted and never updated, so
 * there is no concurrent modification for a lock to protect against.
 */
@Entity
@Table(name = "policy_result")
public class PolicyResultEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "service_id", nullable = false, updatable = false)
    private UUID serviceId;

    @Column(name = "policy_set_version", nullable = false, updatable = false)
    private String policySetVersion;

    @Column(name = "evaluated_at", nullable = false, updatable = false)
    private Instant evaluatedAt;

    @Column(name = "checks_passed", nullable = false, updatable = false)
    private short checksPassed;

    @Column(name = "checks_applicable", nullable = false, updatable = false)
    private short checksApplicable;

    protected PolicyResultEntity() {
        // for JPA
    }

    static PolicyResultEntity of(
            UUID orgId, UUID serviceId, String policySetVersion, Instant evaluatedAt, int passed, int applicable) {
        PolicyResultEntity entity = new PolicyResultEntity();
        entity.id = Ids.newRowId();
        entity.orgId = orgId;
        entity.serviceId = serviceId;
        entity.policySetVersion = policySetVersion;
        entity.evaluatedAt = evaluatedAt;
        entity.checksPassed = (short) passed;
        entity.checksApplicable = (short) applicable;
        return entity;
    }

    public UUID getId() {
        return id;
    }

    public UUID getServiceId() {
        return serviceId;
    }

    public String getPolicySetVersion() {
        return policySetVersion;
    }

    public Instant getEvaluatedAt() {
        return evaluatedAt;
    }

    public short getChecksPassed() {
        return checksPassed;
    }

    public short getChecksApplicable() {
        return checksApplicable;
    }
}
