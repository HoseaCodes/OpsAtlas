package probe

import (
	"context"
	"math/rand"
	"sync"
	"time"
)

// Target is what a Scheduler probes. It mirrors catalog.Target without
// importing it, so probing does not depend on where targets came from.
type Target struct {
	ServiceSlug   string
	EnvironmentID string
	Environment   string
	ProbeURL      string
}

// Observed pairs a target with what probing it found.
type Observed struct {
	Target Target
	Result Result
}

// Scheduler runs a pass over every target with bounded concurrency.
type Scheduler struct {
	prober      *Prober
	concurrency int

	// jitter spreads probe starts across a window. Without it every
	// environment is hit at the same instant each pass: a stampede from a
	// process whose whole job is to watch the fleet without disturbing it, and
	// one that also makes every latency sample coincide with every other
	// observer's.
	jitter time.Duration

	random *rand.Rand
	mu     sync.Mutex
}

// NewScheduler returns a Scheduler. Jitter is capped at a fraction of the
// interval so a pass still finishes comfortably before the next is due.
func NewScheduler(prober *Prober, concurrency int, interval time.Duration) *Scheduler {
	return &Scheduler{
		prober:      prober,
		concurrency: concurrency,
		jitter:      interval / 4,
		random:      rand.New(rand.NewSource(time.Now().UnixNano())),
	}
}

// Run probes every target, at most `concurrency` at a time.
//
// Results come back in target order rather than completion order, so a pass
// produces the same batch regardless of which probes happened to be slow. That
// makes a failing batch reproducible, which matters when the reason it failed
// is in the payload.
func (s *Scheduler) Run(ctx context.Context, targets []Target) []Observed {
	if len(targets) == 0 {
		return nil
	}

	observed := make([]Observed, len(targets))
	semaphore := make(chan struct{}, s.concurrency)
	var wait sync.WaitGroup

	for index, target := range targets {
		select {
		case <-ctx.Done():
			// Shutting down. Return what completed rather than starting work
			// that will be cancelled half-done.
			wait.Wait()
			return collect(observed)
		case semaphore <- struct{}{}:
		}

		wait.Add(1)
		go func(index int, target Target) {
			defer wait.Done()
			defer func() { <-semaphore }()

			if delay := s.nextJitter(); delay > 0 {
				select {
				case <-ctx.Done():
					return
				case <-time.After(delay):
				}
			}

			observed[index] = Observed{Target: target, Result: s.prober.Probe(ctx, target.ProbeURL)}
		}(index, target)
	}

	wait.Wait()
	return collect(observed)
}

// nextJitter is guarded because math/rand's Rand is not safe for concurrent
// use, and every worker asks for one.
func (s *Scheduler) nextJitter() time.Duration {
	if s.jitter <= 0 {
		return 0
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	return time.Duration(s.random.Int63n(int64(s.jitter)))
}

// collect drops slots whose probe never ran, which happens when a pass is
// cancelled mid-flight. Reporting a zero-valued result would invent a probe
// that did not happen, and the counters downstream cannot tell the difference.
func collect(observed []Observed) []Observed {
	results := make([]Observed, 0, len(observed))
	for _, entry := range observed {
		if entry.Result.Outcome != "" {
			results = append(results, entry)
		}
	}
	return results
}
