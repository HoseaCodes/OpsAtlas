package report

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"regexp"
	"sync/atomic"
	"testing"
	"time"
)

func observation() Observation {
	return Observation{
		EnvironmentID: "env-1",
		ObservedAt:    time.Now().UTC().Format(time.RFC3339Nano),
		Outcome:       "HEALTHY",
	}
}

func TestRetriesCarryTheSameIdempotencyKey(t *testing.T) {
	// This is the property that makes retrying safe at all. The control plane
	// stores counters, so a batch applied twice inflates them with nothing
	// visibly wrong to notice.
	var keys []string
	var calls atomic.Int32

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		var payload struct {
			IdempotencyKey string `json:"idempotencyKey"`
		}
		_ = json.Unmarshal(body, &payload)
		keys = append(keys, payload.IdempotencyKey)

		if calls.Add(1) < 3 {
			w.WriteHeader(http.StatusBadGateway)
			return
		}
		w.WriteHeader(http.StatusAccepted)
		_, _ = w.Write([]byte(`{"applied":1,"ignored":0,"replayed":false}`))
	}))
	defer server.Close()

	passStart := time.Now()
	result, err := New(server.URL, "observer-1", 5*time.Second).
		Send(context.Background(), passStart, []Observation{observation()})
	if err != nil {
		t.Fatalf("Send: %v", err)
	}
	if result.Applied != 1 {
		t.Fatalf("applied = %d, want 1", result.Applied)
	}
	if len(keys) != 3 {
		t.Fatalf("expected 3 attempts, got %d", len(keys))
	}
	for _, key := range keys[1:] {
		if key != keys[0] {
			t.Fatalf("a retry carried a different key: %q then %q", keys[0], key)
		}
	}
}

func TestKeyMatchesWhatTheControlPlaneAccepts(t *testing.T) {
	// The control plane constrains the key shape. A key it rejects would make
	// every single report fail, permanently and identically.
	shape := regexp.MustCompile(`^[A-Za-z0-9._:-]{8,200}$`)
	reporter := New("http://localhost:8080", "observer-1", time.Second)

	key := reporter.key(time.Now())
	if !shape.MatchString(key) {
		t.Fatalf("key %q does not match what the control plane accepts", key)
	}
}

func TestDistinctPassesGetDistinctKeys(t *testing.T) {
	reporter := New("http://localhost:8080", "observer-1", time.Second)

	first := reporter.key(time.Unix(0, 1_000_000_000))
	second := reporter.key(time.Unix(0, 1_000_000_001))
	if first == second {
		t.Fatal("two passes sharing a key would make the second a silent no-op")
	}
}

func TestRejectedPayloadsAreNotRetried(t *testing.T) {
	// A 422 will be a 422 again: the payload is the problem, not the transport.
	// Retrying is pure waste at exactly the moment the observer should move on.
	var calls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		calls.Add(1)
		w.WriteHeader(http.StatusUnprocessableEntity)
		_, _ = w.Write([]byte(`{"detail":"no"}`))
	}))
	defer server.Close()

	_, err := New(server.URL, "observer-1", 5*time.Second).
		Send(context.Background(), time.Now(), []Observation{observation()})
	if err == nil {
		t.Fatal("a rejected batch must be reported as an error")
	}
	if got := calls.Load(); got != 1 {
		t.Fatalf("server was called %d times, want exactly 1", got)
	}
}

func TestRateLimitingIsRetried(t *testing.T) {
	// 429 is the one 4xx that means "later", not "never".
	var calls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		if calls.Add(1) == 1 {
			w.WriteHeader(http.StatusTooManyRequests)
			return
		}
		w.WriteHeader(http.StatusAccepted)
		_, _ = w.Write([]byte(`{"applied":1,"ignored":0,"replayed":false}`))
	}))
	defer server.Close()

	if _, err := New(server.URL, "observer-1", 5*time.Second).
		Send(context.Background(), time.Now(), []Observation{observation()}); err != nil {
		t.Fatalf("Send: %v", err)
	}
	if got := calls.Load(); got != 2 {
		t.Fatalf("server was called %d times, want 2", got)
	}
}

func TestEmptyBatchIsNotSent(t *testing.T) {
	var calls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		calls.Add(1)
		w.WriteHeader(http.StatusAccepted)
	}))
	defer server.Close()

	if _, err := New(server.URL, "observer-1", time.Second).
		Send(context.Background(), time.Now(), nil); err != nil {
		t.Fatalf("an empty pass is not an error, got %v", err)
	}
	if calls.Load() != 0 {
		t.Fatal("an observer with nothing to report should not report")
	}
}

func TestShutdownStopsRetrying(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusBadGateway)
	}))
	defer server.Close()

	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	if _, err := New(server.URL, "observer-1", time.Second).
		Send(ctx, time.Now(), []Observation{observation()}); err == nil {
		t.Fatal("a cancelled context must stop the retry loop")
	}
}
