package probe

import (
	"context"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"
)

// A real HTTP server rather than a stubbed transport: what is being tested here
// is how the probe classifies real network behaviour - a slow response, a
// refused connection, a redirect - and a stub would only prove the code calls
// the methods it calls.

func TestHealthyResponseIsClassifiedHealthy(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	result := New(2*time.Second, 0).Probe(context.Background(), server.URL)

	if result.Outcome != Healthy {
		t.Fatalf("outcome = %s, want HEALTHY", result.Outcome)
	}
	if result.ResponseMs == nil {
		t.Fatal("a probe that got a response must report how long it took")
	}
	if result.StatusCode == nil || *result.StatusCode != 200 {
		t.Fatalf("statusCode = %v, want 200", result.StatusCode)
	}
	if result.Detail != "" {
		t.Fatalf("a healthy probe needs no explanation, got %q", result.Detail)
	}
}

func TestNonSuccessStatusIsUnhealthyNotUnreachable(t *testing.T) {
	// A 503 from a readiness endpoint is the single most common real answer:
	// the service is up and saying it is not ready. Reporting that as
	// unreachable would send an on-call engineer looking for a dead host.
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusServiceUnavailable)
	}))
	defer server.Close()

	result := New(2*time.Second, 0).Probe(context.Background(), server.URL)

	if result.Outcome != Unhealthy {
		t.Fatalf("outcome = %s, want UNHEALTHY", result.Outcome)
	}
	if result.StatusCode == nil || *result.StatusCode != 503 {
		t.Fatalf("statusCode = %v, want 503", result.StatusCode)
	}
	if result.ResponseMs == nil {
		t.Fatal("it answered, so how fast it said no is still a measurement")
	}
}

func TestSlowResponseIsTimeoutWithNoResponseTime(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		select {
		case <-time.After(2 * time.Second):
		case <-r.Context().Done():
		}
	}))
	defer server.Close()

	result := New(100*time.Millisecond, 0).Probe(context.Background(), server.URL)

	if result.Outcome != Timeout {
		t.Fatalf("outcome = %s, want TIMEOUT", result.Outcome)
	}
	// A timeout has no round trip to report. Sending the timeout value instead
	// would put the timeout into the control plane's latency average, silently
	// raising every mean the moment a service went down.
	if result.ResponseMs != nil {
		t.Fatalf("a timeout must report no response time, got %d", *result.ResponseMs)
	}
	if result.StatusCode != nil {
		t.Fatal("nothing answered, so there is no status code")
	}
}

func TestRefusedConnectionIsUnreachable(t *testing.T) {
	// Start and immediately stop, so the port is closed but was real.
	server := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	url := server.URL
	server.Close()

	result := New(time.Second, 0).Probe(context.Background(), url)

	if result.Outcome != Unreachable {
		t.Fatalf("outcome = %s, want UNREACHABLE", result.Outcome)
	}
	if result.Detail == "" {
		t.Fatal("an unreachable probe must say what happened")
	}
}

func TestRedirectsAreNotFollowed(t *testing.T) {
	// A health endpoint that redirects is not answering the question, and
	// following one could take the probe to a host the manifest never named.
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		http.Redirect(w, &http.Request{}, "https://elsewhere.example.com/", http.StatusFound)
	}))
	defer server.Close()

	result := New(2*time.Second, 0).Probe(context.Background(), server.URL)

	if result.Outcome != Unhealthy {
		t.Fatalf("outcome = %s, want UNHEALTHY for a redirect", result.Outcome)
	}
	if result.StatusCode == nil || *result.StatusCode != 302 {
		t.Fatalf("statusCode = %v, want the redirect itself", result.StatusCode)
	}
}

func TestStatusCodesAreNotRetried(t *testing.T) {
	// A status code is a definitive answer. Retrying it does not change it and
	// would double the load on a service that is already struggling.
	var calls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		calls.Add(1)
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer server.Close()

	result := New(2*time.Second, 3).Probe(context.Background(), server.URL)

	if got := calls.Load(); got != 1 {
		t.Fatalf("server was called %d times, want exactly 1", got)
	}
	if result.Attempts != 1 {
		t.Fatalf("attempts = %d, want 1", result.Attempts)
	}
}

func TestTransientFailuresAreRetried(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	url := server.URL
	server.Close()

	// A connection-level failure may be a dropped packet, so it gets another go.
	result := New(200*time.Millisecond, 2).Probe(context.Background(), url)

	if result.Attempts != 3 {
		t.Fatalf("attempts = %d, want 3 (the first plus two retries)", result.Attempts)
	}
}

func TestCancelledContextStopsRetrying(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	url := server.URL
	server.Close()

	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	// Shutting down must not turn into a burst of retries, and must not report
	// a failure the observer caused by cancelling.
	result := New(time.Second, 5).Probe(ctx, url)

	if result.Attempts != 1 {
		t.Fatalf("attempts = %d, want 1 when the context is already cancelled", result.Attempts)
	}
}
