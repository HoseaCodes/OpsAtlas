package com.ambitiousconcepts.opsatlas.shared;

import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request one identifier that works everywhere.
 *
 * <p>CLAUDE.md section 9 asks for a correlation id accepted or generated, put in
 * the MDC, logged on every line and echoed in every response - and separately
 * for W3C trace context to be propagated. Treating those as two problems
 * produces two identifiers for one request, and joining a log line to its trace
 * then means joining on timestamps.
 *
 * <p>So they are the same string. ADR 0011:
 *
 * <ul>
 *   <li>Caller supplied {@code X-Correlation-Id}: that value is echoed back and
 *       attached to the span, so a search by it finds this trace. A caller who
 *       sends an id and gets a different one back cannot correlate anything.
 *   <li>Nothing supplied, request traced: the correlation id <em>is</em> the
 *       32-character trace id - whether this service started the trace or joined
 *       one arriving on {@code traceparent}. This is the common case.
 *   <li>Neither: a generated value, as before.
 * </ul>
 *
 * <p>The formats already agreed, which is what made this possible rather than
 * merely desirable - a W3C trace id is 32 hex characters and satisfies the
 * pattern this filter has always enforced.
 *
 * <p>Ordering matters and is easy to get backwards. Spring Boot registers
 * {@code ServerHttpObservationFilter} at {@code HIGHEST_PRECEDENCE + 1}, and
 * <em>that</em> is what creates the span or joins an inbound {@code traceparent}.
 * This filter must therefore run after it - at {@code HIGHEST_PRECEDENCE}, there
 * is no current span to read and the correlation id silently falls back to a
 * generated UUID, which looks like it works and quietly undoes ADR 0011.
 *
 * <p>It still runs before anything that can fail, so an error response can
 * always quote an id the caller can search for, and before
 * {@code OrgContextFilter} at {@code +10} so organization-scoped logging
 * already has a correlation id to carry.
 */
@Component
@Order(CorrelationIdFilter.ORDER)
public class CorrelationIdFilter extends OncePerRequestFilter {

    /**
     * After Spring's observation filter ({@code HIGHEST_PRECEDENCE + 1}) and
     * before {@code OrgContextFilter} ({@code +10}).
     */
    static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 5;

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    /** Satisfied by a W3C trace id as well as by a UUID. */
    private static final Pattern ACCEPTABLE = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    /** W3C's "invalid" trace id, and what a no-op tracer reports. */
    private static final String INVALID_TRACE_ID = "0".repeat(32);

    /**
     * Optional on purpose. Tracing can be switched off - in tests, or in a
     * deployment with no collector - and the correlation id must keep working
     * when it is.
     */
    private final ObjectProvider<Tracer> tracer;

    CorrelationIdFilter(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }

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

    /**
     * A caller's own id wins; otherwise the trace id; otherwise generated.
     *
     * <p>Section 9 says the correlation id is "accepted from
     * {@code X-Correlation-Id}" and "echoed in every response", and a caller who
     * sends one and gets a different one back cannot correlate anything - which
     * is the entire purpose of sending it. So a supplied id is returned
     * unchanged and attached to the span, and a search by it finds this trace.
     *
     * <p>An earlier version of this preferred the trace id even over a supplied
     * one, on the reasoning that the logs and the trace should carry the same
     * string. Two existing tests caught it. The reasoning was right about the
     * common case and wrong about this one: when nobody supplies an id there is
     * only the trace id and it is used, and when somebody does supply one they
     * have asked for it by name.
     *
     * <p>An implausible inbound value is replaced rather than rejected: it
     * reaches log files and a response header, so an unbounded client string is
     * a log-injection and header-splitting vector - but the request is not the
     * caller's fault to fail over.
     */
    private String resolve(String inbound) {
        String supplied = inbound != null && ACCEPTABLE.matcher(inbound).matches() ? inbound : null;

        if (supplied != null) {
            tagSpanWithSuppliedId(supplied);
            return supplied;
        }

        String traceId = currentTraceId();
        return traceId != null ? traceId : UUID.randomUUID().toString();
    }

    /**
     * The current trace id, or null when there is not a real one.
     *
     * <p>The all-zero id is W3C's "invalid", and it is what a no-op tracer
     * reports. It satisfies the acceptable pattern, so without this check a
     * deployment with tracing switched off would give every request the same
     * correlation id - which looks like it works right up until two requests
     * are indistinguishable in the logs.
     */
    private String currentTraceId() {
        Tracer current = tracer.getIfAvailable();
        if (current == null) {
            return null;
        }
        var span = current.currentSpan();
        if (span == null) {
            return null;
        }
        String traceId = span.context().traceId();
        if (traceId == null || !ACCEPTABLE.matcher(traceId).matches() || INVALID_TRACE_ID.equals(traceId)) {
            return null;
        }
        return traceId;
    }

    private void tagSpanWithSuppliedId(String supplied) {
        Tracer current = tracer.getIfAvailable();
        if (current == null) {
            return;
        }
        var span = current.currentSpan();
        if (span != null) {
            // So a search by the caller's own id finds this trace.
            span.tag("opsatlas.correlation_id", supplied);
        }
    }

    /** The current request's correlation id, or "unknown" outside a request. */
    public static String current() {
        String value = MDC.get(MDC_KEY);
        return value != null ? value : "unknown";
    }
}
