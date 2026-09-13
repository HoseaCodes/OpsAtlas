package com.ambitiousconcepts.opsatlas.catalog.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * A service as it appears in the catalog list.
 *
 * <p>Deliberately does not carry health, SLO attainment or a 30-day history.
 * Those are produced by the observer, which is phase 7. A field here that the
 * console could render as a status would be a capability the backend does not
 * have - see CLAUDE.md section 10. The console renders an explicit
 * "never observed" state for those columns instead.
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
