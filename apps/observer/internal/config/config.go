// Package config reads the observer's settings from the environment.
//
// Every setting has a default that works against a control plane running
// locally, so the observer starts with no configuration at all. Nothing here
// is a secret: the observer reads the catalog and writes observations, and
// holds no credentials for the services it probes.
package config

import (
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"
)

// Config is the whole of the observer's configuration.
type Config struct {
	// APIURL is the control plane. The observer is useless without it and
	// refuses to start if it is not a usable URL.
	APIURL string

	// APIKey authenticates this observer to the control plane.
	//
	// The observer is a component of OpsAtlas rather than a person, so it does
	// not sign in to the identity provider: it carries a key the control plane
	// issued and the audit log records it as `service:observer` (ADR 0013).
	//
	// Optional here, and it should not be. Without it every request is refused
	// and the observer does nothing but log 401s - but failing to start would
	// mean a control plane that has not been given a key yet takes its observer
	// down with it, and the operator loses the metrics endpoint that would have
	// told them why.
	APIKey string

	// ObserverID identifies this observer in reported batches and in the audit
	// trail. Defaults to the hostname, which is right in a container and
	// adequate on a laptop.
	ObserverID string

	// ProbeInterval is how often every known environment is probed.
	//
	// This is the resolution of everything downstream: availability for a day
	// is successes over probes, so halving this doubles the accuracy of that
	// figure and, because the control plane stores counters rather than
	// samples, costs no extra storage at all.
	ProbeInterval time.Duration

	// ProbeTimeout bounds a single attempt. A health endpoint that cannot
	// answer within a few seconds is not healthy, whatever it would eventually
	// have said.
	ProbeTimeout time.Duration

	// ProbeConcurrency caps simultaneous in-flight probes.
	//
	// Unbounded concurrency would let one pass open a socket per environment at
	// once - a stampede against the fleet, from a process whose entire job is
	// to observe it without disturbing it.
	ProbeConcurrency int

	// ProbeRetries is how many extra attempts a transient failure gets.
	//
	// Only connection-level failures are retried. A 503 is a definitive answer
	// and retrying it would just be asking the same question twice.
	ProbeRetries int

	// CatalogRefresh is how often the service list is re-read. Slower than
	// probing: the catalog changes when someone edits a manifest, not every
	// thirty seconds.
	CatalogRefresh time.Duration

	// MetricsAddr is where Prometheus metrics and the health endpoint listen.
	MetricsAddr string
}

// Load reads configuration, applying defaults, and validates it.
func Load() (Config, error) {
	hostname, err := os.Hostname()
	if err != nil || strings.TrimSpace(hostname) == "" {
		hostname = "observer"
	}

	cfg := Config{
		APIURL:           env("OPSATLAS_API_URL", "http://localhost:8080"),
		APIKey:           env("OPSATLAS_API_KEY", ""),
		ObserverID:       env("OPSATLAS_OBSERVER_ID", hostname),
		ProbeInterval:    duration("OPSATLAS_PROBE_INTERVAL", 30*time.Second),
		ProbeTimeout:     duration("OPSATLAS_PROBE_TIMEOUT", 5*time.Second),
		ProbeConcurrency: integer("OPSATLAS_PROBE_CONCURRENCY", 8),
		ProbeRetries:     integer("OPSATLAS_PROBE_RETRIES", 1),
		CatalogRefresh:   duration("OPSATLAS_CATALOG_REFRESH", 5*time.Minute),
		MetricsAddr:      env("OPSATLAS_METRICS_ADDR", ":9090"),
	}

	return cfg, cfg.validate()
}

func (c Config) validate() error {
	switch {
	case !strings.HasPrefix(c.APIURL, "http://") && !strings.HasPrefix(c.APIURL, "https://"):
		return fmt.Errorf("OPSATLAS_API_URL must be an http or https URL, got %q", c.APIURL)
	case c.APIKey != "" && !strings.HasPrefix(c.APIKey, "opsatlas_sk_"):
		// Caught here rather than as a 401 an hour later. The error never
		// quotes the value: a key in a log is a leaked key.
		return fmt.Errorf("OPSATLAS_API_KEY does not look like a key from `make observer-key` "+
			"(it should start with opsatlas_sk_); refusing to start rather than failing every request %s", "")

	// The observer id reaches an audit trail and an idempotency key, and the
	// control plane constrains the latter. Checking here turns a confusing
	// 422 on every report into a refusal to start.
	case !validObserverID(c.ObserverID):
		return fmt.Errorf(
			"OPSATLAS_OBSERVER_ID must be 1-64 characters of letters, digits, dot, underscore or hyphen, got %q",
			c.ObserverID)

	case c.ProbeTimeout >= c.ProbeInterval:
		// Otherwise a pass cannot finish before the next one is due, and the
		// observer falls permanently behind while looking like it is working.
		return fmt.Errorf(
			"OPSATLAS_PROBE_TIMEOUT (%s) must be shorter than OPSATLAS_PROBE_INTERVAL (%s)",
			c.ProbeTimeout, c.ProbeInterval)

	case c.ProbeConcurrency < 1:
		return fmt.Errorf("OPSATLAS_PROBE_CONCURRENCY must be at least 1, got %d", c.ProbeConcurrency)

	case c.ProbeRetries < 0:
		return fmt.Errorf("OPSATLAS_PROBE_RETRIES cannot be negative, got %d", c.ProbeRetries)

	case c.CatalogRefresh < time.Second:
		return fmt.Errorf("OPSATLAS_CATALOG_REFRESH must be at least 1s, got %s", c.CatalogRefresh)
	}
	return nil
}

func validObserverID(id string) bool {
	if len(id) == 0 || len(id) > 64 {
		return false
	}
	for _, r := range id {
		switch {
		case r >= 'a' && r <= 'z', r >= 'A' && r <= 'Z', r >= '0' && r <= '9':
		case r == '.' || r == '_' || r == '-':
		default:
			return false
		}
	}
	return true
}

func env(key, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(key)); value != "" {
		return value
	}
	return fallback
}

func duration(key string, fallback time.Duration) time.Duration {
	raw := strings.TrimSpace(os.Getenv(key))
	if raw == "" {
		return fallback
	}
	parsed, err := time.ParseDuration(raw)
	if err != nil || parsed <= 0 {
		// Deliberately falls back rather than failing: a malformed duration is
		// caught by validate() only where it matters, and a typo in an
		// optional tuning knob should not stop the observer observing.
		return fallback
	}
	return parsed
}

func integer(key string, fallback int) int {
	raw := strings.TrimSpace(os.Getenv(key))
	if raw == "" {
		return fallback
	}
	parsed, err := strconv.Atoi(raw)
	if err != nil {
		return fallback
	}
	return parsed
}
