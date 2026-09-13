package com.ambitiousconcepts.opsatlas.identity.internal;

import com.ambitiousconcepts.opsatlas.identity.api.Principal;
import com.ambitiousconcepts.opsatlas.identity.api.PrincipalResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * TODO(auth): the stub. Returns the single seeded organization for every request.
 *
 * <p>There is no authentication in this phase, so there is nothing to resolve
 * the caller from. This returns a fixed principal whose organization matches the
 * row inserted by Flyway migration {@code V2__seed_organization.sql}.
 *
 * <p>This class is the whole of what has to change when authentication arrives.
 * Nothing else in the codebase knows that the organization is stubbed: every
 * other component receives an {@code orgId} and filters on it exactly as it
 * would with a real one. That is deliberate, and it is the reason the data model
 * needs no migration when auth lands.
 *
 * <p><strong>This does not make OpsAtlas multi-tenant.</strong> There is one
 * organization, no authentication and no authorization. See
 * {@code docs/adr/0003-org-scoping-stub.md}.
 */
@Component
class SeededOrgPrincipalResolver implements PrincipalResolver {

    /** Must match V2__seed_organization.sql. A test asserts that it does. */
    static final UUID SEEDED_ORG_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");

    private static final Principal SEEDED =
            new Principal(SEEDED_ORG_ID, "local-operator", "Local operator (unauthenticated)");

    @Override
    public Principal resolve(HttpServletRequest request) {
        return SEEDED;
    }
}
