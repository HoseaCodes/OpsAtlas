package com.ambitiousconcepts.opsatlas.identity.internal;

import com.ambitiousconcepts.opsatlas.identity.api.Principal;
import com.ambitiousconcepts.opsatlas.identity.api.PrincipalResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the caller once per request and binds the organization for the
 * duration of it.
 *
 * <p>Ordered immediately after {@code CorrelationIdFilter} so that anything
 * logged here already carries a correlation id.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class OrgContextFilter extends OncePerRequestFilter {

    static final String MDC_KEY = "orgId";

    private final PrincipalResolver principalResolver;

    OrgContextFilter(PrincipalResolver principalResolver) {
        this.principalResolver = principalResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Principal principal = principalResolver.resolve(request);
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
