package com.ambitiousconcepts.opsatlas.operations.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * One probe result, as reported by an observer.
 *
 * <p>This crosses the wire and is then <em>folded into counters</em> - it is
 * never stored as a row of its own. See
 * {@code docs/adr/0009-observations-are-rolled-up-not-a-time-series.md}.
 *
 * @param environmentId which environment was probed
 * @param observedAt when the probe ran, as reported by the observer. Used to
 *     decide which UTC day the result counts toward.
 * @param outcome what the probe found
 * @param responseMs round-trip time. Null when nothing answered, because there
 *     is no duration to report for a request that got no response - recording
 *     the timeout value instead would put the timeout into the latency average.
 * @param statusCode the HTTP status, where there was one. Null for a timeout or
 *     a refused connection, which is itself the distinction between "answered
 *     badly" and "did not answer".
 * @param detail why it failed, in words. Required when the outcome is not healthy.
 */
public record Observation(
        UUID environmentId,
        Instant observedAt,
        ProbeOutcome outcome,
        @Schema(nullable = true) Integer responseMs,
        @Schema(nullable = true) Integer statusCode,
        @Schema(nullable = true) String detail) {}
