package com.ambitiousconcepts.opsatlas.operations.internal;

import com.ambitiousconcepts.opsatlas.operations.api.Observation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * One environment, one UTC day, as counters.
 *
 * <p>A probe increments these. It never inserts a row of its own - that is the
 * decision in ADR 0009, and the reason storage here is independent of probe
 * frequency.
 */
@Entity
@Table(name = "environment_day")
@IdClass(EnvironmentDayEntity.Key.class)
public class EnvironmentDayEntity {

    @Id
    @Column(name = "environment_id", nullable = false, updatable = false)
    private UUID environmentId;

    @Id
    @Column(name = "day", nullable = false, updatable = false)
    private LocalDate day;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "service_id", nullable = false, updatable = false)
    private UUID serviceId;

    @Column(name = "probes", nullable = false)
    private int probes;

    @Column(name = "successes", nullable = false)
    private int successes;

    @Column(name = "response_ms_sum", nullable = false)
    private long responseMsSum;

    @Column(name = "response_ms_min")
    private Integer responseMsMin;

    @Column(name = "response_ms_max")
    private Integer responseMsMax;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected EnvironmentDayEntity() {
        // for JPA
    }

    static EnvironmentDayEntity forDay(UUID orgId, UUID serviceId, UUID environmentId, LocalDate day) {
        EnvironmentDayEntity entity = new EnvironmentDayEntity();
        entity.environmentId = environmentId;
        entity.day = day;
        entity.orgId = orgId;
        entity.serviceId = serviceId;
        entity.probes = 0;
        entity.successes = 0;
        entity.responseMsSum = 0;
        entity.updatedAt = Instant.EPOCH;
        return entity;
    }

    void record(Observation observation) {
        probes += 1;
        updatedAt = observation.observedAt();

        if (!observation.outcome().succeeded()) {
            return;
        }
        successes += 1;

        Integer responseMs = observation.responseMs();
        if (responseMs == null) {
            return;
        }
        // Latency is accumulated only from probes that actually got a response.
        // A timeout has no round trip to report, and folding the timeout value
        // in would silently raise every mean the moment a service went down.
        responseMsSum += responseMs;
        responseMsMin = responseMsMin == null ? responseMs : Math.min(responseMsMin, responseMs);
        responseMsMax = responseMsMax == null ? responseMs : Math.max(responseMsMax, responseMs);
    }

    public LocalDate getDay() {
        return day;
    }

    public UUID getEnvironmentId() {
        return environmentId;
    }

    public int getProbes() {
        return probes;
    }

    public int getSuccesses() {
        return successes;
    }

    /** Mean across successful probes, or null when none succeeded. Mean - not a percentile. */
    public Integer getMeanResponseMs() {
        return successes == 0 || responseMsSum == 0 ? null : (int) (responseMsSum / successes);
    }

    public Integer getMaxResponseMs() {
        return responseMsMax;
    }

    public static class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        private UUID environmentId;
        private LocalDate day;

        public Key() {}

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return Objects.equals(environmentId, key.environmentId) && Objects.equals(day, key.day);
        }

        @Override
        public int hashCode() {
            return Objects.hash(environmentId, day);
        }
    }
}
