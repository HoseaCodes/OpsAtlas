package catalog

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func ptr(s string) *string { return &s }

func TestProbeURLJoinsBaseAndReadinessPath(t *testing.T) {
	cases := []struct {
		name      string
		base      *string
		readiness *string
		want      string
		ok        bool
	}{
		{"base and path", ptr("https://orders.example.com"), ptr("/actuator/health/readiness"),
			"https://orders.example.com/actuator/health/readiness", true},
		{"trailing slash on base", ptr("https://orders.example.com/"), ptr("/readyz"),
			"https://orders.example.com/readyz", true},
		{"path without leading slash", ptr("https://orders.example.com"), ptr("readyz"),
			"https://orders.example.com/readyz", true},
		// An environment with a URL and no declared path is probed at its root,
		// which is better than not probing it at all.
		{"no readiness path", ptr("https://orders.example.com"), nil,
			"https://orders.example.com/", true},
		{"no url at all", nil, ptr("/readyz"), "", false},
		{"empty url", ptr("   "), ptr("/readyz"), "", false},
		{"not a url", ptr("not a url"), ptr("/readyz"), "", false},
		{"not http", ptr("ftp://orders.example.com"), ptr("/readyz"), "", false},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got, ok := probeURL(tc.base, tc.readiness)
			if ok != tc.ok {
				t.Fatalf("ok = %v, want %v", ok, tc.ok)
			}
			if ok && got != tc.want {
				t.Fatalf("got %q, want %q", got, tc.want)
			}
		})
	}
}

func TestProbeURLCannotEscapeItsHost(t *testing.T) {
	// A readiness path comes from a manifest in somebody else's repository.
	// It must not be able to redirect the probe at another host.
	for _, path := range []string{"//evil.example.com/", "https://evil.example.com/"} {
		got, ok := probeURL(ptr("https://orders.example.com"), ptr(path))
		if ok && !strings.Contains(got, "orders.example.com") {
			t.Fatalf("path %q escaped its host and produced %q", path, got)
		}
	}
}

func TestTargetsSkipsEnvironmentsWithNoURL(t *testing.T) {
	// Not having declared an address is a manifest gap the scorecard already
	// reports. Probing something we were never given would be inventing a
	// failure.
	server := catalogServer(t, map[string]any{
		"orders-api": map[string]any{
			"slug": "orders-api",
			"environments": []map[string]any{
				{"id": "env-1", "name": "production", "url": "https://orders.example.com", "readinessPath": "/readyz"},
				{"id": "env-2", "name": "staging", "url": nil, "readinessPath": "/readyz"},
			},
		},
	})
	defer server.Close()

	targets, skipped, err := New(server.URL, 5*time.Second).Targets(context.Background())
	if err != nil {
		t.Fatalf("Targets: %v", err)
	}
	if len(targets) != 1 {
		t.Fatalf("got %d targets, want 1", len(targets))
	}
	if skipped != 1 {
		t.Fatalf("skipped = %d, want 1 - a skipped environment must be counted, not vanish", skipped)
	}
	if targets[0].ProbeURL != "https://orders.example.com/readyz" {
		t.Fatalf("ProbeURL = %q", targets[0].ProbeURL)
	}
}

func TestOneUnreadableServiceDoesNotCostTheWholeCatalog(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("/api/v1/services", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, map[string]any{
			"items":      []map[string]any{{"slug": "good"}, {"slug": "broken"}},
			"nextCursor": nil,
		})
	})
	mux.HandleFunc("/api/v1/services/good", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, map[string]any{
			"slug": "good",
			"environments": []map[string]any{
				{"id": "env-1", "name": "production", "url": "https://good.example.com", "readinessPath": "/readyz"},
			},
		})
	})
	mux.HandleFunc("/api/v1/services/broken", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	})
	server := httptest.NewServer(mux)
	defer server.Close()

	targets, skipped, err := New(server.URL, 5*time.Second).Targets(context.Background())
	if err != nil {
		t.Fatalf("one bad service must not fail the whole read, got %v", err)
	}
	if len(targets) != 1 || skipped != 1 {
		t.Fatalf("targets = %d, skipped = %d, want 1 and 1", len(targets), skipped)
	}
}

func TestTargetsFollowsPagination(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("/api/v1/services", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("cursor") == "" {
			next := "page-2"
			writeJSON(w, map[string]any{"items": []map[string]any{{"slug": "one"}}, "nextCursor": next})
			return
		}
		writeJSON(w, map[string]any{"items": []map[string]any{{"slug": "two"}}, "nextCursor": nil})
	})
	for _, slug := range []string{"one", "two"} {
		mux.HandleFunc("/api/v1/services/"+slug, func(w http.ResponseWriter, _ *http.Request) {
			writeJSON(w, map[string]any{
				"slug": slug,
				"environments": []map[string]any{
					{"id": "env-" + slug, "name": "production", "url": "https://" + slug + ".example.com"},
				},
			})
		})
	}
	server := httptest.NewServer(mux)
	defer server.Close()

	targets, _, err := New(server.URL, 5*time.Second).Targets(context.Background())
	if err != nil {
		t.Fatalf("Targets: %v", err)
	}
	// A catalog larger than one page must be fully read, or the observer
	// silently stops watching everything past the first 100 services.
	if len(targets) != 2 {
		t.Fatalf("got %d targets across two pages, want 2", len(targets))
	}
}

func catalogServer(t *testing.T, services map[string]any) *httptest.Server {
	t.Helper()
	mux := http.NewServeMux()
	mux.HandleFunc("/api/v1/services", func(w http.ResponseWriter, _ *http.Request) {
		items := make([]map[string]any, 0, len(services))
		for slug := range services {
			items = append(items, map[string]any{"slug": slug})
		}
		writeJSON(w, map[string]any{"items": items, "nextCursor": nil})
	})
	for slug, detail := range services {
		mux.HandleFunc("/api/v1/services/"+slug, func(w http.ResponseWriter, _ *http.Request) {
			writeJSON(w, detail)
		})
	}
	return httptest.NewServer(mux)
}

func writeJSON(w http.ResponseWriter, body any) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(body)
}
