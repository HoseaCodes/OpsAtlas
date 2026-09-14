package com.ambitiousconcepts.opsatlas.identity.internal;

import com.ambitiousconcepts.opsatlas.identity.api.Principal;
import com.ambitiousconcepts.opsatlas.identity.api.PrincipalResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Resolves the caller from the verified token, and the organization from this
 * system's own records (ADR 0013).
 *
 * <p>This replaced {@code SeededOrgPrincipalResolver}, which returned the single
 * seeded organization for every request. ADR 0003 said replacing one
 * implementation of {@link PrincipalResolver} would be the whole migration to
 * real authentication, and that turned out to be true: no query, no table and no
 * caller of {@code CurrentPrincipal} changed.
 *
 * <p>The token is the authority on <em>who</em>, and never on <em>which
 * organization</em>. The issuer is a generic authentication service that knows
 * nothing about this catalog, so a claim naming an organization would be a claim
 * it is not in a position to make. The mapping lives in the {@code principal}
 * table, which means access is granted here and revoked here.
 */
@Component
class TokenPrincipalResolver implements PrincipalResolver {

    private final PrincipalRepository principals;

    TokenPrincipalResolver(PrincipalRepository principals) {
        this.principals = principals;
    }

    @Override
    public Principal resolve(HttpServletRequest request) {
        return lookup()
                .orElseThrow(() -> new IllegalStateException(
                        "No provisioned principal for the authenticated caller. This should have been "
                                + "refused before reaching here - see ProvisionedPrincipals."));
    }

    /**
     * The caller, when there is an authenticated and provisioned one.
     *
     * <p>Empty rather than throwing, because two callers ask this question for
     * different reasons: the authorization manager asks in order to refuse, and
     * the filter asks in order to bind. Neither wants an exception for the
     * ordinary case of an anonymous request to a public endpoint.
     */
    Optional<Principal> lookup() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken authenticated)) {
            return Optional.empty();
        }

        Jwt token = authenticated.getToken();
        String issuer = token.getIssuer() == null ? null : token.getIssuer().toString();
        String subject = token.getClaimAsString("id");
        if (issuer == null || subject == null) {
            // A token with no issuer cannot be attributed to a provider, and one
            // with no subject cannot be attributed to a person. Either way there
            // is nothing to look up, and guessing would be the whole problem.
            return Optional.empty();
        }

        return principals
                .findByIssuerAndSubject(issuer, subject)
                .map(found -> new Principal(found.getOrgId(), found.getSubject(), found.getDisplayName()));
    }
}
