package probe

import (
	"context"
	"net/http"
	"net/http/httptest"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func targets(base string, count int) []Target {
	list := make([]Target, 0, count)
	for i := 0; i < count; i++ {
		list = append(list, Target{
			ServiceSlug:   "service",
			EnvironmentID: "env",
			Environment:   "production",
			ProbeURL:      base,
		})
	}
	return list
}

func TestConcurrencyIsBounded(t *testing.T) {
	// Unbounded concurrency would let one pass open a socket per environment at
	// once - a stampede from a process whose whole job is to watch the fleet
	// without disturbing it.
	var inFlight, peak atomic.Int32
	var mu sync.Mutex

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		current := inFlight.Add(1)
		mu.Lock()
		if current > peak.Load() {
			peak.Store(current)
		}
		mu.Unlock()
		time.Sleep(30 * time.Millisecond)
		inFlight.Add(-1)
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	const limit = 3
	scheduler := NewScheduler(New(2*time.Second, 0), limit, 0)
	results := scheduler.Run(context.Background(), targets(server.URL, 12))

	if len(results) != 12 {
		t.Fatalf("got %d results, want 12", len(results))
	}
	if got := peak.Load(); got > limit {
		t.Fatalf("peak concurrency was %d, want at most %d", got, limit)
	}
}

func TestResultsComeBackInTargetOrder(t *testing.T) {
	// Ordered by target rather than by completion, so a pass produces the same
	// batch regardless of which probes happened to be slow - which is what
	// makes a failing batch reproducible.
	var slow atomic.Bool
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/slow" && slow.CompareAndSwap(false, true) {
			time.Sleep(60 * time.Millisecond)
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	scheduler := NewScheduler(New(2*time.Second, 0), 4, 0)
	results := scheduler.Run(context.Background(), []Target{
		{EnvironmentID: "first", ProbeURL: server.URL + "/slow"},
		{EnvironmentID: "second", ProbeURL: server.URL + "/fast"},
		{EnvironmentID: "third", ProbeURL: server.URL + "/fast"},
	})

	want := []string{"first", "second", "third"}
	if len(results) != len(want) {
		t.Fatalf("got %d results, want %d", len(results), len(want))
	}
	for i, expected := range want {
		if results[i].Target.EnvironmentID != expected {
			t.Fatalf("result %d is %q, want %q", i, results[i].Target.EnvironmentID, expected)
		}
	}
}

func TestCancellingAPassReportsOnlyWhatRan(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		time.Sleep(50 * time.Millisecond)
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancel()

	scheduler := NewScheduler(New(time.Second, 0), 2, 0)
	results := scheduler.Run(ctx, targets(server.URL, 20))

	// Fewer results than targets is correct. Reporting a zero-valued result for
	// a probe that never ran would invent a probe, and the counters downstream
	// cannot tell the difference.
	if len(results) > 20 {
		t.Fatalf("got %d results for 20 targets", len(results))
	}
	for _, result := range results {
		if result.Result.Outcome == "" {
			t.Fatal("a result with no outcome is a probe that never ran and must not be reported")
		}
	}
}

func TestNoTargetsIsNotAnError(t *testing.T) {
	scheduler := NewScheduler(New(time.Second, 0), 4, time.Minute)
	if results := scheduler.Run(context.Background(), nil); len(results) != 0 {
		t.Fatalf("got %d results for no targets", len(results))
	}
}
