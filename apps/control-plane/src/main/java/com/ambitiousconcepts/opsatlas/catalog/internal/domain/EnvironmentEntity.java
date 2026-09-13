package com.ambitiousconcepts.opsatlas.catalog.internal.domain;

import com.ambitiousconcepts.opsatlas.shared.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * An environment a service runs in.
 *
 * <p>Deliberately not a JPA association from {@link ServiceEntity}. Environments
 * are fetched explicitly by service id when they are needed, which keeps the
 * catalog list query - by far the hottest path - free of any risk of an N+1
 * that only appears once there are enough rows to notice.
 */
@Entity
@Table(name = "environment")
public class EnvironmentEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "service_id", nullable = false, updatable = false)
    private UUID serviceId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "url")
    private String url;

    @Column(name = "readiness_path")
    private String readinessPath;

    @Column(name = "liveness_path")
    private String livenessPath;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected EnvironmentEntity() {
        // for JPA
    }

    public static EnvironmentEntity create(
            UUID orgId, UUID serviceId, String name, String url, String readinessPath, String livenessPath,
            Instant now) {
        EnvironmentEntity entity = new EnvironmentEntity();
        entity.id = Ids.newRowId();
        entity.orgId = orgId;
        entity.serviceId = serviceId;
        entity.name = name;
        entity.createdAt = now;
        entity.apply(url, readinessPath, livenessPath, now);
        return entity;
    }

    /**
     * Update an environment in place, keeping its id.
     *
     * <p>Re-registration matches environments by name and updates them rather
     * than deleting and re-inserting. Once the observer attaches observations to
     * an environment id, churning those ids on every manifest edit would throw
     * away the history attached to them.
     */
    public void apply(String url, String readinessPath, String livenessPath, Instant now) {
        this.url = url;
        this.readinessPath = readinessPath;
        this.livenessPath = livenessPath;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getServiceId() {
        return serviceId;
    }

    public String getName() {
        return name;
    }

    public String getUrl() {
        return url;
    }

    public String getReadinessPath() {
        return readinessPath;
    }

    public String getLivenessPath() {
        return livenessPath;
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
