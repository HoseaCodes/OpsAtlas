package com.ambitiousconcepts.opsatlas.identity.api;

/**
 * The principal for the request being served.
 *
 * <p>This exists so that other modules can read the current organization without
 * importing anything from {@code identity.internal} - the boundary
 * {@code ArchitectureTest} enforces. Callers inject this, read
 * {@code get().orgId()}, and pass that id explicitly into every repository call.
 *
 * <p>The explicitness is the point. CLAUDE.md section 7 requires every query to
 * filter on {@code org_id}, and an argument that is visible in the method
 * signature can be reviewed by eye; an ambient filter applied by the ORM cannot.
 */
public interface CurrentPrincipal {

    /**
     * @throws IllegalStateException when called outside a request, which is a
     *     programming error rather than a runtime condition
     */
    Principal get();
}
