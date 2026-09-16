# 0019 — The observer is a separate process, and it is written in Go

Status: accepted
Date: 2026-09-16

**Recorded after the fact.** Phase 7 built the observer and nothing ever wrote
down why it is Go or why it is not a scheduled task inside the control plane.
`CLAUDE.md` §5 fixes the stack by fiat, and `README.md` describes what the
observer does rather than why it exists as a separate thing. A decision that was
made and never justified is indistinguishable from a decision that was never
made, and this one is load-bearing for how the whole system gets explained.

## Context

Probing is the only work in OpsAtlas that reaches outside the platform on a
schedule. Everything else is a request arriving, a transaction, and a response.
Probing is thousands of mostly-idle sockets, most of them waiting, a few of them
hanging until a timeout fires, against endpoints belonging to people who did not
agree to be reliable.

That work could have lived in three places: a `@Scheduled` method in the control
plane, a second JVM service, or a separate process in a different language. It
went to the third, and the reasons were never recorded.

## Decision

**Probing runs as a separate process, written in Go, that talks to the control
plane over the same public API any other client would use.**

The reasons, in the order they actually matter:

1. **Different failure domain.** A probe pass that wedges, leaks sockets or
   simply gets slow must not be able to degrade the catalog API. In a separate
   process that boundary is enforced by the operating system. Inside the control
   plane it is enforced by a thread pool's configuration, which is a boundary
   that holds until somebody changes a number.

2. **Different scaling axis.** The control plane scales with users, registrations
   and page loads. The observer scales with services × environments × probe
   frequency — a product that grows with the fleet whether or not anybody opens
   the console. Coupling them means resizing one to fix the other.

3. **Different runtime profile.** Tens of thousands of concurrent, mostly-idle
   network waits is the shape Go's scheduler is built for, with a per-goroutine
   cost measured in kilobytes. The JVM answer is a thread pool sized by guesswork
   or a reactive stack threaded through an application that is otherwise
   straightforwardly blocking and transactional.

4. **It is a client, not a module.** The observer authenticates, paginates, and
   posts observations through the same endpoints anybody else would. That makes
   it a standing proof that the API is usable by a machine, and the proof has
   already paid: turning on authentication (ADR 0013) broke the observer, and
   that was a genuine finding about the API's contract rather than a fact about
   the observer.

## Alternatives rejected

**A `@Scheduled` method inside the control plane.** By far the simplest: one
language, one deployable, one test suite, no network contract, no second set of
configuration. The catalog is right there in the same transaction manager.
Rejected on the failure domain — the component that deliberately talks to
unreliable third-party endpoints is the last one that should share a heap and a
thread pool with the system of record. Worth noting that this alternative would
have inherited the control plane's availability for free, which is a real
advantage the chosen design gives up (see below).

**A second JVM service.** Keeps one language and still separates the failure
domain. Rejected because it takes the cost of a separate deployable without the
benefit of a runtime suited to the work, and because two Spring Boot services is
the shape that turns into fifteen. If the process boundary is being paid for
anyway, the language should be chosen for the job.

**Go for the whole control plane.** The domain is transactional and relational:
Flyway migrations, JPA entities, an idempotent insert that needs `REQUIRES_NEW`
to survive a constraint violation, a modular monolith whose boundaries are
enforced by ArchUnit. That is Spring's home ground. Rewriting it in Go would be
résumé-driven architecture pointed in the other direction.

**Rust or Node for the observer.** Node repeats a capability already evidenced
elsewhere and brings a third package manager. Rust's advantage over Go here —
no garbage collector — is irrelevant at this concurrency against network latency
measured in milliseconds.

## Consequences

**The bad, stated plainly:**

- **Two languages, two toolchains, two test runners.** `make test` runs three
  suites and a contributor needs Go 1.27 installed. That is a genuine
  prerequisite, unlike the JDK, which Gradle provisions itself — so the Go
  requirement is the *only* thing somebody must install to work on this project.
- **The contract between them is a network API**, so changing observation
  ingestion is a versioned change with a deployed client rather than a refactor
  across a package boundary. That bill came due at phase 9 and will again.
- **Cross-cutting concerns are implemented twice.** Trace context, correlation
  IDs, structured logging and configuration all exist in both processes, and
  ADR 0011 is largely the work of making the two implementations agree on what a
  trace is.
- **One observer is a single point of blindness.** A `@Scheduled` method would
  have been as available as the control plane; this is not. Two observers would
  double-count into the same counters, and reconciling that needs per-observer
  attribution the schema does not have. Already on the deferred list, and it is a
  direct cost of this decision rather than an unrelated gap.
- **The runtime-profile argument is a design argument, not a measurement.**
  Nothing here has been benchmarked against a JVM alternative, and §3 rule 1
  applies to architecture rationale as much as to feature claims. Reason 3 above
  is why the choice is *plausible*; reasons 1 and 2 are why it is *right*, and
  they hold without a benchmark.

**The good:**

- The boundary is real and is exercised continuously, rather than being a package
  name that a refactor could quietly dissolve.
- `ArchitectureTest` can enforce that only `integrations` makes outbound HTTP
  calls from the control plane, because probing genuinely is not in there.
- The interview answer — policy, catalog and governance stay in a Spring Boot
  modular monolith; the concurrent prober was extracted because it has a
  different failure domain and a different scaling axis — is now a document
  somebody can check rather than a claim.
