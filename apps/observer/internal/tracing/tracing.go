// Package tracing wires OpenTelemetry for the observer.
//
// The point is not that the observer traces itself - it is that a probe, the
// report it produced and the state change that followed become one trace across
// two processes. Without W3C context on the outbound calls, those are three
// unconnected stories about the same event. See ADR 0011.
package tracing

import (
	"context"
	"fmt"
	"net/http"
	"os"
	"strings"
	"time"

	"go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracehttp"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.26.0"
	"go.opentelemetry.io/otel/trace"
)

// Shutdown flushes anything buffered. Always returned, even when tracing is
// disabled, so a caller never has to nil-check it.
type Shutdown func(context.Context) error

// Setup configures the global tracer provider.
//
// Tracing is opt-out rather than opt-in, but a collector that is not there must
// not cost anything: the exporter batches, and a failed export is dropped. An
// observer that cannot ship traces is still probing, and probing is the job.
func Setup(ctx context.Context, serviceName, observerID string) (Shutdown, error) {
	if strings.EqualFold(os.Getenv("OPSATLAS_TRACING_ENABLED"), "false") {
		// Still install the propagator. Even untraced, the observer should send
		// a traceparent it received - and installing it here means there is one
		// place that decides the format.
		otel.SetTextMapPropagator(w3c())
		return func(context.Context) error { return nil }, nil
	}

	endpoint := os.Getenv("OPSATLAS_OTLP_ENDPOINT")
	if endpoint == "" {
		endpoint = "http://localhost:4318"
	}

	exporter, err := otlptracehttp.New(ctx,
		otlptracehttp.WithEndpointURL(strings.TrimRight(endpoint, "/")+"/v1/traces"),
		// Short, and it does not block a probe: export happens on a batch
		// processor's own goroutine.
		otlptracehttp.WithTimeout(5*time.Second),
	)
	if err != nil {
		return nil, fmt.Errorf("configuring the trace exporter: %w", err)
	}

	attributes, err := resource.Merge(
		resource.Default(),
		resource.NewWithAttributes(
			semconv.SchemaURL,
			semconv.ServiceName(serviceName),
			// Which observer produced this. With more than one watching the
			// same fleet, this is the only thing that separates their spans.
			attribute.String("opsatlas.observer_id", observerID),
			attribute.String("deployment.environment", environment()),
		),
	)
	if err != nil {
		return nil, fmt.Errorf("building the resource: %w", err)
	}

	provider := sdktrace.NewTracerProvider(
		sdktrace.WithBatcher(exporter, sdktrace.WithBatchTimeout(2*time.Second)),
		sdktrace.WithResource(attributes),
		// Everything. Matches the control plane, and for the same reason: at a
		// probe every thirty seconds, sampling would mean the trace someone
		// goes looking for is the one that was dropped (ADR 0011).
		sdktrace.WithSampler(sdktrace.AlwaysSample()),
	)

	otel.SetTracerProvider(provider)
	otel.SetTextMapPropagator(w3c())

	return provider.Shutdown, nil
}

// w3c is the propagation format CLAUDE.md section 9 names.
//
// Stated explicitly rather than left to a default: the control plane also names
// it explicitly, and a silent switch to B3 on either side would break the join
// between the two without breaking a build.
func w3c() propagation.TextMapPropagator {
	return propagation.NewCompositeTextMapPropagator(
		propagation.TraceContext{},
		propagation.Baggage{},
	)
}

func environment() string {
	if value := strings.TrimSpace(os.Getenv("OPSATLAS_DEPLOYMENT_ENVIRONMENT")); value != "" {
		return value
	}
	return "local"
}

// Tracer returns the observer's tracer.
func Tracer() trace.Tracer {
	return otel.Tracer("github.com/ambitious-concepts/opsatlas/observer")
}

// Transport wraps an HTTP transport so outbound calls carry traceparent.
//
// This is the whole mechanism by which a probe and the registration it causes
// end up in one trace. Applied to the catalog and reporter clients, not to the
// prober: a probe reaches somebody else's service, and injecting our trace
// context into a request to a system we do not own would put our identifiers in
// their logs uninvited.
func Transport(base http.RoundTripper) http.RoundTripper {
	if base == nil {
		base = http.DefaultTransport
	}
	return otelhttp.NewTransport(base)
}
