package com.ambitiousconcepts.opsatlas.shared;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Accepts a correlation id from {@code X-Correlation-Id} or generates one, puts
 * it in the MDC so every log line carries it, and echoes it on the response.
 *
 * <p>CLAUDE.md section 9. This runs first, before anything that might fail, so
 * that an error response can always quote an id the caller can search for.
 *
 * <p>The inbound value is constrained rather than trusted: it reaches log files
 * and response headers, so an unbounded client-supplied string would be a log
 * injection and header splitting vector. A value that does not match is replaced
 * rather than rejected - the request is not the caller's fault to fail over.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    private static final Pattern ACCEPTABLE = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = resolve(request.getHeader(HEADER));
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private static String resolve(String inbound) {
        return inbound != null && ACCEPTABLE.matcher(inbound).matches()
                ? inbound
                : UUID.randomUUID().toString();
    }

    /** The current request's correlation id, or "unknown" outside a request. */
    public static String current() {
        String value = MDC.get(MDC_KEY);
        return value != null ? value : "unknown";
    }
}
