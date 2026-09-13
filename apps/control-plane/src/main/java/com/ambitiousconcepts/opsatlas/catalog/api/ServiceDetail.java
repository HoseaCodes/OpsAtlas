package com.ambitiousconcepts.opsatlas.catalog.api;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A registered service, in full.
 *
 * <p>Carries the normalized manifest alongside the flat columns so the console
 * can render journeys, dependencies, SLO and health paths without a second
 * request, and so a reader can see exactly what was stored rather than a
 * summary of it.
 *
 * <p>Carries no health. That is {@code operations}' to report, and is served at
 * {@code /api/v1/services/{slug}/health} - see
 * {@code docs/adr/0010-health-is-composed-by-the-console.md}.
 *
 * @param owner the owning team's slug, or null when the manifest declares none
 * @param manifest the normalized document as validated and stored
 * @param version optimistic-lock version; the ETag, and what If-Match must carry
 */
public record ServiceDetail(
        UUID id,
        String slug,
        @Schema(nullable = true)
        String displayName,
        String repository,
        int tier,
        @Schema(nullable = true)
        String runtime,
        String lifecycle,
        @Schema(nullable = true)
        String owner,
        String schemaVersion,
        String manifestDigest,
        String sourcePath,
        @Schema(nullable = true)
        String sourceRef,
        List<EnvironmentView> environments,
        JsonNode manifest,
        Instant registeredAt,
        Instant updatedAt,
        long version) {

    /**
     * @param readinessPath from spec.health.readiness, applied to every environment
     */
    public record EnvironmentView(
            UUID id,
            String name,
            @Schema(nullable = true) String url,
            @Schema(nullable = true) String readinessPath,
            @Schema(nullable = true) String livenessPath) {}
}
