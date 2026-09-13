package com.ambitiousconcepts.opsatlas.operations.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What is known about an environment's health.
 *
 * <p>The whole record is absent for an environment nobody has probed, rather
 * than present with nulls. "Never observed" and "observed and found to be down"
 * are different facts and the API keeps them apart - which is what lets the
 * console render an outline rather than a fabricated reading.
 *
 * @param status HEALTHY, DEGRADED or DOWN
 * @param probeAvailability fraction of probes that succeeded over the retained
 *     window, 0 to 1. <strong>Not an SLO measurement.</strong> It is the share of
 *     probes that succeeded from one vantage point against a health endpoint; a
 *     service can serve errors to every real user while its readiness endpoint
 *     answers happily.
 * @param days newest last, one entry per UTC day. Shorter than the window when
 *     the environment has not been watched that long.
 */
public record EnvironmentHealth(
        UUID environmentId,
        String status,
        @Schema(nullable = true) String detail,
        Instant lastProbeAt,
        @Schema(nullable = true) Instant lastHealthyAt,
        int consecutiveFailures,
        @Schema(nullable = true) Integer responseMs,
        @Schema(nullable = true) Double probeAvailability,
        List<DailyAvailability> days) {

    /**
     * One UTC day of probing.
     *
     * @param availability successes divided by probes, 0 to 1
     * @param meanResponseMs mean round trip across successful probes. Mean, and
     *     called mean - this is not a percentile and must not be presented as one.
     */
    public record DailyAvailability(
            String day,
            int probes,
            int successes,
            double availability,
            @Schema(nullable = true) Integer meanResponseMs,
            @Schema(nullable = true) Integer maxResponseMs) {}
}
