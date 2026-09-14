package com.ambitiousconcepts.opsatlas.identity.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Lets a machine in on a key it was issued (ADR 0013).
 *
 * <p>Its own header rather than {@code Authorization: Bearer}, because that
 * header already means something here: Spring Security would hand the value to
 * the JWT decoder, which would fail to parse a key that was never a JWT and
 * answer 401 with a message about tokens. Two mechanisms sharing one header
 * makes every failure ambiguous.
 *
 * <p>A missing or unrecognised key authenticates nobody and does not fail the
 * request here - the authorization rules refuse it further down, in the one
 * place that decides. A filter that answered 401 itself would be a second
 * decision point that could disagree with the first.
 */
@Component
class ServiceCredentialFilter extends OncePerRequestFilter {

    private final ServiceCredentials credentials;

    ServiceCredentialFilter(ServiceCredentials credentials) {
        this.credentials = credentials;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = request.getHeader(ServiceCredentials.HEADER);
        if (presented != null && nobodyIsAuthenticatedYet()) {
            credentials
                    .authenticate(presented)
                    .ifPresent(principal -> SecurityContextHolder.getContext()
                            .setAuthentication(new ServiceCredentialAuthentication(principal)));
        }
        chain.doFilter(request, response);
    }

    /**
     * Anonymous counts as nobody.
     *
     * <p>Checking only for {@code null} looked equivalent and is not: Spring
     * Security installs an anonymous authentication for requests that presented
     * nothing, and if that filter ever runs before this one, a perfectly good key
     * would be ignored in favour of "anonymous". The symptom would be 403 for a
     * machine that is holding a valid credential.
     */
    private static boolean nobodyIsAuthenticatedYet() {
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        return current == null || current instanceof AnonymousAuthenticationToken || !current.isAuthenticated();
    }
}
