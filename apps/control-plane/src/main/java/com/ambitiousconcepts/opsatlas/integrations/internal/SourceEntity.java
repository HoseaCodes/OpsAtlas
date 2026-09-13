package com.ambitiousconcepts.opsatlas.integrations.internal;

import com.ambitiousconcepts.opsatlas.integrations.api.SourceRef;
import com.ambitiousconcepts.opsatlas.shared.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** A repository OpsAtlas watches. Maps {@code V4__sources.sql}. */
@Entity
@Table(name = "source")
public class SourceEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "provider", nullable = false, updatable = false)
    private String provider;

    @Column(name = "repository", nullable = false, updatable = false)
    private String repository;

    @Column(name = "git_ref", nullable = false)
    private String gitRef;

    @Column(name = "path", nullable = false, updatable = false)
    private String path;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "last_success_at")
    private Instant lastSuccessAt;

    @Column(name = "last_outcome")
    private String lastOutcome;

    @Column(name = "last_detail")
    private String lastDetail;

    @Column(name = "etag")
    private String etag;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    @Column(name = "service_id")
    private UUID serviceId;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SourceEntity() {
        // for JPA
    }

    static SourceEntity watch(UUID orgId, String provider, SourceRef ref, Instant now) {
        SourceEntity entity = new SourceEntity();
        entity.id = Ids.newRowId();
        entity.orgId = orgId;
        entity.provider = provider;
        entity.repository = ref.repository();
        entity.gitRef = ref.ref();
        entity.path = ref.path();
        entity.enabled = true;
        entity.consecutiveFailures = 0;
        entity.createdAt = now;
        entity.updatedAt = now;
        return entity;
    }

    /**
     * Record a sync that produced or confirmed a service.
     *
     * <p>{@code serviceId} is only ever set, never cleared: a source that starts
     * failing has not un-registered the service it produced, and losing the link
     * would make the console unable to say which service has gone stale.
     */
    void succeeded(String outcome, UUID serviceId, String etag, Instant now) {
        this.lastOutcome = outcome;
        this.lastDetail = null;
        this.lastAttemptAt = now;
        this.lastSuccessAt = now;
        this.consecutiveFailures = 0;
        this.updatedAt = now;
        if (serviceId != null) {
            this.serviceId = serviceId;
        }
        if (etag != null) {
            this.etag = etag;
        }
    }

    /**
     * Record a sync that did not.
     *
     * <p>{@code lastSuccessAt} is deliberately untouched, because it is the
     * answer to "how current is what I am looking at" - the question the console
     * has to answer when a source is failing.
     */
    void failed(String outcome, String detail, Instant now) {
        this.lastOutcome = outcome;
        this.lastDetail = detail;
        this.lastAttemptAt = now;
        this.consecutiveFailures += 1;
        this.updatedAt = now;
        // A failed fetch says nothing about whether the content changed, so the
        // stored ETag stays: the next attempt should still be conditional.
    }

    void setEnabled(boolean enabled, Instant now) {
        this.enabled = enabled;
        this.updatedAt = now;
    }

    public SourceRef toRef() {
        return new SourceRef(repository, gitRef, path);
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public String getProvider() {
        return provider;
    }

    public String getRepository() {
        return repository;
    }

    public String getGitRef() {
        return gitRef;
    }

    public String getPath() {
        return path;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public Instant getLastSuccessAt() {
        return lastSuccessAt;
    }

    public String getLastOutcome() {
        return lastOutcome;
    }

    public String getLastDetail() {
        return lastDetail;
    }

    public String getEtag() {
        return etag;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public UUID getServiceId() {
        return serviceId;
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
