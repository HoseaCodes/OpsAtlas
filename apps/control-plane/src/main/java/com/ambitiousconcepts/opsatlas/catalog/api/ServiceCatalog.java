package com.ambitiousconcepts.opsatlas.catalog.api;

import com.ambitiousconcepts.opsatlas.shared.PageResponse;
import java.util.UUID;

/**
 * Read access to the catalog, for callers outside the {@code catalog} module.
 *
 * <p>Writes live on {@link ServiceRegistration}; the two are separate so that a
 * caller needing only to read the catalog cannot register anything. Nothing
 * outside {@code catalog} touches a repository or an entity.
 */
public interface ServiceCatalog {

    /**
     * One page of services, ordered by registration time.
     *
     * @param orgId  the organization to scope to; never inferred
     * @param cursor an opaque cursor from a previous page, or null to start
     * @param limit  maximum rows to return
     */
    PageResponse<ServiceSummary> list(UUID orgId, String cursor, int limit);

    /**
     * One service, in full.
     *
     * @param slug the name from metadata.name, unique within the organization
     * @throws com.ambitiousconcepts.opsatlas.shared.NotFoundException when no such
     *     service exists <em>in this organization</em>. A service belonging to
     *     another organization is reported as absent rather than forbidden: a 403
     *     would confirm it exists. See docs/adr/0003-org-scoping-stub.md.
     */
    ServiceDetail get(UUID orgId, String slug);
}
