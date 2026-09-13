package com.ambitiousconcepts.opsatlas.catalog.api;

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
 * <p>As with {@link ServiceSummary}, there is no health, attainment or 30-day
 * history here. Those come from the observer, which is phase 7. A field the
 * console could render as a status would imply a capability the backend does not
 * have.
 *
 * @param owner the owning team's slug, or null when the manifest declares none
 * @param manifest the normalized document as validated and stored
 * @param version optimistic-lock version; the ETag, and what If-Match must carry
 */
public record ServiceDetail(
        UUID id,
        String slug,
        String displayName,
        String repository,
        int tier,
        String runtime,
        String lifecycle,
        String owner,
        String schemaVersion,
        String manifestDigest,
        String sourcePath,
        String sourceRef,
        List<EnvironmentView> environments,
        JsonNode manifest,
        Instant registeredAt,
        Instant updatedAt,
        long version) {

    /**
     * @param readinessPath from spec.health.readiness, applied to every environment
     * @param lastObservedAt always null in slice one - nothing observes anything yet.
     *     Present so the console's "never observed" state reads a real field rather
     *     than inferring absence from a missing key.
     */
    public record EnvironmentView(
            UUID id, String name, String url, String readinessPath, String livenessPath, Instant lastObservedAt) {}
}
