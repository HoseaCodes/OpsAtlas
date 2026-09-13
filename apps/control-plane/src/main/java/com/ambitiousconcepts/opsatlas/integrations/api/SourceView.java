package com.ambitiousconcepts.opsatlas.integrations.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * A watched repository and how its last sync went.
 *
 * <p>{@code lastSuccessAt} and {@code lastAttemptAt} are separate fields on
 * purpose. Together they answer the question a reader of a failing source
 * actually has - "how current is what I am looking at" - which neither answers
 * alone.
 *
 * @param lastOutcome one of REGISTERED, UPDATED, UNCHANGED, REJECTED, CONFLICT,
 *     NOT_FOUND, UNAUTHORIZED, RATE_LIMITED, UNREACHABLE; null before the first attempt
 * @param lastDetail why a non-success outcome happened; null when it succeeded
 * @param serviceId the service this source produced, once it has produced one.
 *     Not cleared when a later sync fails - the service is still registered.
 */
public record SourceView(
        UUID id,
        String provider,
        String repository,
        String ref,
        String path,
        boolean enabled,
        @Schema(nullable = true) Instant lastAttemptAt,
        @Schema(nullable = true) Instant lastSuccessAt,
        @Schema(nullable = true) String lastOutcome,
        @Schema(nullable = true) String lastDetail,
        int consecutiveFailures,
        @Schema(nullable = true) UUID serviceId,
        Instant createdAt,
        long version) {

    /** Whether the last attempt did what it was supposed to. */
    public boolean healthy() {
        return lastOutcome != null
                && ("REGISTERED".equals(lastOutcome) || "UPDATED".equals(lastOutcome) || "UNCHANGED".equals(lastOutcome));
    }
}
