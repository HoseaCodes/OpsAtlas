package com.ambitiousconcepts.opsatlas.identity.internal;

import com.ambitiousconcepts.opsatlas.identity.api.Principal;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/**
 * An authenticated machine.
 *
 * <p>A separate type from {@code JwtAuthenticationToken} on purpose: a request
 * from the observer and a request from a person are different things, and
 * flattening them into one would make "who did this" unanswerable at exactly
 * the moment it is asked.
 */
class ServiceCredentialAuthentication extends AbstractAuthenticationToken {

    private final transient Principal principal;

    ServiceCredentialAuthentication(Principal principal) {
        super(List.of());
        this.principal = principal;
        setAuthenticated(true);
    }

    Principal principal() {
        return principal;
    }

    @Override
    public Object getCredentials() {
        // The key is not kept after it has been checked. Nothing downstream has
        // a use for it, and anything holding it could log it.
        return null;
    }

    @Override
    public Object getPrincipal() {
        return principal.subject();
    }

    @Override
    public String getName() {
        return principal.subject();
    }
}
