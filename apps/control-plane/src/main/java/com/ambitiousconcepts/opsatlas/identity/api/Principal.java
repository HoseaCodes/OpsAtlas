package com.ambitiousconcepts.opsatlas.identity.api;

import java.util.UUID;

/**
 * Who is making the current request, and which organization's data they see.
 *
 * <p>Until authentication exists there is exactly one of these, produced by a
 * stub resolver. See {@code docs/adr/0003-org-scoping-stub.md}.
 *
 * @param orgId       the organization every query in this request must filter on
 * @param subject     stable identifier for the caller
 * @param displayName human-readable name for audit events
 */
public record Principal(UUID orgId, String subject, String displayName) {

    public Principal {
        if (orgId == null) {
            throw new IllegalArgumentException("a principal without an organization cannot scope a query");
        }
    }
}
