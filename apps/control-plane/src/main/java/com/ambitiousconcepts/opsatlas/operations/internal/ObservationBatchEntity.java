package com.ambitiousconcepts.opsatlas.operations.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A batch that has already been applied.
 *
 * <p>The stored response is replayed verbatim to a retry rather than recomputed.
 * Recomputing could return something different once state has moved on, and a
 * retry must not be able to observe that it was a retry.
 */
@Entity
@Table(name = "observation_batch")
@IdClass(ObservationBatchEntity.Key.class)
public class ObservationBatchEntity {

    @Id
    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Id
    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "observations", nullable = false, updatable = false)
    private int observations;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response", nullable = false, updatable = false)
    private String response;

    protected ObservationBatchEntity() {
        // for JPA
    }

    static ObservationBatchEntity of(
            UUID orgId, String idempotencyKey, Instant receivedAt, int observations, String response) {
        ObservationBatchEntity entity = new ObservationBatchEntity();
        entity.orgId = orgId;
        entity.idempotencyKey = idempotencyKey;
        entity.receivedAt = receivedAt;
        entity.observations = observations;
        entity.response = response;
        return entity;
    }

    public String getResponse() {
        return response;
    }

    public static class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        private UUID orgId;
        private String idempotencyKey;

        public Key() {}

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return Objects.equals(orgId, key.orgId) && Objects.equals(idempotencyKey, key.idempotencyKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(orgId, idempotencyKey);
        }
    }
}
