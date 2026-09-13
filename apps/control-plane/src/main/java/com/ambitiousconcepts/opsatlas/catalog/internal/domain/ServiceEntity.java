package com.ambitiousconcepts.opsatlas.catalog.internal.domain;

import com.ambitiousconcepts.opsatlas.shared.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A registered service.
 *
 * <p>Maps {@code V1__catalog.sql}. Hibernate runs with {@code ddl-auto: validate},
 * so this class and that migration must agree or the application refuses to
 * start - which is the intended failure mode.
 *
 * <p>Package-private to {@code catalog.internal}: no other module sees this type.
 * Callers outside {@code catalog} receive records from {@code catalog.api}.
 */
@Entity
@Table(name = "service")
public class ServiceEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "team_id")
    private UUID teamId;

    @Column(name = "slug", nullable = false)
    private String slug;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "repository", nullable = false)
    private String repository;

    @Column(name = "tier", nullable = false)
    private short tier;

    @Column(name = "runtime")
    private String runtime;

    @Column(name = "lifecycle", nullable = false)
    private String lifecycle;

    @Column(name = "schema_version", nullable = false)
    private String schemaVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "manifest", nullable = false)
    private String manifest;

    @Column(name = "manifest_digest", nullable = false)
    private String manifestDigest;

    @Column(name = "source_path", nullable = false)
    private String sourcePath;

    @Column(name = "source_ref")
    private String sourceRef;

    /** Optimistic locking. Surfaced as an ETag; PUT requires a matching If-Match. */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ServiceEntity() {
        // for JPA
    }

    /**
     * Register a new service.
     *
     * <p>The id is a UUIDv7 assigned here rather than by the database, so it is
     * available before insert and so that ordering by id is ordering by
     * registration time - which is what the catalog's keyset pagination relies
     * on.
     */
    public static ServiceEntity register(UUID orgId, ServiceFields fields, Instant now) {
        ServiceEntity entity = new ServiceEntity();
        entity.id = Ids.newRowId();
        entity.orgId = orgId;
        entity.createdAt = now;
        entity.apply(fields, now);
        return entity;
    }

    /**
     * Apply a newly ingested manifest to an existing service.
     *
     * <p>{@code orgId}, {@code id} and {@code createdAt} are deliberately not
     * touched: a re-registration replaces what the manifest says, not which
     * organization the row belongs to or when it first appeared.
     */
    public void apply(ServiceFields fields, Instant now) {
        this.teamId = fields.teamId();
        this.slug = fields.slug();
        this.displayName = fields.displayName();
        this.repository = fields.repository();
        this.tier = fields.tier();
        this.runtime = fields.runtime();
        this.lifecycle = fields.lifecycle();
        this.schemaVersion = fields.schemaVersion();
        this.manifest = fields.manifest();
        this.manifestDigest = fields.manifestDigest();
        this.sourcePath = fields.sourcePath();
        this.sourceRef = fields.sourceRef();
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getTeamId() {
        return teamId;
    }

    public String getSlug() {
        return slug;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getRepository() {
        return repository;
    }

    public short getTier() {
        return tier;
    }

    public String getRuntime() {
        return runtime;
    }

    public String getLifecycle() {
        return lifecycle;
    }

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public String getManifest() {
        return manifest;
    }

    public String getManifestDigest() {
        return manifestDigest;
    }

    public String getSourcePath() {
        return sourcePath;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
