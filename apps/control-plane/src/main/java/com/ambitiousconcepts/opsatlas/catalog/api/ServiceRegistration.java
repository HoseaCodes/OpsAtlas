package com.ambitiousconcepts.opsatlas.catalog.api;

import java.util.UUID;

/**
 * Registering a service from its manifest.
 *
 * <p>The document arrives in the request body. Slice one has no GitHub
 * integration - CLAUDE.md section 14 defers it - so nothing here reads a
 * repository. The {@code repository} recorded against a service is whatever its
 * manifest declares, and the console says so rather than implying a sync that
 * does not exist.
 */
public interface ServiceRegistration {

    /**
     * Register a service, or replay an unchanged registration.
     *
     * @param document   the raw service.yaml
     * @param sourcePath where the manifest lives in its repository
     * @param sourceRef  the branch or commit it came from, or null
     * @throws com.ambitiousconcepts.opsatlas.shared.ValidationFailedException the manifest is invalid
     * @throws com.ambitiousconcepts.opsatlas.shared.ConflictException a different manifest is already
     *     registered from this location, or the name is taken
     */
    RegistrationOutcome register(UUID orgId, String document, String sourcePath, String sourceRef);

    /**
     * Replace a registered service's manifest.
     *
     * @param ifMatchVersion the version the caller read; null means the header was absent
     * @throws com.ambitiousconcepts.opsatlas.shared.PreconditionException If-Match was missing or stale
     */
    ServiceDetail update(UUID orgId, String slug, String document, Long ifMatchVersion, String sourceRef);
}
