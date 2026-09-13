package tracing_test

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/ambitious-concepts/opsatlas/observer/internal/probe"
	"github.com/ambitious-concepts/opsatlas/observer/internal/tracing"
	"go.opentelemetry.io/otel"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
)

// What is worth testing here is not that OpenTelemetry works. It is the two
// decisions this package makes on top of it, both of which would fail silently:
// outbound calls to the control plane must carry W3C context, and probes to
// somebody else's service must not.

// recording installs a real sampling provider with no exporter, so spans have
// genuine trace ids without anything being shipped anywhere. Without it every
// span is non-recording, its context is invalid, and a propagator injects
// nothing - which would make the assertions below pass for the wrong reason.
func recording(t *testing.T) {
	t.Helper()
	previous := otel.GetTracerProvider()
	otel.SetTracerProvider(sdktrace.NewTracerProvider(sdktrace.WithSampler(sdktrace.AlwaysSample())))
	t.Cleanup(func() { otel.SetTracerProvider(previous) })
}

func TestDisabledTracingStillInstallsTheW3CPropagator(t *testing.T) {
	t.Setenv("OPSATLAS_TRACING_ENABLED", "false")

	shutdown, err := tracing.Setup(context.Background(), "opsatlas-observer", "test")
	if err != nil {
		t.Fatalf("setup with tracing off must not fail: %v", err)
	}
	if shutdown == nil {
		t.Fatal("a shutdown func is always returned, so a caller never has to nil-check it")
	}
	if err := shutdown(context.Background()); err != nil {
		t.Fatalf("shutdown with tracing off: %v", err)
	}

	var carries bool
	for _, field := range otel.GetTextMapPropagator().Fields() {
		if field == "traceparent" {
			carries = true
		}
	}
	if !carries {
		// A traceparent that arrives must still be passed on, even when this
		// process is not recording anything itself.
		t.Fatalf("propagator fields = %v, want one of them to be traceparent",
			otel.GetTextMapPropagator().Fields())
	}
}

func TestControlPlaneCallsCarryTraceparent(t *testing.T) {
	recording(t)
	t.Setenv("OPSATLAS_TRACING_ENABLED", "false") // installs the propagator, exports nothing
	if _, err := tracing.Setup(context.Background(), "opsatlas-observer", "test"); err != nil {
		t.Fatalf("setup: %v", err)
	}

	var received string
	server := httptest.NewServer(http.HandlerFunc(func(_ http.ResponseWriter, r *http.Request) {
		received = r.Header.Get("traceparent")
	}))
	defer server.Close()

	ctx, span := tracing.Tracer().Start(context.Background(), "test.pass")
	defer span.End()

	client := &http.Client{Transport: tracing.Transport(nil), Timeout: 5 * time.Second}
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, server.URL, nil)
	if err != nil {
		t.Fatalf("building the request: %v", err)
	}
	response, err := client.Do(request)
	if err != nil {
		t.Fatalf("calling the server: %v", err)
	}
	defer response.Body.Close()

	// This header is the entire mechanism by which a probe and the registration
	// it causes end up in one trace instead of three unconnected ones (ADR 0011).
	if received == "" {
		t.Fatal("an outbound call to the control plane carried no traceparent")
	}
	wanted := span.SpanContext().TraceID().String()
	if len(received) < 35 || received[3:35] != wanted {
		t.Fatalf("traceparent = %q, want it to carry trace id %s", received, wanted)
	}
}

func TestProbesDoNotCarryOurTraceContext(t *testing.T) {
	recording(t)
	t.Setenv("OPSATLAS_TRACING_ENABLED", "false")
	if _, err := tracing.Setup(context.Background(), "opsatlas-observer", "test"); err != nil {
		t.Fatalf("setup: %v", err)
	}

	var headers http.Header
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		headers = r.Header.Clone()
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	ctx, span := tracing.Tracer().Start(context.Background(), "test.pass")
	defer span.End()

	if result := probe.New(5*time.Second, 0).Probe(ctx, server.URL); result.Outcome != probe.Healthy {
		t.Fatalf("outcome = %s, want HEALTHY; the server answered 200", result.Outcome)
	}

	// A probe reaches a system we do not own. Injecting our identifiers into
	// somebody else's request headers - and so into their logs - is not ours to
	// decide, and the span above is recording, so this would be carried if the
	// prober used the traced transport.
	for _, header := range []string{"traceparent", "tracestate", "baggage"} {
		if value := headers.Get(header); value != "" {
			t.Fatalf("a probe sent %s: %q - probes must not carry our trace context", header, value)
		}
	}
}
