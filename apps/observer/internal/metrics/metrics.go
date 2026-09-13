// Package metrics exposes what the observer is doing, in Prometheus format.
//
// CLAUDE.md section 5 names Prometheus metrics as part of the observer. The
// metrics here are deliberately about the observer's own behaviour - how many
// probes it ran, how long they took, whether its reports are landing - and not
// about the services it watches. Service health is the control plane's to
// report; duplicating it here would create two answers to one question.
package metrics

import (
	"net/http"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/collectors"
	"github.com/prometheus/client_golang/prometheus/promhttp"
)

// Metrics holds the observer's instruments.
type Metrics struct {
	registry *prometheus.Registry

	ProbesTotal    *prometheus.CounterVec
	ProbeDuration  *prometheus.HistogramVec
	ProbeAttempts  prometheus.Histogram
	TargetsWatched prometheus.Gauge
	TargetsSkipped prometheus.Gauge

	ReportsTotal         *prometheus.CounterVec
	ObservationsApplied  prometheus.Counter
	ObservationsIgnored  prometheus.Counter
	LastSuccessfulReport prometheus.Gauge

	CatalogRefreshTotal *prometheus.CounterVec
}

// New builds the registry.
//
// A private registry rather than the default one, so nothing a dependency
// happens to register leaks into this process's output. Go runtime and process
// collectors are registered explicitly, because a long-running poller's own
// memory and goroutine count are worth watching.
func New() *Metrics {
	registry := prometheus.NewRegistry()
	registry.MustRegister(
		collectors.NewGoCollector(),
		collectors.NewProcessCollector(collectors.ProcessCollectorOpts{}),
	)

	m := &Metrics{
		registry: registry,
		ProbesTotal: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "opsatlas_observer_probes_total",
			Help: "Probes performed, by outcome.",
		}, []string{"outcome"}),
		ProbeDuration: prometheus.NewHistogramVec(prometheus.HistogramOpts{
			Name: "opsatlas_observer_probe_duration_seconds",
			Help: "Probe round-trip time, for probes that got a response.",
			// Buckets chosen for health endpoints, which are fast or broken:
			// resolution where the answers cluster, and a long tail so a
			// pathological endpoint is still bucketed rather than lost in +Inf.
			Buckets: []float64{0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10},
		}, []string{"outcome"}),
		ProbeAttempts: prometheus.NewHistogram(prometheus.HistogramOpts{
			Name:    "opsatlas_observer_probe_attempts",
			Help:    "Attempts needed per probe, including the first.",
			Buckets: prometheus.LinearBuckets(1, 1, 5),
		}),
		TargetsWatched: prometheus.NewGauge(prometheus.GaugeOpts{
			Name: "opsatlas_observer_targets_watched",
			Help: "Environments currently being probed.",
		}),
		TargetsSkipped: prometheus.NewGauge(prometheus.GaugeOpts{
			Name: "opsatlas_observer_targets_skipped",
			Help: "Environments in the catalog that cannot be probed, usually for declaring no URL.",
		}),
		ReportsTotal: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "opsatlas_observer_reports_total",
			Help: "Observation batches sent to the control plane, by result.",
		}, []string{"result"}),
		ObservationsApplied: prometheus.NewCounter(prometheus.CounterOpts{
			Name: "opsatlas_observer_observations_applied_total",
			Help: "Observations the control plane accepted.",
		}),
		ObservationsIgnored: prometheus.NewCounter(prometheus.CounterOpts{
			Name: "opsatlas_observer_observations_ignored_total",
			Help: "Observations the control plane ignored, usually for an environment it no longer knows.",
		}),
		LastSuccessfulReport: prometheus.NewGauge(prometheus.GaugeOpts{
			Name: "opsatlas_observer_last_successful_report_timestamp_seconds",
			Help: "When a batch was last accepted. The thing to alert on: an observer that probes but cannot report is silently useless.",
		}),
		CatalogRefreshTotal: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "opsatlas_observer_catalog_refreshes_total",
			Help: "Attempts to re-read the service list, by result.",
		}, []string{"result"}),
	}

	registry.MustRegister(
		m.ProbesTotal, m.ProbeDuration, m.ProbeAttempts,
		m.TargetsWatched, m.TargetsSkipped,
		m.ReportsTotal, m.ObservationsApplied, m.ObservationsIgnored, m.LastSuccessfulReport,
		m.CatalogRefreshTotal,
	)
	return m
}

// Handler serves /metrics and /healthz.
//
// /healthz reports whether this process is running, and nothing more. It
// deliberately does not go unhealthy when the control plane is unreachable: an
// observer that cannot report is still correctly probing, and restarting it -
// which is what a failing liveness check invites - would fix nothing and lose
// the batch in flight. That the reports are not landing is visible in
// last_successful_report_timestamp_seconds, which is a metric to alert on
// rather than a reason to kill the process.
func (m *Metrics) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.Handle("/metrics", promhttp.HandlerFor(m.registry, promhttp.HandlerOpts{}))
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok\n"))
	})
	return mux
}

// MarkReported records a successful report at the given time.
func (m *Metrics) MarkReported(at time.Time) {
	m.LastSuccessfulReport.Set(float64(at.Unix()))
}
