// Package probe performs a single health check and classifies what happened.
package probe

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

// Outcome mirrors the control plane's ProbeOutcome.
//
// Four outcomes rather than a boolean, because they call for different
// responses. A service answering 503 is up and saying it is not ready; one that
// times out may be wedged; a refused connection means nothing is listening at
// all. "Failed" would throw away the first thing an on-call engineer asks.
type Outcome string

const (
	Healthy     Outcome = "HEALTHY"
	Unhealthy   Outcome = "UNHEALTHY"
	Timeout     Outcome = "TIMEOUT"
	Unreachable Outcome = "UNREACHABLE"
)

// Result is one probe, as it will be reported.
type Result struct {
	Outcome    Outcome
	ObservedAt time.Time

	// ResponseMs is nil when nothing answered. A timeout has no round trip to
	// report, and sending the timeout value instead would put the timeout into
	// the control plane's latency average - silently raising every mean the
	// moment a service went down.
	ResponseMs *int

	// StatusCode is nil for a timeout or a refused connection, which is itself
	// the distinction between "answered badly" and "did not answer".
	StatusCode *int

	Detail string

	// Attempts includes the first try. Reported in metrics, not to the control
	// plane: how hard we had to work to get an answer is the observer's
	// business, and one probe per environment per pass is what the counters
	// expect.
	Attempts int
}

// Prober performs probes. Safe for concurrent use.
type Prober struct {
	client  *http.Client
	retries int
	now     func() time.Time
}

// New returns a Prober.
//
// Redirects are deliberately not followed. A health endpoint that redirects is
// not answering the question, and following one could take the probe to a host
// the manifest never named.
func New(timeout time.Duration, retries int) *Prober {
	return &Prober{
		client: &http.Client{
			Timeout: timeout,
			CheckRedirect: func(*http.Request, []*http.Request) error {
				return http.ErrUseLastResponse
			},
		},
		retries: retries,
		now:     time.Now,
	}
}

// Probe checks one URL, retrying only what is worth retrying.
//
// A connection-level failure may be a dropped packet, so it gets another go. A
// status code is a definitive answer and is never retried: asking the same
// question twice does not change it, and would just double the load on a
// service that is already struggling.
func (p *Prober) Probe(ctx context.Context, url string) Result {
	var result Result

	for attempt := 0; attempt <= p.retries; attempt++ {
		result = p.attempt(ctx, url)
		result.Attempts = attempt + 1

		if result.Outcome == Healthy || result.Outcome == Unhealthy {
			return result
		}
		if ctx.Err() != nil {
			// Shutting down. Report what we have rather than retrying into a
			// cancelled context and reporting a failure we caused.
			return result
		}
	}
	return result
}

func (p *Prober) attempt(ctx context.Context, url string) Result {
	started := p.now()

	request, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return Result{
			Outcome:    Unreachable,
			ObservedAt: started,
			Detail:     fmt.Sprintf("The probe URL could not be used: %v.", err),
		}
	}
	request.Header.Set("User-Agent", "OpsAtlas-Observer")
	request.Header.Set("Accept", "*/*")

	response, err := p.client.Do(request)
	elapsed := int(p.now().Sub(started).Milliseconds())

	if err != nil {
		if isTimeout(err) {
			return Result{
				Outcome:    Timeout,
				ObservedAt: started,
				Detail:     fmt.Sprintf("The probe timed out after %s.", p.client.Timeout),
			}
		}
		return Result{
			Outcome:    Unreachable,
			ObservedAt: started,
			Detail:     "Nothing answered at the probe address: " + summarise(err) + ".",
		}
	}
	defer response.Body.Close()

	// The body is drained and discarded, capped. Draining lets the connection
	// be reused; the cap stops a health endpoint that returns a large document
	// from costing the observer memory once per environment per pass.
	_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, 32<<10))

	status := response.StatusCode
	if status >= 200 && status < 300 {
		return Result{
			Outcome:    Healthy,
			ObservedAt: started,
			ResponseMs: &elapsed,
			StatusCode: &status,
		}
	}

	return Result{
		Outcome:    Unhealthy,
		ObservedAt: started,
		// A response time is recorded even for an unhealthy answer: it answered,
		// and how fast it said no is still a measurement. The control plane only
		// accumulates latency from successful probes, so this informs the
		// current-state view without skewing any average.
		ResponseMs: &elapsed,
		StatusCode: &status,
		Detail:     fmt.Sprintf("The probe answered %d.", status),
	}
}

func isTimeout(err error) bool {
	var timeout interface{ Timeout() bool }
	if errors.As(err, &timeout) && timeout.Timeout() {
		return true
	}
	return errors.Is(err, context.DeadlineExceeded)
}

// summarise turns a Go transport error into something a person can read.
//
// The raw error embeds the full URL and Go's own wrapping, which makes for a
// long and repetitive sentence in a table cell.
func summarise(err error) string {
	message := err.Error()
	if index := strings.LastIndex(message, ": "); index >= 0 && index+2 < len(message) {
		message = message[index+2:]
	}
	if len(message) > 160 {
		message = message[:157] + "..."
	}
	return message
}
