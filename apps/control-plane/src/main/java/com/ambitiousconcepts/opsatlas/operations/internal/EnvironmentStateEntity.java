package com.ambitiousconcepts.opsatlas.operations.internal;

import com.ambitiousconcepts.opsatlas.operations.api.Observation;
import com.ambitiousconcepts.opsatlas.operations.api.ProbeOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** What is true about an environment right now. Maps {@code V5__operations.sql}. */
@Entity
@Table(name = "environment_state")
public class EnvironmentStateEntity {

    /**
     * How many consecutive failures before an environment is called DOWN rather
     * than DEGRADED.
     *
     * <p>One failed probe is a blip: a deploy rolling, a pod restarting, a
     * dropped packet. Paging on it, or painting the catalog red for it, teaches
     * people to ignore the signal. Three consecutive failures is a sustained
     * problem at any sensible probe interval.
     */
    static final int FAILURES_BEFORE_DOWN = 3;

    @Id
    @Column(name = "environment_id", nullable = false, updatable = false)
    private UUID environmentId;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "service_id", nullable = false, updatable = false)
    private UUID serviceId;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "detail")
    private String detail;

    @Column(name = "last_probe_at", nullable = false)
    private Instant lastProbeAt;

    @Column(name = "last_healthy_at")
    private Instant lastHealthyAt;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    @Column(name = "response_ms")
    private Integer responseMs;

    @Column(name = "last_status_code")
    private Integer lastStatusCode;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected EnvironmentStateEntity() {
        // for JPA
    }

    static EnvironmentStateEntity firstObservation(UUID orgId, UUID serviceId, Observation observation) {
        EnvironmentStateEntity entity = new EnvironmentStateEntity();
        entity.environmentId = observation.environmentId();
        entity.orgId = orgId;
        entity.serviceId = serviceId;
        entity.consecutiveFailures = 0;
        entity.apply(observation);
        return entity;
    }

    /**
     * Fold one probe result into the current state.
     *
     * <p>Out-of-order results are ignored rather than applied: two observers, or
     * one observer retrying, can deliver a batch late, and letting a stale probe
     * overwrite a newer one would make the catalog flap between two truths. The
     * counters in {@code environment_day} still take it, because a late probe
     * did still happen.
     */
    void apply(Observation observation) {
        if (lastProbeAt != null && observation.observedAt().isBefore(lastProbeAt)) {
            return;
        }

        this.lastProbeAt = observation.observedAt();
        this.lastStatusCode = observation.statusCode();
        this.updatedAt = observation.observedAt();

        if (observation.outcome().succeeded()) {
            this.consecutiveFailures = 0;
            this.status = "HEALTHY";
            this.detail = null;
            this.lastHealthyAt = observation.observedAt();
            this.responseMs = observation.responseMs();
            return;
        }

        this.consecutiveFailures += 1;
        this.status = consecutiveFailures >= FAILURES_BEFORE_DOWN ? "DOWN" : "DEGRADED";
        this.detail = describe(observation);
        // responseMs is deliberately left at its last successful value rather
        // than nulled: "it last answered in 40ms" stays true and useful while
        // it is failing, and a timeout's duration is not a response time.
    }

    private static String describe(Observation observation) {
        if (observation.detail() != null && !observation.detail().isBlank()) {
            return observation.detail();
        }
        // The database refuses an unhealthy state with no explanation, and an
        // observer that reports one is buggy - but a missing sentence should not
        // take down ingestion for every other environment in the batch.
        return switch (observation.outcome()) {
            case TIMEOUT -> "The probe timed out.";
            case UNREACHABLE -> "Nothing answered at the probe address.";
            case UNHEALTHY -> "The probe answered "
                    + (observation.statusCode() == null ? "unsuccessfully" : String.valueOf(observation.statusCode()))
                    + ".";
            case HEALTHY -> "Healthy.";
        };
    }

    public UUID getEnvironmentId() {
        return environmentId;
    }

    public UUID getServiceId() {
        return serviceId;
    }

    public String getStatus() {
        return status;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getLastProbeAt() {
        return lastProbeAt;
    }

    public Instant getLastHealthyAt() {
        return lastHealthyAt;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public Integer getResponseMs() {
        return responseMs;
    }

    /** Kept for callers that need to distinguish "answered badly" from "did not answer". */
    public Integer getLastStatusCode() {
        return lastStatusCode;
    }

    static ProbeOutcome outcomeOf(String status) {
        return "HEALTHY".equals(status) ? ProbeOutcome.HEALTHY : ProbeOutcome.UNHEALTHY;
    }
}
