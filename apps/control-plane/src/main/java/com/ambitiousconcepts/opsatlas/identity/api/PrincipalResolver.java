package com.ambitiousconcepts.opsatlas.identity.api;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the caller for a request.
 *
 * <p>TODO(auth): this is the entire migration path to real authentication.
 * Replacing the single implementation of this interface - and nothing else -
 * is what turns the stubbed organization into an authenticated one. CLAUDE.md
 * section 7 and {@code docs/adr/0003-org-scoping-stub.md}.
 */
public interface PrincipalResolver {

    /**
     * @return the caller, never null. An implementation that cannot identify the
     *     caller throws rather than returning a default, so that a scoping
     *     failure is loud.
     */
    Principal resolve(HttpServletRequest request);
}
