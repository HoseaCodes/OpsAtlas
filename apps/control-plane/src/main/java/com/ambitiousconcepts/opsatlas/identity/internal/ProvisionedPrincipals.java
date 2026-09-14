package com.ambitiousconcepts.opsatlas.identity.internal;

import java.util.function.Supplier;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

/**
 * A verified token is not a membership (ADR 0013).
 *
 * <p>Storm-Gate will mint a token for anyone it has an account for, and its
 * accounts are not this catalog's users. Treating "the signature checks out" as
 * "may read the fleet" would make every OpsAtlas deployment readable by every
 * account on a shared identity provider.
 *
 * <p>So authorization is a second question, asked here: is this issuer and
 * subject provisioned? Answered against the {@code principal} table, which is
 * where access is granted and revoked.
 *
 * <p>The refusal is 403 rather than 401, and the difference is not cosmetic:
 * 401 invites the caller to try again with a credential, and they have one -
 * it is simply not one this system recognises. Retrying would not help.
 */
@Component
class ProvisionedPrincipals implements AuthorizationManager<RequestAuthorizationContext> {

    private final TokenPrincipalResolver resolver;

    ProvisionedPrincipals(TokenPrincipalResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public AuthorizationDecision check(Supplier<Authentication> authentication, RequestAuthorizationContext context) {
        return new AuthorizationDecision(resolver.lookup().isPresent());
    }
}
