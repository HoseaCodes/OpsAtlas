package config

import (
	"strings"
	"testing"
	"time"
)

func TestDefaultsAreUsable(t *testing.T) {
	// The observer starts with no configuration at all, against a control
	// plane running locally.
	cfg, err := Load()
	if err != nil {
		t.Fatalf("defaults should be valid, got %v", err)
	}
	if cfg.APIURL != "http://localhost:8080" {
		t.Fatalf("APIURL = %q", cfg.APIURL)
	}
	if cfg.ProbeTimeout >= cfg.ProbeInterval {
		t.Fatal("the default timeout must be shorter than the default interval")
	}
	if cfg.ObserverID == "" {
		t.Fatal("ObserverID must default to something")
	}
}

func TestTimeoutLongerThanIntervalIsRefused(t *testing.T) {
	// Otherwise a pass cannot finish before the next is due, and the observer
	// falls permanently behind while looking like it is working.
	t.Setenv("OPSATLAS_PROBE_TIMEOUT", "60s")
	t.Setenv("OPSATLAS_PROBE_INTERVAL", "30s")

	_, err := Load()
	if err == nil {
		t.Fatal("a timeout longer than the interval must be refused at startup")
	}
	if !strings.Contains(err.Error(), "shorter than") {
		t.Fatalf("the error should explain the relationship, got %v", err)
	}
}

func TestObserverIDIsConstrainedToWhatTheKeyAllows(t *testing.T) {
	// The id becomes part of an idempotency key, which the control plane
	// constrains. Checking here turns a confusing 422 on every report into a
	// refusal to start.
	t.Setenv("OPSATLAS_OBSERVER_ID", "has spaces and $ymbols")

	if _, err := Load(); err == nil {
		t.Fatal("an observer id that cannot appear in an idempotency key must be refused")
	}
}

func TestNonHttpApiUrlIsRefused(t *testing.T) {
	t.Setenv("OPSATLAS_API_URL", "ftp://example.com")

	if _, err := Load(); err == nil {
		t.Fatal("a non-http control plane URL must be refused")
	}
}

func TestMalformedDurationFallsBackRatherThanFailing(t *testing.T) {
	// A typo in an optional tuning knob should not stop the observer observing.
	t.Setenv("OPSATLAS_PROBE_INTERVAL", "not-a-duration")

	cfg, err := Load()
	if err != nil {
		t.Fatalf("a malformed optional duration should fall back, got %v", err)
	}
	if cfg.ProbeInterval != 30*time.Second {
		t.Fatalf("ProbeInterval = %s, want the default", cfg.ProbeInterval)
	}
}

func TestZeroConcurrencyIsRefused(t *testing.T) {
	t.Setenv("OPSATLAS_PROBE_CONCURRENCY", "0")

	if _, err := Load(); err == nil {
		t.Fatal("zero concurrency would probe nothing, forever, silently")
	}
}
