// Package report sends observations to the control plane.
package report

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

// Observation is one probe result, in the shape the control plane accepts.
type Observation struct {
	EnvironmentID string  `json:"environmentId"`
	ObservedAt    string  `json:"observedAt"`
	Outcome       string  `json:"outcome"`
	ResponseMs    *int    `json:"responseMs"`
	StatusCode    *int    `json:"statusCode"`
	Detail        *string `json:"detail"`
}

type batch struct {
	IdempotencyKey string        `json:"idempotencyKey"`
	ObserverID     string        `json:"observerId"`
	Observations   []Observation `json:"observations"`
}

// Result is what the control plane did with a batch.
type Result struct {
	Applied  int  `json:"applied"`
	Ignored  int  `json:"ignored"`
	Replayed bool `json:"replayed"`
}

// Reporter posts batches.
type Reporter struct {
	baseURL    string
	observerID string
	http       *http.Client
}

// New returns a Reporter.
func New(baseURL, observerID string, timeout time.Duration) *Reporter {
	return &Reporter{
		baseURL:    strings.TrimRight(baseURL, "/"),
		observerID: observerID,
		http:       &http.Client{Timeout: timeout},
	}
}

// Send delivers one pass's observations, retrying transient failures.
//
// The idempotency key is derived from the observer and the pass, so every
// attempt at the same pass carries the same key. That is what makes retrying
// safe: the control plane stores counters, and a batch applied twice would
// inflate them with nothing visibly wrong to notice.
//
// Retries are bounded and backed off. An observer that cannot report is not an
// emergency - it keeps probing, and the next pass carries fresh results - so it
// is better to give up on this batch than to spend the interval retrying and
// fall behind.
func (r *Reporter) Send(ctx context.Context, passStart time.Time, observations []Observation) (Result, error) {
	if len(observations) == 0 {
		return Result{}, nil
	}

	payload := batch{
		IdempotencyKey: r.key(passStart),
		ObserverID:     r.observerID,
		Observations:   observations,
	}
	body, err := json.Marshal(payload)
	if err != nil {
		return Result{}, fmt.Errorf("encoding batch: %w", err)
	}

	const attempts = 3
	var lastErr error
	for attempt := 0; attempt < attempts; attempt++ {
		if attempt > 0 {
			// Backoff, capped well inside a probe interval so a failing
			// control plane cannot stall the next pass.
			delay := time.Duration(attempt) * 500 * time.Millisecond
			select {
			case <-ctx.Done():
				return Result{}, ctx.Err()
			case <-time.After(delay):
			}
		}

		result, err := r.post(ctx, body)
		if err == nil {
			return result, nil
		}
		lastErr = err

		// A rejected batch will be rejected identically next time: the payload
		// is the problem, not the transport. Retrying is pure waste.
		if isPermanent(err) {
			return Result{}, err
		}
		if ctx.Err() != nil {
			return Result{}, ctx.Err()
		}
	}
	return Result{}, fmt.Errorf("after %d attempts: %w", attempts, lastErr)
}

// key is stable for a pass and distinct between passes.
//
// Nanosecond precision on the pass start, so two passes cannot collide even if
// the interval is tiny, and the observer id so two observers never share one.
func (r *Reporter) key(passStart time.Time) string {
	return fmt.Sprintf("%s-%d", r.observerID, passStart.UTC().UnixNano())
}

// permanentError marks a failure that retrying cannot fix.
type permanentError struct{ error }

func isPermanent(err error) bool {
	var permanent permanentError
	return errors.As(err, &permanent)
}

func (r *Reporter) post(ctx context.Context, body []byte) (Result, error) {
	request, err := http.NewRequestWithContext(
		ctx, http.MethodPost, r.baseURL+"/api/v1/observations", bytes.NewReader(body))
	if err != nil {
		return Result{}, permanentError{fmt.Errorf("building request: %w", err)}
	}
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Accept", "application/json")

	response, err := r.http.Do(request)
	if err != nil {
		return Result{}, fmt.Errorf("posting observations: %w", err)
	}
	defer response.Body.Close()

	if response.StatusCode == http.StatusAccepted || response.StatusCode == http.StatusOK {
		var result Result
		if err := json.NewDecoder(io.LimitReader(response.Body, 1<<20)).Decode(&result); err != nil {
			return Result{}, fmt.Errorf("decoding response: %w", err)
		}
		return result, nil
	}

	detail, _ := io.ReadAll(io.LimitReader(response.Body, 1024))
	err = fmt.Errorf("control plane returned %d: %s", response.StatusCode, strings.TrimSpace(string(detail)))

	// 4xx other than 429 means this payload is wrong and will be wrong again.
	if response.StatusCode >= 400 && response.StatusCode < 500 && response.StatusCode != http.StatusTooManyRequests {
		return Result{}, permanentError{err}
	}
	return Result{}, err
}
