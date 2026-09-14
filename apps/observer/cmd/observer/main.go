// Command observer probes the environments the OpsAtlas catalog declares and
// reports what it finds back to the control plane.
//
// It holds no credentials for the services it probes, makes only GET requests
// to the health endpoints those services declared, and writes nothing anywhere
// except the control plane's observation endpoint.
//
// What it deliberately does not do: detect desired-versus-observed drift. That
// needs a notion of a deployment - a declared version to compare a running one
// against - and no such thing exists yet. Claiming drift detection on the
// strength of "the probe failed" would be calling an outage a drift.
package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"

	"github.com/ambitious-concepts/opsatlas/observer/internal/catalog"
	"github.com/ambitious-concepts/opsatlas/observer/internal/config"
	"github.com/ambitious-concepts/opsatlas/observer/internal/metrics"
	"github.com/ambitious-concepts/opsatlas/observer/internal/probe"
	"github.com/ambitious-concepts/opsatlas/observer/internal/report"
	"github.com/ambitious-concepts/opsatlas/observer/internal/tracing"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/trace"
)

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo}))
	slog.SetDefault(logger)

	cfg, err := config.Load()
	if err != nil {
		logger.Error("configuration is not usable", "error", err)
		os.Exit(1)
	}

	logger.Info("observer starting",
		"observerId", cfg.ObserverID,
		"controlPlane", cfg.APIURL,
		"probeInterval", cfg.ProbeInterval.String(),
		"probeTimeout", cfg.ProbeTimeout.String(),
		"concurrency", cfg.ProbeConcurrency,
		"metricsAddr", cfg.MetricsAddr,
		// Whether, never what.
		"authenticated", cfg.APIKey != "",
	)

	if code := run(cfg, logger); code != 0 {
		os.Exit(code)
	}
	logger.Info("observer stopped")
}

func run(cfg config.Config, logger *slog.Logger) int {
	// SIGINT and SIGTERM cancel the root context. Everything below takes a
	// context, so a signal unwinds the whole thing rather than killing it.
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	shutdownTracing, err := tracing.Setup(ctx, "opsatlas-observer", cfg.ObserverID)
	if err != nil {
		// Not fatal. An observer that cannot export traces is still probing,
		// and probing is the job; refusing to start would trade something
		// useful for something merely visible.
		logger.Warn("tracing is not configured; continuing without it", "error", err)
		shutdownTracing = func(context.Context) error { return nil }
	}

	instruments := metrics.New()
	catalogClient := catalog.New(cfg.APIURL, cfg.APIKey, 30*time.Second)
	prober := probe.New(cfg.ProbeTimeout, cfg.ProbeRetries)
	scheduler := probe.NewScheduler(prober, cfg.ProbeConcurrency, cfg.ProbeInterval)
	reporter := report.New(cfg.APIURL, cfg.ObserverID, cfg.APIKey, 30*time.Second)

	server := &http.Server{
		Addr:              cfg.MetricsAddr,
		Handler:           instruments.Handler(),
		ReadHeaderTimeout: 5 * time.Second,
	}

	var wait sync.WaitGroup
	wait.Add(1)
	go func() {
		defer wait.Done()
		if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			// Not fatal. An observer that cannot serve metrics is still
			// observing, and killing it would stop something useful to restore
			// something merely visible.
			logger.Error("metrics endpoint stopped", "error", err)
		}
	}()

	targets := newTargetCache(catalogClient, instruments, logger)
	targets.refresh(ctx)

	catalogTicker := time.NewTicker(cfg.CatalogRefresh)
	defer catalogTicker.Stop()
	probeTicker := time.NewTicker(cfg.ProbeInterval)
	defer probeTicker.Stop()

	// A pass immediately, so starting the observer produces data rather than a
	// silent interval of nothing.
	pass(ctx, scheduler, reporter, instruments, targets.get(), logger)

loop:
	for {
		select {
		case <-ctx.Done():
			break loop
		case <-catalogTicker.C:
			targets.refresh(ctx)
		case <-probeTicker.C:
			pass(ctx, scheduler, reporter, instruments, targets.get(), logger)
		}
	}

	logger.Info("shutting down")

	// Graceful: stop serving metrics, but give in-flight work a moment. The
	// context is already cancelled, so probes are unwinding; this is about not
	// dropping the connection mid-response for a scrape that is already in
	// progress.
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := server.Shutdown(shutdownCtx); err != nil {
		logger.Warn("metrics endpoint did not shut down cleanly", "error", err)
	}
	// Flush buffered spans before exiting, so the pass that was in flight when
	// the signal arrived is still explicable afterwards.
	if err := shutdownTracing(shutdownCtx); err != nil {
		logger.Warn("tracing did not flush cleanly", "error", err)
	}

	wait.Wait()
	return 0
}

// pass probes every target once and reports the results.
func pass(
	ctx context.Context,
	scheduler *probe.Scheduler,
	reporter *report.Reporter,
	instruments *metrics.Metrics,
	targets []probe.Target,
	logger *slog.Logger,
) {
	if len(targets) == 0 {
		return
	}

	started := time.Now()

	// One span per pass. Everything below - the probes, reading the catalog,
	// reporting the batch - hangs off it, so the control plane's registration
	// of a state change is a child of the probe that caused it (ADR 0011).
	ctx, span := tracing.Tracer().Start(ctx, "observer.pass",
		trace.WithAttributes(attribute.Int("opsatlas.targets", len(targets))))
	defer span.End()

	observed := scheduler.Run(ctx, targets)
	if len(observed) == 0 {
		span.SetStatus(codes.Error, "no probes completed")
		return
	}

	observations := make([]report.Observation, 0, len(observed))
	unhealthy := 0
	for _, entry := range observed {
		outcome := string(entry.Result.Outcome)
		if entry.Result.Outcome != "HEALTHY" {
			unhealthy++
		}
		instruments.ProbesTotal.WithLabelValues(outcome).Inc()
		instruments.ProbeAttempts.Observe(float64(entry.Result.Attempts))
		if entry.Result.ResponseMs != nil {
			instruments.ProbeDuration.WithLabelValues(outcome).
				Observe(float64(*entry.Result.ResponseMs) / 1000)
		}

		var detail *string
		if entry.Result.Detail != "" {
			value := entry.Result.Detail
			detail = &value
		}

		observations = append(observations, report.Observation{
			EnvironmentID: entry.Target.EnvironmentID,
			ObservedAt:    entry.Result.ObservedAt.UTC().Format(time.RFC3339Nano),
			Outcome:       outcome,
			ResponseMs:    entry.Result.ResponseMs,
			StatusCode:    entry.Result.StatusCode,
			Detail:        detail,
		})
	}

	// The pass start is the idempotency key's basis, so a retry of this exact
	// pass carries the same key and cannot be double-counted.
	result, err := reporter.Send(ctx, started, observations)
	if err != nil {
		if ctx.Err() != nil {
			// Shutting down mid-report is not a failure worth alarming about.
			logger.Info("report abandoned during shutdown", "observations", len(observations))
			return
		}
		instruments.ReportsTotal.WithLabelValues("failed").Inc()
		span.RecordError(err)
		span.SetStatus(codes.Error, "report rejected")
		// Probing continues. The next pass carries fresh results, and
		// last_successful_report_timestamp_seconds is what says how long this
		// has been going on.
		logger.Error("could not report observations", "error", err, "observations", len(observations))
		return
	}

	instruments.ReportsTotal.WithLabelValues("accepted").Inc()
	instruments.ObservationsApplied.Add(float64(result.Applied))
	instruments.ObservationsIgnored.Add(float64(result.Ignored))
	instruments.MarkReported(time.Now())
	span.SetAttributes(
		attribute.Int("opsatlas.probed", len(observations)),
		attribute.Int("opsatlas.unhealthy", unhealthy),
		attribute.Int("opsatlas.applied", result.Applied),
		attribute.Int("opsatlas.ignored", result.Ignored),
	)

	logger.Info("pass complete",
		"probed", len(observations),
		"applied", result.Applied,
		"ignored", result.Ignored,
		"replayed", result.Replayed,
		"took", time.Since(started).Round(time.Millisecond).String(),
	)
}

// targetCache holds the last successfully read service list.
//
// A refresh that fails keeps the previous list rather than emptying it. The
// control plane being briefly unreachable is not a reason to stop watching the
// fleet - and an observer that silently stopped probing would look identical to
// a fleet that was entirely healthy.
type targetCache struct {
	client      *catalog.Client
	instruments *metrics.Metrics
	logger      *slog.Logger

	mu      sync.RWMutex
	targets []probe.Target
}

func newTargetCache(client *catalog.Client, instruments *metrics.Metrics, logger *slog.Logger) *targetCache {
	return &targetCache{client: client, instruments: instruments, logger: logger}
}

func (c *targetCache) refresh(ctx context.Context) {
	found, skipped, err := c.client.Targets(ctx)
	if err != nil {
		if ctx.Err() != nil {
			return
		}
		c.instruments.CatalogRefreshTotal.WithLabelValues("failed").Inc()
		c.logger.Error("could not refresh the service list; continuing with the previous one",
			"error", err, "targets", len(c.get()))
		return
	}

	c.instruments.CatalogRefreshTotal.WithLabelValues("ok").Inc()
	c.instruments.TargetsWatched.Set(float64(len(found)))
	c.instruments.TargetsSkipped.Set(float64(skipped))

	c.mu.Lock()
	c.targets = toProbeTargets(found)
	c.mu.Unlock()

	c.logger.Info("service list refreshed", "targets", len(found), "skipped", skipped)
}

func (c *targetCache) get() []probe.Target {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.targets
}

func toProbeTargets(found []catalog.Target) []probe.Target {
	targets := make([]probe.Target, 0, len(found))
	for _, target := range found {
		targets = append(targets, probe.Target{
			ServiceSlug:   target.ServiceSlug,
			EnvironmentID: target.EnvironmentID,
			Environment:   target.Environment,
			ProbeURL:      target.ProbeURL,
		})
	}
	return targets
}
