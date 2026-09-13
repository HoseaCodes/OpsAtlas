package com.ambitiousconcepts.opsatlas.catalog.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * A service as it appears in the catalog list.
 *
 * <p>Carries no health, deliberately. Health is {@code operations}' to report
 * and is served separately at {@code /api/v1/health} - see
 * {@code docs/adr/0010-health-is-composed-by-the-console.md}. The practical
 * effect is that the catalog still lists every service when the health read is
 * slow or failing, and the console says which half is missing.
 *
 * @param id          stable identifier, also the pagination sort key
 * @param slug        the name from metadata.name
 * @param displayName human-facing name, or null to fall back to slug
 * @param repository  where the manifest came from
 * @param tier        1 customer-facing, 2 business hours, 3 internal
 * @param runtime     free-form runtime slug, or null when not declared
 * @param lifecycle   active, deprecated or retired
 * @param owned       whether a team is accountable; false is a real and common answer
 * @param registeredAt when this service first entered the catalog
 * @param version     optimistic-lock version, surfaced as the ETag
 */
public record ServiceSummary(
        UUID id,
        String slug,
        @Schema(nullable = true)
        String displayName,
        String repository,
        int tier,
        @Schema(nullable = true)
        String runtime,
        String lifecycle,
        boolean owned,
        Instant registeredAt,
        long version) {}
