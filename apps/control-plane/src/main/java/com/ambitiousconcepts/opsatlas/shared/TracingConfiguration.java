package com.ambitiousconcepts.opsatlas.shared;

import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.TextMapPropagator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * W3C trace context, declared explicitly.
 *
 * <p>CLAUDE.md section 9 names {@code traceparent} specifically, and ADR 0011
 * makes the correlation id the trace id - so the propagation format is not a
 * detail this can afford to inherit from a default.
 *
 * <p>It is declared here because inheriting it did not work. With
 * {@code management.tracing.propagation.type=w3c} set, Spring Boot still wired
 * {@code NoopTextMapPropagator}: every inbound {@code traceparent} was ignored
 * and every request started a fresh trace. Nothing failed - the correlation id
 * was still a valid-looking 32-character trace id, just a different one each
 * hop, which is the worst kind of broken because it looks like it works.
 * {@code TraceCorrelationIT} asserts the join rather than the configuration, so
 * it catches this whichever way Boot's conditionals move.
 *
 * <p>Baggage is included so a value set on one side of a call is readable on the
 * other. Nothing sets any today; it costs one header and removes a surprise
 * later.
 *
 * <p>Unconditional on purpose. Gating it on {@code management.tracing.enabled}
 * added a condition whose evaluation had to be reasoned about to explain why the
 * bean was missing; the bean itself is inert when nothing traces, so the
 * condition bought nothing and cost an hour.
 */
@Configuration
public class TracingConfiguration {

    /**
     * A {@code TextMapPropagator}, not a {@code ContextPropagators}.
     *
     * <p>That is the seam: Boot composes its {@code otelContextPropagators} from
     * whatever {@code TextMapPropagator} beans exist, and falls back to
     * {@code TextMapPropagator.noop()} only when none does. Supplying the
     * composite one layer up left the noop in place and was silently ignored.
     */
    @Bean
    TextMapPropagator w3cTextMapPropagator() {
        return TextMapPropagator.composite(
                W3CTraceContextPropagator.getInstance(), W3CBaggagePropagator.getInstance());
    }
}
