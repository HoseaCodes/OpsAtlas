package com.ambitiousconcepts.opsatlas.catalog.api;

import com.ambitiousconcepts.opsatlas.shared.PageResponse;
import java.util.UUID;

/**
 * Read access to the catalog, for callers outside the {@code catalog} module.
 *
 * <p>This is the module's whole public surface in phase 1. Registration is added
 * in phase 2. Nothing outside {@code catalog} touches a repository or an entity.
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
}
