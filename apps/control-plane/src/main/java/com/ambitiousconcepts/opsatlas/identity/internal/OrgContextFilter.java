package com.ambitiousconcepts.opsatlas.identity.internal;

import com.ambitiousconcepts.opsatlas.identity.api.Principal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the caller once per request and binds the organization for the
 * duration of it.
 *
 * <p>Ordered <strong>after Spring Security</strong>, which is a change from
 * where this used to sit. It previously ran at {@code HIGHEST_PRECEDENCE + 10},
 * ahead of everything - fine while the principal was a constant, and wrong the
 * moment it came from a verified token: the security filter chain had not run
 * yet, so there was no authentication to read. A correlation id is still in the
 * MDC by now, because {@code CorrelationIdFilter} stays in front of both.
 *
 * <p>An unauthenticated request binds nothing rather than failing. Some
 * endpoints are deliberately open - liveness, the generated contract - and they
 * have no organization to scope to. Anything that needs one and does not have
 * one fails loudly through {@code CurrentPrincipal}, which is exactly the guard
 * CLAUDE.md section 2 says must stay able to catch a request that lost its
 * principal.
 */
@Component
@Order(SecurityProperties.DEFAULT_FILTER_ORDER + 10)
class OrgContextFilter extends OncePerRequestFilter {

    static final String MDC_KEY = "orgId";

    private final TokenPrincipalResolver resolver;

    OrgContextFilter(TokenPrincipalResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<Principal> resolved = resolver.lookup();
        if (resolved.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }

        Principal principal = resolved.get();
        RequestScopedPrincipal.bind(principal);
        MDC.put(MDC_KEY, principal.orgId().toString());
        try {
            chain.doFilter(request, response);
        } finally {
            // Both of these must happen even when the chain throws. A principal
            // left on a pooled thread is inherited by the next request.
            MDC.remove(MDC_KEY);
            RequestScopedPrincipal.clear();
        }
    }
}
