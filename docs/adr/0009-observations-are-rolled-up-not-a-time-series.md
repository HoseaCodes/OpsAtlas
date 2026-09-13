# 0009 — Observations are stored as rolled-up state, never as a time series

- **Status:** Accepted
- **Date:** 2026-09-13
- **Phase:** 7 — the observer

## Context

Phase 7 is the first thing in this system that measures anything. An observer
probes each declared environment on an interval and reports what it found. At one
probe every 30 seconds, a single environment produces about 2,880 results a day,
and a fleet of fifty environments produces 144,000.

`CLAUDE.md` §6 already decided where those may not go: "PostgreSQL is the system
of record for platform metadata. **Never** store raw metrics, logs or traces in
it. Observations are stored as rolled-up state, not as a time series."

That settles the prohibition. It does not settle what "rolled-up state" is, and
getting that shape wrong is expensive: it is the thing the health meter, the
30-day ribbon and every future error-budget calculation read from.

## Decision

**Two tables, both bounded, neither containing a single probe result.**

**`environment_state` — one row per environment, updated in place.** What is true
right now: the current status, when it was last probed, when it was last seen
healthy, how many consecutive failures there have been, the most recent response
time, and why it is failing if it is. This is what the catalog list reads, and it
is one indexed row per environment forever.

**`environment_day` — one row per environment per UTC day.** Counters, not
samples: probes, successes, failures, and the sum, minimum and maximum of
response times. Thirty of these per environment is the 30-day ribbon.
Availability for a day is `successes / probes`, which is a real number derived
from real probes rather than an estimate.

**A day's row is a running aggregate, not an insert.** Every batch of
observations increments the counters for the day it falls in. Storage per
environment is therefore fixed at one state row plus one row per retained day,
regardless of probe frequency — doubling the probe rate doubles the accuracy and
adds no rows at all.

**Retention is enforced, not assumed.** Days older than the retention window are
deleted by a scheduled job. A table that only grows is a time series wearing a
different hat.

**Ingestion is idempotent.** §9 requires idempotency keys for observation
ingestion so a retried POST does not double-write, and counters are exactly the
shape where a double-write is silent: nothing looks wrong, the numbers are just
inflated. Each batch carries a key, and a replayed key returns the original
result without applying anything.

### What this deliberately cannot answer

**Percentiles.** A p95 needs the distribution, and a sum, a minimum and a maximum
are not a distribution. This stores mean and max response time and calls them
mean and max. It does not compute a "p95" from them, because a number derived
that way would be wrong in a way nobody could see — and a latency figure people
trust and act on during an incident is precisely the wrong place for invented
precision. Real percentiles arrive with the telemetry pipeline in phase 8, which
is where a histogram belongs.

**Anything finer than a day, beyond right now.** There is the current state and
there are daily rollups. "What did this look like at 14:20 last Tuesday" is not
answerable and is not meant to be; that is a metrics system's job.

## Alternatives rejected

**An `observation` table, one row per probe, pruned on a schedule.** The obvious
shape, and it answers every question including the ones above. Rejected because
it is the thing §6 forbids, and the reason §6 forbids it is sound: at 144,000
rows a day the table dominates the database within a week, every query against it
needs a time-range index that the catalog's other queries do not, and the pruning
job becomes load-bearing infrastructure. A control plane's database should hold
metadata about services, not the measurements themselves.

**TimescaleDB, or PostgreSQL partitioned by day.** Makes the previous option
viable at scale. Rejected because it is a substantial operational commitment —
an extension or a partition-management routine — taken on to store data that
another system is better at, and §6 rules Redis and Kafka out on the same
"demonstrated need" grounds.

**Push probe results straight to Prometheus and keep nothing here.** Arguably the
correct long-term architecture, and phase 8 moves in that direction. Rejected as
the *only* store because the catalog must be able to answer "is this healthy" and
"how has it been for thirty days" without a second system being up. A control
plane that cannot say whether a service is healthy when the metrics stack is down
has failed at the one moment it matters.

**Storing only the current state, with no daily rollups.** Simplest possible, one
row per environment. Rejected because it discards the 30-day ribbon, which is the
prototype's clearest idea and the only view that distinguishes "broken now" from
"broken for a fortnight".

## Consequences

**Good.** Storage is bounded by environments times retained days, and is
independent of probe frequency — so the observer can probe as often as is useful
without a storage conversation. The catalog's hot query reads one row per
environment. Availability is computed from counted probes rather than estimated.
The database stays a metadata store, which is what every other decision here
assumes.

**The cost, stated plainly.** Detail is destroyed at write time and cannot be
recovered. A day that shows 97% availability cannot be asked *when* the failures
were, whether they were one outage or ninety scattered blips, or what the
latency looked like during them. That is a real loss, and during a post-incident
review someone will want exactly that. The answer is the telemetry pipeline, and
until phase 8 exists the answer is genuinely "we do not have it".

**A day boundary is a real seam.** An outage spanning midnight appears as two
partial days, and a UTC day is not a working day anywhere in particular.

**Counters are unforgiving of double-counting.** An ingestion bug that applies a
batch twice produces numbers that are simply wrong, with nothing visibly broken
to notice. The idempotency key is not a nicety here; it is what makes the
counters trustworthy, and it is tested as such.

**Availability from probes is not availability.** It is the fraction of *probes*
that succeeded from *one vantage point*, on an interval, against a health
endpoint. A service can serve errors to every real user while its readiness probe
answers happily. The console must say "probe availability" and mean it, and no
document in this repository may call this an SLO measurement.
