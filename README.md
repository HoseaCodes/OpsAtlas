# OpsAtlas

A service operations control plane: it catalogs services, records who owns them,
checks whether they are healthy, and scores them against production-readiness
policy.

> **Status: phase 8 done — a request is explicable end to end.** The catalog
> vertical slice works: a `service.yaml` is submitted through the console or read
> from a watched repository, validated, stored, scored against ten policy rules
> and audited, and the result is browsable. A Go observer probes every declared
> health endpoint and the catalog reports what it found. As of phase 8 **a
> request's correlation ID is its W3C trace ID**, so one string read off a failure
> searches the logs, the traces and the audit log — and a probe, the report it
> produced and the state change that followed are **one trace across two
> processes** ([ADR 0011](docs/adr/0011-correlation-id-is-the-trace-id.md)).
> The [what actually works](#what-actually-works-today) table below is the
> authoritative answer, and it lists what is *not* built as carefully as what
> is.

---

## The idea

A team declares its service in a `service.yaml` committed to its own repository:

```yaml
apiVersion: opsatlas.ambitiousconcepts.io/v1
kind: Service
metadata:
  name: orders-api
  owner: ambitious-concepts
  repository: ambitious-concepts/orders-api
spec:
  tier: 1
  runtime: spring-boot
  environments:
    - name: production
      url: https://orders.example.com
  health:
    readiness: /actuator/health/readiness
    liveness: /actuator/health/liveness
  operations:
    slo:
      availability: 99.9
      window: 30d
    runbook: docs/runbook.md
  journeys:
    - Place an order
```

The control plane reads it, validates it against a versioned schema, stores the
normalized result, and scores it against policy. Nothing about the catalog is
maintained by hand.

The single design constraint worth stating up front: **status is carried by
shape, height and weight, never by hue.** Screenshot any page, convert it to
greyscale, and every status must still be readable. Colour is rationed to four
jobs — selection, focus ring, error-budget fill, open-incident emphasis. See
[`docs/design/tokens.md`](docs/design/tokens.md).

---

## What actually works today

| Capability | State | Verified by |
|---|---|---|
| `service.yaml` v1 JSON Schema | **Real** | `make check-examples` |
| Six example manifests | **Real** | validated on every run |
| Eight invalid fixtures, each with a stated expected JSON Pointer | **Real** | `make check-examples`, which fails if any fixture stops failing correctly |
| Semantic validation — duplicate environment names | **Real** | same |
| Architecture decisions, phases 0–10 | **Real** (written down) | [`docs/adr/`](docs/adr/), [`docs/roadmap.md`](docs/roadmap.md) |
| Design system extracted from the prototype | **Real** (written down) | [`docs/design/tokens.md`](docs/design/tokens.md) |
| Control plane (Java 21 / Spring Boot) | **Real** | `make test` — 288 JVM tests |
| PostgreSQL schema and Flyway migrations | **Real** | `SeedConsistencyIT`, and Hibernate `ddl-auto: validate` refuses to start on drift |
| `POST /api/v1/services` — register from a `service.yaml` | **Real** | `RegistrationApiIT`, plus 13 curl assertions against a running server |
| Safe YAML ingestion — size cap, no alias expansion, no type construction | **Real** | `ManifestValidationTest` — billion-laughs, `!!java` tags, duplicate keys and a 70 KiB body are all refused |
| Located validation errors (JSON Pointer + keyword + sentence) | **Real** | every fixture in `examples/services/invalid/` asserted against `expected.json`, by both the JVM and the Node checker |
| Idempotent re-registration by manifest digest | **Real** | `RegistrationApiIT` — a replay returns 200 and does not move `version` |
| `PUT` with `If-Match` optimistic locking (428 / 412) | **Real** | `RegistrationApiIT` |
| `GET /api/v1/services` and `/{slug}` with cursor pagination | **Real** | `CatalogApiIT`, `RegistrationApiIT` |
| Cross-organization isolation, on **every** org-scoped endpoint | **Real, and verified** | `OrgIsolationIT` — 27 tests covering services, sources, scorecard, audit log, both health endpoints, observation ingestion and deletion; two prove the database itself refuses a cross-org reference |
| That an endpoint cannot be added without covering its isolation | **Real, enforced** | `no_endpoint_escapes_this_test` enumerates every `/api/v1` mapping and fails the build on any that is neither covered nor exempt with a written reason |
| That those isolation tests would catch a real leak | **Real, and verified** | removing the org filter from the audit log, the fleet health rollup and the environment lookup fails exactly three of them and nothing else |
| OpenAPI document generated from the code, drift-checked | **Real** | `make check-openapi` fails the build on any difference |
| Swagger UI over that document, at `/swagger-ui.html` | **Real** | served by springdoc; on by default locally, off when `OPSATLAS_SWAGGER_UI=false` |
| Typed TypeScript client, no hand-written API types | **Real** | `make typecheck` |
| Web console — catalog, detail, scorecard, register by paste, edit and delete | **Real** | 50 component tests, 36 Playwright tests against the real stack |
| A declared dashboard, rendered as a followable link | **Real** | `manifestLinks.test.ts` refuses `javascript:`, `data:`, `http:` and protocol-relative URLs; `observability.spec.ts` drives the real link in a browser |
| Editing a manifest from the console, with `If-Match` | **Real** | `manage.spec.ts` — an invalid edit is refused with the control plane's own JSON Pointer |
| Retiring a service, keeping its entry and history | **Real** | `manage.spec.ts` — `spec.lifecycle: retired` through the edit form, and it stays in the catalog |
| Deleting a service, with the audit trail surviving it | **Real** | cross-org and positive-control coverage in the isolation suite, plus `manage.spec.ts` — `service.deleted` is still readable after the row is gone |
| The deployment label under the wordmark | **Real** | `environment.test.ts` — read from configuration, not the hardcoded "local" it used to be |
| A copyable prompt on `/register`, generated from the schema and the live rules | **Real** | `manifestPrompt.test.ts` walks the real schema and fails if a field is missing from the prompt; a Playwright test reads the clipboard |
| Loading / empty / partial-failure / error / never-observed states | **Real** | `states.test.tsx`, and the detail page settles its two requests independently |
| Status carried by shape and height, never by hue | **Real** | `status.test.tsx` — a pass differs from a fail by shape, a day's availability by height |
| Keyboard navigation with a visible focus ring | **Real, and verified** | `keyboard.spec.ts` — tabs through the catalog in a real browser and fails if anything takes focus without showing it; deleting the `:focus-visible` rule fails it |
| Polling a GitHub repository for its `service.yaml` | **Real** | `SourceSyncIT`, `SourceApiIT`, and a one-off check against the real api.github.com |
| Conditional requests — an unchanged manifest costs a 304 and no ingestion | **Real** | `SourceSyncIT`; verified against real GitHub |
| A failing sync leaves the registered service untouched | **Real, and verified** | `SourceSyncIT` — eight failing-sync cases, each asserting the service survives |
| Read-only by construction — no webhook, no write scope | **Real, enforced** | `ArchitectureTest` — only `integrations` may make outbound HTTP calls |
| Sources page with sync status and per-source explanations | **Real** | 4 component tests, 5 Playwright tests |
| **Go observer** — probes health endpoints, bounded concurrency, jitter, retries | **Real** | 35 Go tests, race-clean; run live against the control plane |
| Probe availability, per environment and per UTC day | **Real** | `ObservationIngestIT` (17 tests), 5 Playwright tests |
| Health meter and 30-day ribbon rendering real measurements | **Real** | `health.spec.ts` — 5 Playwright tests driving a real browser against real observations |
| Idempotent observation ingestion | **Real** | a replayed batch applies nothing; counters are where a double-write is silent |
| Retention — bounded storage, not a table that only grows | **Real, and verified** | `ObservationRetentionIT` (6 tests) calls the scheduled job directly against a fixed clock; disabling the pruning fails four of them |
| The retention window stays wider than the 30-day ribbon | **Real, enforced** | the same test reads both windows from the real configuration — narrowing `OPSATLAS_RETENTION_DAYS` below the ribbon fails the build |
| Prometheus metrics and `/healthz` on the observer | **Real** | served on `:9090`; scraped live — the `observer` target reports `up` and `opsatlas_observer_probes_total` returns series |
| Correlation ID **is** the W3C trace ID | **Real** | `TraceCorrelationIT` (5) — including an inbound `traceparent` being joined rather than replaced |
| A caller's own `X-Correlation-Id` echoed unchanged, and tagged on the span | **Real** | `TraceCorrelationIT` |
| W3C trace context propagated observer → control plane | **Real, and verified live** | one Tempo trace spanning both processes: `observer.pass` with the control plane's `POST /api/v1/observations` as a child |
| Probes deliberately carry **no** trace context | **Real, enforced** | `tracing_test.go` — a probe that sent `traceparent` fails the test; verified by mutation |
| OTLP export is best-effort — a collector that is down costs a request nothing | **Real** | `TraceCorrelationIT` points the exporter at a closed port and asserts requests still succeed |
| Traces, metrics and dashboards locally (Tempo, Prometheus, Grafana) | **Real** | `make up-telemetry`; Prometheus scraping the control plane and the observer |
| Logs shipped to a log store (Loki) | **Not built** | deferred with a reason — see below |
| No credentials in source control | **Real, enforced** | `make check-secrets` — eight credential formats plus tracked `.env`/key files, run first in CI and by `make test`; verified by planting a token and a tracked `.env` and watching it fail. It raises the floor, it is not a proof — [ADR 0012](docs/adr/0012-secret-scanning-is-a-grep.md) says what it misses |
| CI pipeline — six jobs on a clean runner | **Real, and green** | [run #1](https://github.com/HoseaCodes/OpsAtlas/actions/runs/34782671255) — contract fixtures, console, observer, control plane, OpenAPI drift and the browser smoke test all passed on first execution. The smoke job boots PostgreSQL, the control plane and the console and drives Playwright against them |
| Scorecard — ten declaration rules, tier-conditional | **Real** | `PolicyCheckTest` (49), `ScorecardApiIT` (12) |
| **The fleet table below is asserted, not maintained by hand** | **Real, enforced** | `PolicySetIT` registers all six manifests and compares the real scores to the table printed in this README; it also pins the rule-id set beside `PolicyCatalog.VERSION`, so a rule cannot change a verdict without the build noticing |
| `NOT_APPLICABLE` as a real outcome, with a moving denominator | **Real** | a tier 3 service is scored out of 7, not 10 |
| Scorecard + audit written in the registration transaction | **Real, and verified** | `ScorecardApiIT` — a forced mid-registration failure leaves no service, no scorecard and no audit row |
| Audit log with correlation IDs, cursor-paged | **Real** | `ScorecardApiIT` |
| `GET /api/v1/policy/rules` — the rule set and its rationale | **Real** | `ScorecardApiIT` |
| RFC 9457 problem responses with `correlationId` and `violations[]` | **Real** | `CatalogApiIT` |
| Correlation ID accepted, generated, echoed | **Real** | `CatalogApiIT` |
| Module boundaries across all five modules | **Real, enforced** | `ArchitectureTest` — 15 rules |
| **Authentication** — every `/api/v1` endpoint needs a verified RS256 token | **Real, and verified live** | `AuthenticationIT` (9 tests); and against a real Storm-Gate: no token → 401, a real token → 200, a token signed by an unpublished key → 401 |
| **Authorization** — a verified token is not a membership | **Real, and verified live** | a genuine token from a second real Storm-Gate account, not provisioned here, gets 403 `not-provisioned` |
| The organization comes from the caller, not a constant | **Real, and verified** | `OrgIsolationIT` authenticates as a principal in the *other* organization and asserts they reach their catalog and not ours; hardcoding the org in the resolver fails two tests |
| The contract declares how to authenticate | **Real** | `bearerToken` and `serviceCredential` in the OpenAPI document, so Swagger UI can send either |
| Metrics endpoints require a credential | **Real, and verified live** | anonymous scrape 401, credentialled scrape 200, Prometheus target back to `up` with series flowing |
| Swagger UI off by default | **Real** | `OPSATLAS_SWAGGER_UI=true` turns it on; its assets load while the endpoints it calls still require a credential |
| **Container images** for the control plane and console | **Real, and verified live** | multi-stage builds, non-root, code read-only to the process; `make up-app` runs the whole stack containerised and a browser signs in against it |
| First-principal bootstrap from configuration, not from the first caller | **Real** | `PrincipalBootstrapIT` (5 tests), including that a bootstrapped identity can actually get in |
| Org scoping resolved from the authenticated caller | **Real** | `SeedConsistencyIT`, `OrgIsolationIT` — scoping is covered; cross-org *authorization* is not yet |
| Observer authenticating to the control plane | **Real, and verified live** | `ServiceCredentialIT` (9 tests) and 2 Go tests; run live, the observer reports `probed 4, applied 4` with its key and cannot refresh the catalog without it |
| Keys stored hashed, never in the database | **Real** | SHA-256 of a 256-bit key — a fast hash on purpose, and `ServiceCredentialIT` asserts the stored value is not the key |
| Rotating a key revokes the old one immediately | **Real** | `ServiceCredentialIT` — both keys working during a changeover would leave a leaked key live |
| Console sign-in, session and sign-out | **Real, and verified live** | `signin.spec.ts` drives a real browser against a real Storm-Gate: redirect to sign-in, sign in, catalog renders, reload keeps the session, sign out ends it |
| The console holds no credential of its own | **Real** | it forwards the reader's token, so the audit log names the person rather than "the console" |
| The browser suite, signed in | **Real** | 36 Playwright tests against the whole stack — identity provider, control plane, PostgreSQL and the console — with a shared session from a real sign-in |
| OpenAPI document declaring the bearer scheme | **Not done** | the generated contract says nothing about auth, so the typed client does not know a token exists |
| Dependency graph and blast radius | **Not built** | needs trace data |
| Real-user SLO measurement | **Not built** | probe availability is not an SLO — see below |
| Latency percentiles over stored history | **Not built** | span durations are in Tempo; nothing aggregates them, and the rollups deliberately store mean and max only (ADR 0009) |
| Cost attribution | **Not planned for slice one** | — |
| Incidents | **Not planned for slice one** | — |

There is **no mocked data anywhere in this repository.** The example manifests in
`examples/services/` are real test inputs that a real validator really validates.
When the console ships, anything it displays that is not backed by a real
measurement will be labelled in the interface, not only in a comment.

### Things this project does not do, and will not claim to

- **It is not multi-tenant.** The data model is single-tenant, shaped so that
  multi-tenancy is possible later. Every scoped table carries `org_id`, and there
  is exactly one seeded organization behind a stub resolver. See
  [ADR 0003](docs/adr/0003-org-scoping-stub.md).
- **The scorecard scores manifests, not running systems.** The slice-one checks
  read what a team declared. Whether traces actually arrive, whether the image was
  actually scanned, whether coverage is actually above the gate — none of that is
  checked yet, because the integrations that would check it do not exist. See
  [ADR 0004](docs/adr/0004-scorecard-rule-model.md).
- **Nothing has been load-tested or security-tested.** No performance, scale,
  availability or security claim appears anywhere in this repository, because
  none has been measured. It *is* deployed — [`deploy/production/`](deploy/production/),
  [ADR 0014](docs/adr/0014-deployment-is-one-box.md) — on one box, with no
  backups and no redundancy. Reachable is not the same as production-grade, and
  this document will not call it the latter.

---

## Architecture

Six planes. Five of them now have something real in them:

| Plane | What | State |
|---|---|---|
| Presentation | Next.js console | **built** — phase 4 |
| **Control** | catalog, ownership, policy, scorecards | **built** — phases 1–3, 6 |
| Execution | Go observer, probes | **built** — phase 7; reconciliation is not, and needs a deployment concept |
| Telemetry | traces and metrics | **built** — phase 8; logs are on stdout and shipped nowhere |
| Event | durable normalized platform events | not built — phase 9, and §6 says it waits for a demonstrated need |
| Data | the monitored applications themselves | — |

The control plane is a **modular monolith**, not microservices:

```text
com.ambitiousconcepts.opsatlas
├── catalog        services, environments, registration, service.yaml ingestion
├── governance     policies, scorecards, audit events
├── integrations   watched repositories, polled read-only
├── operations     observations rolled up, the health read model
├── identity       organizations, teams, principals
└── shared         errors, pagination, correlation, tracing — depends on nothing
```

Modules talk through public interfaces in their own `api` package. Nothing
reaches into another module's `internal` package, and a test fails the build if
it does — `ArchitectureTest`, 15 rules. The dependencies run one way, and
`operations` serving health through its own endpoints rather than through the
catalog response is what keeps them acyclic
([ADR 0010](docs/adr/0010-health-is-served-separately-from-the-catalog.md)). See
[ADR 0001](docs/adr/0001-modular-monolith-as-one-gradle-module.md).

### Key decisions

| ADR | Decision |
|---|---|
| [0001](docs/adr/0001-modular-monolith-as-one-gradle-module.md) | One Gradle module, boundaries enforced by an ArchUnit test |
| [0002](docs/adr/0002-safe-service-yaml-ingestion.md) | Parse to plain data, validate against schema, *then* bind — never deserialize repository content into arbitrary types |
| [0003](docs/adr/0003-org-scoping-stub.md) | Explicit `orgId` on every query, behind a swappable `PrincipalResolver` |
| [0004](docs/adr/0004-scorecard-rule-model.md) | Typed check beans, versioned rule sets, per-check rows, `NOT_APPLICABLE` as a first-class outcome |
| [0005](docs/adr/0005-openapi-generated-committed-drift-checked.md) | OpenAPI generated from code, committed, and drift-checked in CI |
| [0006](docs/adr/0006-prototype-is-not-committed.md) | The v3 HTML prototype stays out of the repository |
| [0007](docs/adr/0007-service-identity-and-re-registration.md) | Registration is keyed by where the manifest lives; idempotency by content digest; POST never overwrites |
| [0008](docs/adr/0008-polled-sources-not-webhooks.md) | Manifests are polled, never pushed by webhook, so OpsAtlas never holds write access to a monitored repository |
| [0009](docs/adr/0009-observations-are-rolled-up-not-a-time-series.md) | Observations are counters, never a row per probe; storage is bounded and independent of probe frequency |
| [0010](docs/adr/0010-health-is-served-separately-from-the-catalog.md) | Health is served by its own endpoints so `catalog` and `operations` stay acyclic |
| [0011](docs/adr/0011-correlation-id-is-the-trace-id.md) | The correlation ID *is* the trace ID, when there is one — one string searches the logs, the traces and the audit log |
| [0012](docs/adr/0012-secret-scanning-is-a-grep.md) | Secret scanning is a grep run before the push, not a scanner run after it |

Diagrams of what exists — context, modules, the registration sequence, one
request traced across both processes, and the data model — are in
[`docs/architecture/slice-one.md`](docs/architecture/slice-one.md).

### Watching a repository

```bash
# Start watching. Public repositories need no credentials.
curl -X POST localhost:8080/api/v1/sources \
  -H 'Content-Type: application/json' \
  -d '{"repository":"ambitious-concepts/orders-api","ref":"main"}'

# Sync now rather than waiting for the next pass
curl -X POST localhost:8080/api/v1/sources/<id>/sync | jq
```

OpsAtlas reads `service.yaml` from each watched repository every five minutes and
registers what it finds, through exactly the same validation, scoring and audit
path a pasted manifest takes.

**It only ever reads.** No webhook is registered and no write scope is held or
needed — [ADR 0008](docs/adr/0008-polled-sources-not-webhooks.md) explains why
that is worth giving up webhook latency for, and what it costs. The short version:
registering a webhook needs write access to every monitored repository, which is
a large permanent permission bought to remove a few minutes of visible, labelled
staleness.

Two limits worth knowing before pointing this at anything:

- **Rate limit.** Unauthenticated GitHub allows 60 requests an hour per IP, so at
  a five-minute interval roughly **five sources saturate it** — a 304 still counts.
  A contents-read token in `OPSATLAS_GITHUB_TOKEN` raises it to 5,000.
- **A failing sync never erases what is known.** If GitHub is unreachable or a
  manifest stops validating, the registered service stays exactly as it was and
  the source records why it is no longer current. Stale, never blank.

### The observer, and what "healthy" actually means

```bash
make dev            # control plane on :8080
make dev-observer   # probes every declared health endpoint
```

The observer pulls the service list, probes each environment's readiness
endpoint with bounded concurrency, timeouts and jitter, and reports what it
found. The control plane folds those results into counters — never a row per
probe. Eight probes of two environments produce **two state rows and two daily
rows**, and that stays true at any probe frequency
([ADR 0009](docs/adr/0009-observations-are-rolled-up-not-a-time-series.md)).

**`probeAvailability` is not an SLO, and the API says so in its own payload.**
It is the share of probes that succeeded, from one vantage point, against a
health endpoint. A service can serve errors to every real user while its
readiness endpoint answers happily. Nothing in this repository calls it an SLO
measurement.

Three more things it deliberately does not do:

- **No percentiles.** A p95 needs the distribution; a sum, a min and a max are
  not one. You get mean and max, called mean and max.
- **No finer resolution than a day**, beyond the current state. "What did this
  look like at 14:20 last Tuesday" is a metrics system's question.
- **No drift detection.** `CLAUDE.md` §5 lists desired-versus-observed drift as
  the observer's job. It needs a declared version to compare a running one
  against, and no deployment concept exists yet. Calling a failed probe a
  "drift" would be calling an outage something it is not.

### Seeing one request end to end

```bash
make up-telemetry   # collector, Tempo, Prometheus, Grafana
make dev            # control plane on :8080
make dev-observer   # in another terminal
```

| | |
|---|---|
| Grafana | <http://localhost:3001> — Tempo and Prometheus already provisioned |
| Tempo | <http://localhost:3200> |
| Prometheus | <http://localhost:9091> |
| Collector | OTLP on 4317 (gRPC) and 4318 (HTTP) |

**Every response carries one identifier that works everywhere.**

```bash
curl -si localhost:8080/api/v1/services | grep -i x-correlation-id
# x-correlation-id: 9f3c1a2e5b7d4f60a8c9e1b2d3f40516
```

That is a W3C trace ID, and it is the same string in the log line, in the
`audit_event` row, in any problem document the request produced, and in Tempo.
Paste it into Grafana's Tempo search and you get the request. Send your own
`X-Correlation-Id` instead and you get *yours* back unchanged — it is attached to
the span as an attribute, so searching by it still finds the trace.
[ADR 0011](docs/adr/0011-correlation-id-is-the-trace-id.md) has the reasoning and
the costs.

The observer propagates the same context, so a probe and the registration it
causes are one trace rather than three unconnected ones:

```text
opsatlas-observer      observer.pass                      377ms
opsatlas-control-plane └─ http post /api/v1/observations   39ms
```

**Probes deliberately carry no trace context.** A probe reaches a service
OpsAtlas does not own, and injecting our identifiers into somebody else's request
headers — and so into their logs — is not ours to decide. A test asserts a probe
sends no `traceparent`, and it was checked by mutation: giving the prober the
traced transport makes it fail.

Sampling is 100%, which is right at this system's volume — a poll every five
minutes and the occasional human request — and would be wrong at a hundred times
it. Sampling at less would mean the one trace someone goes looking for is the one
that was dropped, and would make the correlation ID unreliable exactly when it
matters.

**Logs are not shipped anywhere.** Loki is in the roadmap and is deliberately not
done: the control plane already logs structured JSON carrying the correlation ID,
and shipping it needs an agent, a retention policy and a second query language to
beat what `docker logs | grep` already does locally. Deferred with that reason
rather than half-built.

Tracing is on by default and costs nothing when no collector is listening — the
exporter batches and drops. `OPSATLAS_TRACING_ENABLED=false` turns it off on both
processes; `OPSATLAS_OTLP_ENDPOINT` points them somewhere else.

### The scorecard, and what it does not check

Registering a service evaluates ten rules and stores the result in the same
transaction as the service row. Registering the six example manifests produces:

```text
service                  tier  score    failing
orders-api               1     10/10    -
pricing-engine           1     8/10     journeys-declared, runbook-linked
billing-worker           2     7/10     environment-urls-declared, liveness-probe-declared, readiness-probe-declared
customer-portal          2     9/10     observability-service-name
identity-bff             1     9/10     dependencies-declared
legacy-report-runner     3     0/7      (7 failing; 3 not applicable at tier 3)
```

Two things in that table are the whole design:

- **The denominator moves.** `legacy-report-runner` is scored out of 7, not 10.
  A tier 3 internal tool has no SLO obligation, so it is marked `NOT_APPLICABLE`
  rather than failed — otherwise every internal tool reads as the worst thing in
  the fleet and the fleet number means nothing.
- **Every failure says why**, in a sentence the owning team can act on. The
  database refuses to store a failure without one.

**These are declaration checks.** They read what a `service.yaml` declares.
Nothing here verifies that a runbook link resolves, that telemetry arrives, or
that a health endpoint answers — the integrations that could do that do not
exist. `GET /api/v1/policy/rules` says so in its own payload, so the console
cannot present them as more than they are.

---

## Stack

The target stack is fixed for the life of the project. What is **wired today** is
marked; the rest is listed so the direction is clear, not to imply it is present.

**Control plane** — Java 21 ✓, Spring Boot 3 ✓, Gradle ✓, Spring Web ✓, Spring
Validation ✓, Spring Data JPA ✓, PostgreSQL ✓, Flyway ✓, Actuator ✓, Springdoc
OpenAPI ✓, Micrometer ✓ (Prometheus registry and the Micrometer Tracing bridge),
OpenTelemetry ✓ (OTLP over HTTP), Testcontainers ✓, ArchUnit ✓. Not added: Spring
Security — deliberately, because the starter would put every endpoint behind a
generated password, which is a security posture the project does not actually
have.

**Console** — Next.js App Router ✓, React ✓, TypeScript in strict mode ✓,
Tailwind ✓, TanStack Query ✓, and a TypeScript client generated from the
backend's OpenAPI document ✓. TanStack Table and Recharts are not used: the
catalog table is a plain table and the health visualisations are hand-drawn
because their shapes are the design (`docs/design/tokens.md`), and neither
library would carry that. Authorization, policy evaluation and scorecard
computation live in the control plane, never in the console.

**Observer** — Go 1.27 ✓, `prometheus/client_golang` ✓ for metrics, and the
OpenTelemetry Go SDK ✓ with `otelhttp` for trace context on outbound calls.
Everything else is standard library: HTTP, JSON, concurrency and scheduling.

**Contract tooling** — Ajv ✓ for schema validation in the workspace,
snakeyaml-engine ✓ and networknt/json-schema-validator ✓ on the JVM side. The
JSON Schema has exactly one copy, in `packages/contracts`; Gradle copies it into
the control-plane jar so both sides validate against identical bytes.

---

## Local setup

### Requirements today

| Tool | Version | Needed for |
|---|---|---|
| Node | ≥ 20.11 | the contract checks |
| pnpm | 10.x | the contract checks |
| GNU Make | 3.81+ | the entry point |
| Docker | any recent | PostgreSQL, the integration tests, and — optionally — the `telemetry` compose profile |
| Java | **none required** | Gradle provisions a Temurin 21 toolchain itself |
| Go | 1.27+ | the observer (`apps/observer`) |

You do not need a JDK installed. The build declares a Java 21 toolchain and
Gradle downloads one on first use — verified on a machine with only JDK 17 and
25 present.

### Running it

```bash
make install    # install workspace dependencies
make up         # start PostgreSQL and wait until it is accepting connections
make up-telemetry  # optional: collector, Tempo, Prometheus, Grafana
make dev        # database + control plane on :8080
make dev-web    # in another terminal: the console on :3000
make test       # everything that needs no running server
make test-all   # the above, plus the browser smoke test
make            # list the targets that exist
```

Then open <http://localhost:3000>. The catalog is empty until you register
something — `examples/services/` has six manifests to paste in.

#### Writing a manifest for a service that has none

`/register` carries a prompt you can copy into whichever assistant you use, and
paste the YAML it returns back into the form. The prompt is generated per request
from the versioned JSON Schema and from `GET /api/v1/policy/rules`, so it states
the field constraints and the ten scorecard rules as they are right now rather
than as they were when somebody last wrote them down. It also tells the
assistant to omit what it cannot know instead of inventing a URL — a catalog is
believed, so a plausible wrong value is worse than an absent one.

#### Browsing the API

With the control plane running, the API is browsable at
<http://localhost:8080/swagger-ui.html>, which reads the same generated document
the TypeScript client is built from. The document itself is at
<http://localhost:8080/v3/api-docs>, and its committed copy is
`packages/contracts/openapi/control-plane.json`.

The UI is a local-development convenience. Nothing authenticates a request yet,
so wherever the control plane is genuinely exposed, turn it off with
`OPSATLAS_SWAGGER_UI=false`; `/v3/api-docs` is unaffected either way.

Then:

```bash
# Register a service from its manifest. The request body IS the file.
curl -X POST localhost:8080/api/v1/services \
  -H 'Content-Type: application/yaml' \
  --data-binary @examples/services/orders-api.yaml

# Read it back
curl -s localhost:8080/api/v1/services/orders-api | jq

# List, with cursor pagination
curl -s 'localhost:8080/api/v1/services?limit=10' | jq
```

Try registering a deliberately broken one to see what an error looks like:

```bash
curl -s -X POST localhost:8080/api/v1/services \
  -H 'Content-Type: application/yaml' \
  --data-binary @examples/services/invalid/unknown-property.yaml | jq
```

```json
{
  "type": "https://opsatlas.ambitiousconcepts.io/problems/validation-failed",
  "status": 422,
  "correlationId": "…",
  "violations": [{
    "pointer": "/spec",
    "keyword": "additionalProperties",
    "message": "An unrecognised property was found at /spec: property 'healthz' is not defined in the schema… Check the spelling; unknown keys are rejected rather than ignored so a typo cannot silently do nothing."
  }]
}
```

`make check-examples` validates the six example manifests, then asserts that each
of the eight invalid fixtures fails at exactly the JSON Pointer recorded in
`examples/services/invalid/expected.json`. It fails if a good manifest breaks
*and* if a bad manifest stops being bad — a checker that cannot fail is worthless,
and both directions are exercised.

---

## Deploying it

One virtual machine running seven containers, fronted by Caddy, which terminates
TLS and renews its own certificates. **One public hostname**, for the console:
it calls the control plane and the identity provider from the server over the
compose network, so neither needs a route in from outside. Not Kubernetes: the topology is five coupled
containers on one host, and a cluster to run a control plane means operating a
control plane to run a control plane. The reasoning, and what it costs, is
[ADR 0014](docs/adr/0014-deployment-is-one-box.md); the steps are in
[`deploy/production/README.md`](deploy/production/README.md).

What makes it different from the local compose file is worth stating, because
the two look alike and are not:

| | Local | Deployment |
|---|---|---|
| Published ports | PostgreSQL 5432, MongoDB 27017, and every app port | 80 and 443 publicly; the issuer on `127.0.0.1` for bootstrap |
| MongoDB auth | none | required |
| Secrets | documented development defaults | no defaults; a missing one stops the stack |
| Images | built from source | pulled by commit SHA or release tag, never `latest` |
| TLS | none | Caddy, with automatic renewal |

**It is running.** Deployed to a DigitalOcean droplet on 2026-09-15 and serving
at `https://opsatlas.hoseacodes.com` — seven containers healthy, a Let's Encrypt
certificate over `tls-alpn-01`, HTTP redirecting to HTTPS, and the API answering
200 for a provisioned caller and 401 without a token.

Still true, and worth saying in the same breath: **there are no backups**, it is
one box with no redundancy and no zero-downtime deploy, and nothing has been
load-tested or security-tested. Deploying something does not make it production-
grade; it makes it reachable.

---

## Repository layout

Only directories with real contents exist. The full target layout is in
[`docs/roadmap.md`](docs/roadmap.md).

```text
OpsAtlas/
├── apps/control-plane/     Java 21 / Spring Boot 3 modular monolith
│   └── Dockerfile          multi-stage: JDK builds the jar, JRE runs it
│   └── src/main/java/com/ambitiousconcepts/opsatlas/
│       ├── shared/         errors, pagination, correlation — depends on nothing
│       ├── identity/       the org-scoping stub
│       ├── catalog/        services, environments, service.yaml ingestion
│       ├── governance/     policy rules, scorecards, audit
│       ├── integrations/   polling watched repositories (read-only)
│       └── operations/     observations, rolled up; health read model
├── apps/observer/          Go — probes health endpoints, reports back
├── apps/web/Dockerfile     multi-stage: Next standalone output, no dev deps
├── apps/web/               Next.js console — catalog, scorecard, register
├── packages/contracts/     service.yaml JSON Schema and the fixture validator
├── deploy/compose/         local PostgreSQL; collector, Tempo, Prometheus,
│                           Grafana behind a compose profile
├── scripts/                check-secrets.sh, and deliberately nothing else
├── examples/services/      six valid manifests, eight invalid fixtures
└── docs/
    ├── adr/                0001–0012
    ├── architecture/       what exists, in Mermaid
    ├── design/tokens.md    the design system, extracted from the prototype
    └── roadmap.md          phases, target layout, deferred decisions
```

---

## Roadmap

Slice one is the catalog vertical slice, and nothing else.

| Phase | What | State |
|---|---|---|
| 0 | Foundation, `service.yaml` contract, fixtures | **complete** |
| 1 | Control plane skeleton, PostgreSQL, empty catalog endpoint | **complete** — `make dev` works |
| 2 | Ingestion and registration | **complete** |
| 3 | Scorecard and audit | **complete** |
| 4 | OpenAPI client and the web console | **complete** |
| 5 | CI and documentation close-out | **complete** |
| 6 | GitHub sync — poll watched repositories | **complete** |
| 7 | The Go observer, and everything health-shaped | **complete** |
| 8 | OpenTelemetry — traces across both processes, Tempo, Prometheus, Grafana | **complete** |
| 9 | Transactional outbox, platform events, Redis read models | not started |
| 10 | Terraform, Kubernetes, Helm, k6 | not started |

Details, what each remaining phase is waiting on, and the decisions deferred
rather than forgotten are in [`docs/roadmap.md`](docs/roadmap.md).
