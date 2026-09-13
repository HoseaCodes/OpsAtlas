package com.ambitiousconcepts.opsatlas.governance.internal;

import com.ambitiousconcepts.opsatlas.shared.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One recorded change. Inserted, never updated or deleted. */
@Entity
@Table(name = "audit_event")
public class AuditEventEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "actor", nullable = false, updatable = false)
    private String actor;

    @Column(name = "action", nullable = false, updatable = false)
    private String action;

    @Column(name = "subject_type", nullable = false, updatable = false)
    private String subjectType;

    @Column(name = "subject_id", nullable = false, updatable = false)
    private UUID subjectId;

    @Column(name = "correlation_id", nullable = false, updatable = false)
    private String correlationId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    protected AuditEventEntity() {
        // for JPA
    }

    static AuditEventEntity of(
            UUID orgId,
            Instant occurredAt,
            String actor,
            String action,
            String subjectType,
            UUID subjectId,
            String correlationId,
            String payload) {
        AuditEventEntity entity = new AuditEventEntity();
        entity.id = Ids.newRowId();
        entity.orgId = orgId;
        entity.occurredAt = occurredAt;
        entity.actor = actor;
        entity.action = action;
        entity.subjectType = subjectType;
        entity.subjectId = subjectId;
        entity.correlationId = correlationId;
        entity.payload = payload;
        return entity;
    }

    public UUID getId() {
        return id;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getActor() {
        return actor;
    }

    public String getAction() {
        return action;
    }

    public String getSubjectType() {
        return subjectType;
    }

    public UUID getSubjectId() {
        return subjectId;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getPayload() {
        return payload;
    }
}
