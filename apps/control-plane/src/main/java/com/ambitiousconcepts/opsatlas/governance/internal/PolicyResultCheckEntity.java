package com.ambitiousconcepts.opsatlas.governance.internal;

import com.ambitiousconcepts.opsatlas.governance.api.CheckOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** One rule's verdict within one evaluation. */
@Entity
@Table(name = "policy_result_check")
@IdClass(PolicyResultCheckEntity.Key.class)
public class PolicyResultCheckEntity {

    @Id
    @Column(name = "policy_result_id", nullable = false, updatable = false)
    private UUID policyResultId;

    @Id
    @Column(name = "check_id", nullable = false, updatable = false)
    private String checkId;

    @Column(name = "status", nullable = false, updatable = false)
    private String status;

    @Column(name = "detail", updatable = false)
    private String detail;

    protected PolicyResultCheckEntity() {
        // for JPA
    }

    static PolicyResultCheckEntity of(UUID policyResultId, String checkId, CheckOutcome outcome) {
        PolicyResultCheckEntity entity = new PolicyResultCheckEntity();
        entity.policyResultId = policyResultId;
        entity.checkId = checkId;
        entity.status = outcome.status().name();
        entity.detail = outcome.detail();
        return entity;
    }

    public String getCheckId() {
        return checkId;
    }

    public String getStatus() {
        return status;
    }

    public String getDetail() {
        return detail;
    }

    /** Composite key: one verdict per rule per evaluation. */
    public static class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        private UUID policyResultId;
        private String checkId;

        public Key() {}

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return Objects.equals(policyResultId, key.policyResultId) && Objects.equals(checkId, key.checkId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(policyResultId, checkId);
        }
    }
}
