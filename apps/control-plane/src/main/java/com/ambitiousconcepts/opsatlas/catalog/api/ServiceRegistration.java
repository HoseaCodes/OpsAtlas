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

    /**
     * Apply a manifest read from the repository that owns it.
     *
     * <p>Unlike {@link #update}, this carries no {@code If-Match}, and that is a
     * decision rather than an omission. A repository is authoritative for its own
     * {@code service.yaml}: the file in the repository <em>is</em> the
     * declaration, so a poller replaying it is not racing anyone, it is
     * restoring the declared state.
     *
     * <p>The consequence, which is real: an edit made through the console to a
     * service backed by a source is overwritten on the next sync. That is the
     * correct outcome - the console is not where such a service is declared -
     * but it will surprise someone at least once. Recorded in ADR 0008.
     *
     * <p>Renames are refused here exactly as they are on {@link #update}. A
     * repository changing {@code metadata.name} is a migration, not an edit, and
     * being authoritative does not make it not a migration.
     *
     * @param serviceId the service this source already produced
     */
    ServiceDetail updateFromSource(UUID orgId, UUID serviceId, String document, String sourceRef);
}
