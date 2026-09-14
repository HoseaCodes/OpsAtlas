package com.ambitiousconcepts.opsatlas.identity.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A caller this system knows, and the organization they act for (ADR 0013). */
@Entity
@Table(name = "principal")
class PrincipalEntity {

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(nullable = false)
    private String issuer;

    @Column(nullable = false)
    private String subject;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PrincipalEntity() {}

    UUID getOrgId() {
        return orgId;
    }

    String getSubject() {
        return subject;
    }

    String getDisplayName() {
        return displayName;
    }
}
