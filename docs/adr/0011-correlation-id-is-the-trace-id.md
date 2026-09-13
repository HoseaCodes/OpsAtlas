# 0011 — The correlation ID *is* the trace ID, when there is one

- **Status:** Accepted
- **Date:** 2026-09-13
- **Phase:** 8 — telemetry

## Context

`CLAUDE.md` §9 asks for two things that have been treated as one problem and
solved as half of one:

> Correlation ID accepted from `X-Correlation-Id` or generated, put in the MDC,
> logged on every line, echoed in every response and error.
>
> Trace context propagated (W3C `traceparent`).

The first has been done since phase 1. The second has not been done at all —
this is the only convention in §9 that has never been implemented, and it has
been quietly unmet for seven phases.

Doing it naively produces two identifiers for one request. A log line would
carry `correlationId=8f2c…` while the trace carrying that same work would be
`traceId=a41b…`, and joining them would mean joining on timestamps, which is
how a two-minute investigation becomes twenty.

The observer makes this concrete rather than theoretical. It probes a service,
reports a batch, and the control plane registers what it found. That is one
causal chain across two processes and three HTTP calls, and it is the first
thing in this system that a trace would genuinely explain.

## Decision

**When a request is traced, its correlation ID *is* its trace ID.**

- A request arriving with a W3C `traceparent` joins that trace, and — if it
  supplies no correlation ID of its own — its correlation ID becomes the
  32-character trace ID.
- A request that supplies `X-Correlation-Id` gets **that value back**, unchanged,
  and it is attached to the span as an attribute so a search by it finds the same
  work.
- A request with neither gets a generated trace, and the trace ID becomes the
  correlation ID.

The formats already agree, which is what makes this possible rather than
merely desirable: the existing correlation ID pattern is
`[A-Za-z0-9_-]{8,64}` and a W3C trace ID is 32 lowercase hex characters. No
validation changes.

**One string searches everything.** The same value appears in the log line, in
the trace, in the response header, in the problem document, and in the
`audit_event` row. Reading it off a failure and pasting it into any of those
finds the same request.

**The observer propagates.** It starts a span per pass, a child span per probe,
and sends `traceparent` when it reads the catalog and reports observations — so
a registration caused by a poll, or a health change caused by a probe, is one
trace rather than three unconnected ones.

### Amended during implementation: a supplied ID wins

The first version of this decision had the trace ID win even over a
caller-supplied `X-Correlation-Id`, reasoning that the logs and the trace should
carry the same string no matter what. Two existing tests caught it immediately,
and they were right: §9 says the correlation ID is "accepted from
`X-Correlation-Id`" and "echoed in every response", and a caller who sends an ID
and receives a different one cannot correlate anything — which is the only reason
to send one.

The original reasoning was right about the common case and wrong about this one.
When nobody supplies an ID there is only the trace ID and it is used; when
somebody does, they have asked for theirs by name. Tagging the span with it
keeps both searchable, which is what the reasoning actually wanted.

## Alternatives rejected

**Keep them separate and correlate by timestamp.** No code, no coupling.
Rejected because it does not work: at any real request rate the timestamps
overlap, and the person doing the joining is doing it during an incident.

**Replace the correlation ID with the trace ID entirely.** Cleanest — one
concept, not two. Rejected because a client-supplied correlation ID survives
things a trace does not: a caller that does not speak W3C trace context, a
request that never reaches a traced service, and a sampling decision that
dropped the trace. The identifier that appears in an error message should not
depend on whether a span was sampled.

**Put the trace ID in a separate `traceId` field alongside `correlationId`.**
Honest and explicit, and what most systems do. Rejected because it leaves the
reader with two strings and the question of which to search — and the answer
would be "both, they are the same", which is a fact better expressed by making
them the same.

**Sample traces at less than 100%.** Standard practice and necessary at volume.
Rejected for now: this system's traffic is a poll every five minutes and an
occasional human request. Sampling would mean the one trace someone goes looking
for is the one that was dropped, and would make the correlation ID unreliable
exactly as described above. It is recorded as the first thing to revisit if
volume ever justifies it.

## Consequences

**Good.** One identifier, everywhere. A failure hands the reader a string that
works in the logs, in Tempo, and against the audit log. The observer's work
becomes explicable: a probe, the report it produced, and the state change that
followed are one trace across two processes.

**The cost, stated plainly.** The correlation ID's meaning now depends on where
it came from — a caller's value when supplied, a trace ID when traced, a
generated UUID otherwise. Those are different shapes (32 hex characters versus a hyphenated
UUID), so anything parsing it would break. Nothing parses it today, and nothing
should; it is an opaque search key and the API documents it as one.

**Turning tracing off changes behaviour.** With the exporter disabled the
correlation ID falls back to a generated value, so the same deployment produces
different-looking identifiers depending on configuration. That is a real
inconsistency, and the alternative — always generating a UUID and attaching it
to the span — reintroduces the two-identifier problem this decision exists to
remove.

**100% sampling is a real cost at scale**, in the collector and in storage. It
is right at this system's volume and would be wrong at a hundred times it. The
decision to revisit is recorded rather than deferred silently.

**Logs are not shipped anywhere.** Loki is in the roadmap and is not being done:
the control plane already logs structured JSON to stdout carrying the
correlation ID, and shipping it needs an agent, a retention policy and a second
query language for value that `docker logs | grep` already provides locally. It
is deferred with that reason rather than half-built.
