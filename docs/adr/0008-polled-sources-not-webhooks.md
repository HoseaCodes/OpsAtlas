# 0008 — Manifests are polled from repositories, not pushed by webhooks

- **Status:** Accepted
- **Date:** 2026-09-12
- **Phase:** 6 — GitHub sync

## Context

Slice one registers a service by submitting its `service.yaml` in a request
body. That was the right scope for a slice with no external integration, and it
is also the thing that keeps the catalog from being true: a manifest edited in
its repository does not reach OpsAtlas until somebody remembers to paste it
again. A catalog that drifts from the repositories it describes is worse than no
catalog, because people believe it.

Phase 6 closes that gap. The question is how a change in a repository becomes a
change in the catalog, and the answer determines what permissions this system
has to hold over other people's repositories — which is the part that is hard to
walk back.

## Decision

**OpsAtlas polls. It registers no webhooks and holds no write access.**

A *source* is a repository, a ref and a path that OpsAtlas watches. A scheduled
sync reads each source's manifest and feeds it through the same ingestion and
registration path a pasted manifest takes. Nothing about validation, scoring or
auditing is different because a manifest arrived by poll.

Three things fall out of that, and they are the decision as much as the polling
is:

**Read-only, and structurally so.** The reader talks to one fixed host over
HTTPS and builds its URL from a validated `owner/name`, a ref and a path. There
is no code path that writes to a repository, because there is no method that
does. A token, where one is configured, needs only public or contents-read
scope, and the documentation says so.

**Conditional requests.** Each source stores the ETag GitHub returned. The next
poll sends `If-None-Match`, and a `304 Not Modified` costs no content transfer
and no ingestion. Polling without this would re-read and re-validate every
manifest every interval to discover that nothing changed.

**A failed sync never erases what is known.** If GitHub is unreachable, or the
token is rejected, or the manifest has become invalid, the previously registered
service stays exactly as it was and the *source* records why the sync failed.
The catalog goes stale rather than going blank, and the console says which it is.
This is the partial-failure case §10 calls the normal case for a control plane.

## Alternatives rejected

**A webhook per repository.** Near-instant: a push reaches the catalog in about
a second rather than within the poll interval. Rejected because registering a
webhook requires write access to every monitored repository. That is a large,
permanent permission footprint bought to remove a few minutes of staleness from
a catalog — and the staleness is visible and labelled, while the permission is
neither. The prototype reached the same conclusion and called it "staleness
bought in exchange for a smaller permission footprint."

**A GitHub App.** The right answer at organisational scale: fine-grained
permissions, per-installation tokens, higher rate limits, and no personal
credential in the loop. Rejected *for this phase* rather than on the merits. An
App needs a registered application, a private key, an installation flow and a
place to store per-installation tokens — none of which exists yet, and the
identity module is still a stub (ADR 0003). Polling with an optional read-only
token works today against public repositories with no credential at all, and the
reader is behind an interface precisely so that swapping in App authentication
later changes one class. It is recorded in `docs/roadmap.md`.

**Git clone, then read from disk.** Would give full history and work for any
provider. Rejected because cloning a repository to read one small file is a large
amount of I/O, disk and cleanup for the value, and running `git` against
repository-supplied refs is a shelling-out surface this project has spent real
effort not having (§3 rule 4).

**Letting CI push the manifest to OpsAtlas.** Zero polling, no read access
needed, and the repository stays in control. Genuinely attractive, and it is
what the existing `POST /api/v1/services` already supports. Rejected as the
*primary* mechanism because it makes the catalog's completeness depend on every
team remembering to add a step to their pipeline — the failure mode is a service
that is silently absent, which is the exact failure a catalog exists to prevent.
It stays available for teams that prefer it.

## Consequences

**Good.** OpsAtlas holds no write access to anything. A manifest edited in a
repository reaches the catalog on its own. Ingestion, validation, scoring and
audit are unchanged, so a polled manifest and a pasted one cannot diverge in how
they are treated. A source that stops working says what went wrong and when it
last worked, rather than disappearing.

**The cost, stated plainly.** The catalog is stale for up to one poll interval,
and it is stale *invisibly* for that window — nothing distinguishes "synced two
minutes ago and unchanged" from "changed one minute ago and not yet seen". The
interval is the whole of the guarantee, and shortening it trades rate limit for
freshness.

**Rate limits are a real ceiling.** Unauthenticated GitHub allows 60 requests an
hour per IP. At a five-minute interval that is twelve polls an hour, so *five
sources* saturate it — conditional requests help only after the first response,
because a 304 still counts against the limit. This is fine for a handful of
repositories and does not scale, and the README says so rather than letting
someone discover it by watching syncs start failing. A token raises it to 5,000
an hour, which is the practical answer until the App arrives.

**Polling is work the system does whether or not anything changed.** Twelve
requests an hour per source, forever, to learn nothing most of the time. That is
the trade being made, and it is only defensible because the alternative is write
access.

**A deleted or renamed repository looks like an outage at first.** Both surface
as a failing sync, and only the `404` versus a connection error distinguishes
them. Nothing automatically retires a service whose repository has vanished —
deciding that a service is gone is not a decision a poll timeout should make.
