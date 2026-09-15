package com.ambitiousconcepts.opsatlas.identity.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A credential this system issued to one of its own machines (ADR 0013). */
@Entity
@Table(name = "service_credential")
class ServiceCredentialEntity {

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(nullable = false)
    private String name;

    @Column(name = "secret_hash", nullable = false)
    private String secretHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    protected ServiceCredentialEntity() {}

    static ServiceCredentialEntity of(UUID orgId, String name, String secretHash, Instant now) {
        ServiceCredentialEntity entity = new ServiceCredentialEntity();
        entity.id = UUID.randomUUID();
        entity.orgId = orgId;
        entity.name = name;
        entity.secretHash = secretHash;
        entity.createdAt = now;
        return entity;
    }

    UUID getOrgId() {
        return orgId;
    }

    String getName() {
        return name;
    }

    void usedAt(Instant when) {
        this.lastUsedAt = when;
    }

    /** Rotation: the same machine, a new key. The old one stops working at once. */
    void rotateTo(String newSecretHash) {
        this.secretHash = newSecretHash;
        this.lastUsedAt = null;
    }

    boolean hasHash(String candidate) {
        return secretHash.equals(candidate);
    }
}
