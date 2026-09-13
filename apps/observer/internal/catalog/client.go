// Package catalog reads the service list from the control plane.
//
// The observer holds no opinion about what should be probed: it probes exactly
// what the catalog declares, which is exactly what the owning teams put in
// their service.yaml. Nothing here writes to the catalog.
package catalog

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/ambitious-concepts/opsatlas/observer/internal/tracing"
)

// Target is one environment worth probing.
type Target struct {
	ServiceSlug   string
	EnvironmentID string
	Environment   string

	// ProbeURL is the environment URL joined to the readiness path. Empty
	// targets never reach here - Targets filters them out.
	ProbeURL string
}

// Client reads the catalog. Read-only: there is no method that writes.
type Client struct {
	baseURL string
	http    *http.Client
}

// New returns a client with a bounded HTTP client.
//
// The timeout is generous compared to a probe's: this is one request that
// returns a page of services, and failing it means the observer probes a stale
// list, which is worse than waiting a moment longer.
//
// The transport carries W3C trace context, so reading the catalog is a child
// span of the pass that needed it rather than an unconnected request in the
// control plane's traces (ADR 0011).
func New(baseURL string, timeout time.Duration) *Client {
	return &Client{
		baseURL: strings.TrimRight(baseURL, "/"),
		http: &http.Client{
			Timeout:   timeout,
			Transport: tracing.Transport(nil),
		},
	}
}

type servicePage struct {
	Items []struct {
		Slug string `json:"slug"`
	} `json:"items"`
	NextCursor *string `json:"nextCursor"`
}

type serviceDetail struct {
	Slug         string `json:"slug"`
	Environments []struct {
		ID            string  `json:"id"`
		Name          string  `json:"name"`
		URL           *string `json:"url"`
		ReadinessPath *string `json:"readinessPath"`
	} `json:"environments"`
}

// Targets returns every environment that can actually be probed.
//
// An environment with no URL is skipped rather than reported as failing: not
// having declared an address is a manifest gap that the scorecard already
// reports, and probing something we were never given would be inventing a
// failure. The count of skipped targets is returned so the caller can surface
// it rather than it vanishing.
func (c *Client) Targets(ctx context.Context) (targets []Target, skipped int, err error) {
	cursor := ""
	for {
		page, pageErr := c.listServices(ctx, cursor)
		if pageErr != nil {
			return nil, 0, pageErr
		}

		for _, item := range page.Items {
			detail, detailErr := c.service(ctx, item.Slug)
			if detailErr != nil {
				// One unreadable service must not cost us the whole catalog.
				// The observer probes what it could read and says how much it
				// could not.
				skipped++
				continue
			}

			for _, environment := range detail.Environments {
				probeURL, ok := probeURL(environment.URL, environment.ReadinessPath)
				if !ok {
					skipped++
					continue
				}
				targets = append(targets, Target{
					ServiceSlug:   detail.Slug,
					EnvironmentID: environment.ID,
					Environment:   environment.Name,
					ProbeURL:      probeURL,
				})
			}
		}

		if page.NextCursor == nil || *page.NextCursor == "" {
			return targets, skipped, nil
		}
		cursor = *page.NextCursor
	}
}

// probeURL joins an environment URL to its readiness path.
//
// A readiness path is optional: an environment with a URL and no declared path
// is probed at its root, which is better than not probing it at all. A URL that
// does not parse is refused rather than guessed at.
func probeURL(base *string, readiness *string) (string, bool) {
	if base == nil || strings.TrimSpace(*base) == "" {
		return "", false
	}
	parsed, err := url.Parse(strings.TrimSpace(*base))
	if err != nil || (parsed.Scheme != "http" && parsed.Scheme != "https") || parsed.Host == "" {
		return "", false
	}

	path := "/"
	if readiness != nil && strings.TrimSpace(*readiness) != "" {
		path = strings.TrimSpace(*readiness)
	}
	if !strings.HasPrefix(path, "/") {
		path = "/" + path
	}

	// Resolved against the base rather than concatenated, so a base carrying a
	// path prefix behaves and a path cannot escape the host.
	resolved, err := parsed.Parse(path)
	if err != nil || resolved.Host != parsed.Host {
		return "", false
	}
	return resolved.String(), true
}

func (c *Client) listServices(ctx context.Context, cursor string) (servicePage, error) {
	endpoint := c.baseURL + "/api/v1/services?limit=100"
	if cursor != "" {
		endpoint += "&cursor=" + url.QueryEscape(cursor)
	}

	var page servicePage
	return page, c.get(ctx, endpoint, &page)
}

func (c *Client) service(ctx context.Context, slug string) (serviceDetail, error) {
	var detail serviceDetail
	return detail, c.get(ctx, c.baseURL+"/api/v1/services/"+url.PathEscape(slug), &detail)
}

func (c *Client) get(ctx context.Context, endpoint string, into any) error {
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return fmt.Errorf("building request for %s: %w", endpoint, err)
	}
	request.Header.Set("Accept", "application/json")

	response, err := c.http.Do(request)
	if err != nil {
		return fmt.Errorf("reading %s: %w", endpoint, err)
	}
	defer response.Body.Close()

	if response.StatusCode != http.StatusOK {
		// The body is capped before being read into an error message: an error
		// path is not the place to allocate whatever the other end sent.
		body, _ := io.ReadAll(io.LimitReader(response.Body, 512))
		return fmt.Errorf("%s returned %d: %s", endpoint, response.StatusCode, strings.TrimSpace(string(body)))
	}

	if err := json.NewDecoder(io.LimitReader(response.Body, 8<<20)).Decode(into); err != nil {
		return fmt.Errorf("decoding %s: %w", endpoint, err)
	}
	return nil
}
