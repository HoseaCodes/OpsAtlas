# Roadmap and target layout

This file holds two things `CLAUDE.md` §3 rule 3 keeps out of the repository
itself: the **full target directory layout**, and the **phase list**. A directory
appears in the tree on disk only when it has real contents; until then it lives
here.

---

## Target repository layout

**✓ = exists on disk today.** Everything unmarked is recorded here and nowhere
else, per §3 rule 3 — the phase it arrives in is noted beside it.

```text
OpsAtlas/
├── Makefile                       ✓ exists
├── README.md                      ✓ exists
├── settings.gradle.kts            ✓ exists
├── gradle/libs.versions.toml      ✓ exists
├── apps/
│   ├── control-plane/             ✓ Java 21 / Spring Boot 3 modular monolith
│   │   └── src/main/java/com/ambitiousconcepts/opsatlas/
│   │       ├── shared/            ✓ errors, pagination, correlation
│   │       ├── identity/          ✓ the org-scoping stub
│   │       ├── catalog/           ✓ services, environments, service.yaml ingestion
│   │       ├── governance/        ✓ policy rules, scorecards, audit
│   │       ├── operations/        ✓ observations, rolled up; health read model
│   │       └── integrations/      ✓ watched repositories, polled read-only
│   ├── web/                       ✓ Next.js App Router console
│   └── observer/                  ✓ Go prober
├── packages/
│   ├── contracts/                 ✓ service.yaml schema, OpenAPI document, TS client
│   └── ui/                          not planned; extract only if a second consumer appears
├── deploy/
│   ├── compose/                   ✓ local PostgreSQL
│   │   └── telemetry/             ✓ collector, Tempo, Prometheus, Grafana
│   ├── production/                ✓ one box, compose behind Caddy — ADR 0014
│   ├── k8s/                         phase 12
│   └── helm/                        phase 12
├── examples/
│   └── services/                  ✓ example and fixture manifests
├── scripts/                       ✓ check-secrets.sh — a layout deviation, ADR 0012
├── docs/
│   ├── adr/                       ✓ 0001-0015
│   ├── architecture/              ✓ slice-one.md
│   ├── design/                    ✓ tokens.md
│   └── roadmap.md                 ✓ this file
├── terraform/                       phase 12
└── .github/workflows/             ✓ ci.yml, publish.yml
```

`packages/ui` is listed as *not planned* rather than as a later phase. A shared
component package with one consumer is a directory pretending to be a decision.

---

## Slice one — the catalog vertical slice

`CLAUDE.md` §14: slice one is the catalog vertical slice only. Each phase below
ends in something runnable and independently verifiable.

### Phase 0 — Foundation, contract, fixtures ✓ **complete**

Workspace, Makefile, the versioned `service.yaml` JSON Schema, six valid example
manifests, eight invalid fixtures with stated expected violations, ADRs 0001–0006,
`docs/design/tokens.md`.

**Verified by:** `make check-examples`. Runs with no JVM, no database, no network.

### Phase 1 — Control plane skeleton and database ✓ **complete** — `make dev` works

Gradle build with an auto-provisioned Java 21 toolchain, Spring Boot application,
compose PostgreSQL, Flyway `V1` plus the seeded organization, Actuator,
correlation-ID filter, org-context filter, RFC 9457 problem responses, cursor
pagination, `GET /api/v1/services` returning an empty page, `ArchitectureTest`.

**Verified by:** 31 tests, all passing — `CatalogApiIT` (10) against a
Testcontainers PostgreSQL, `ArchitectureTest` (6), `CursorTest` (9),
`ViolationTest` (4), `SeedConsistencyIT` (2). Additionally verified by curl
against a running server on the compose database: the empty page body, a
generated and an echoed correlation ID, the problem document for a bad cursor,
and the named-parameter error for an out-of-range limit.

**Gap closed in phase 2:** cross-organization isolation, which phase 1 left
unverified, is now covered by `OrgIsolationIT`.

### Phase 2 — Ingestion and registration ✓ **complete**

The ADR 0002 pipeline: size cap, snakeyaml-engine load, schema validation,
violation mapping, binding, semantic checks. `POST /api/v1/services`,
`GET /api/v1/services/{slug}`, `PUT /api/v1/services/{slug}` with `If-Match`.
Identity and re-registration semantics are ADR 0007.

**Verified by:** 98 JVM tests. Every fixture in `examples/services/` registers;
every fixture in `examples/services/invalid/` produces exactly the violations
`expected.json` states; a 70 KiB body is refused before parsing; a billion-laughs
document, a `!!java` type tag and a duplicate key are all refused by the loader.
`OrgIsolationIT` covers every read and write path against a planted second
organization. Additionally verified by 13 curl assertions against a running
server: 201 / 200 replay / 409 conflict / 428 / 412 / 200 update / 422, and
cursor paging visiting six services exactly once.

**Deliberately not built here:** the `PUT` addresses services by slug, not UUID,
because renames are refused (ADR 0007). Moving a `service.yaml` to a different
path in the same repository registers a second service rather than moving the
first; nothing detects that, and it is worth revisiting when the GitHub
integration can see file renames in a diff.

### Phase 3 — Scorecard and audit ✓ **complete**

`governance` module, the declaration checks from ADR 0004 (ten at the time, eleven
since ADR 0016), `PolicyCatalog`,
evaluation inside the registration transaction, `policy_result`,
`policy_result_check`, `audit_event`, `GET /api/v1/services/{slug}/scorecard`,
`GET /api/v1/policy/rules`, `GET /api/v1/audit-events`.

**Verified by:** 162 JVM tests. `PolicyCheckTest` (49) asserts that every rule
passes a fully declared manifest and fails a bare one — a rule that cannot fail
is padding — and that every failure is a complete sentence. `ScorecardApiIT` (12)
covers scoring over HTTP, the shrinking tier 3 denominator, re-scoring on update,
and the transactional guarantee: a forced mid-registration failure leaves no
service row, no policy result and no audit event. Additionally verified against a
running server: six example manifests registered and scored, the fleet table
reproduced in the README, and audit entries carrying the correlation ID of the
request that caused them.

**Deliberately not built here:** policy exceptions. They need an approver, an
approver needs identity, and identity is stubbed (ADR 0003, ADR 0004).

### Phase 4 — Contracts and console ✓ **complete**

`make openapi`, generated TypeScript types, the typed fetch client. Next.js
console: catalog list with search, tier filter and cursor paging; detail with
Overview, Scorecard and service.yaml tabs; register-by-paste with inline
violations. Design tokens from `docs/design/tokens.md`, dark by default.
Loading, empty, partial-failure, error and never-observed states.

Navigation contains **Catalog and nothing else.**

**Verified by:** 16 component tests and 8 Playwright tests driving a real
browser against a real console, control plane and PostgreSQL. Queries are by
role and accessible name, so they fail if the page stops being navigable by a
screen reader. The honesty properties are asserted directly: "Never observed"
appears, the declaration-only notice appears, the navigation has exactly one
link, and 375px does not scroll horizontally.

**Known limitation:** catalog search and tier filtering are applied in the
console, over the current page only, because the control plane has no search
endpoint. The empty state says so rather than implying a fleet-wide search.
Server-side filtering is the obvious next API change.

### Phase 7 — The observer ✓ **complete**

A Go binary that pulls the service list, probes every declared health endpoint
with bounded concurrency, jitter and retries, and reports results back in
idempotent batches. An `operations` module folds those into counters per
environment and per UTC day (ADR 0009), and serves them through their own
endpoints rather than through the catalog (ADR 0010).

**Verified by:** 26 Go tests, race-clean, driving real HTTP servers rather than
mocked transports - concurrency bounds, ordering, redirect refusal, retry
policy, idempotency key stability. 238 JVM tests including `ObservationIngestIT`
(23), which holds time still with a fixed clock. 19 Playwright tests, five of
them on health. Additionally verified by running the observer live against the
control plane: two environments, one real and one with nothing listening,
produced HEALTHY at 100% and DOWN at 0% with "connection refused", and eight
probes became two state rows plus two daily rows.

**Gap closed after phase 8:** phase 7's endpoints — `POST /api/v1/observations`,
`GET /api/v1/health` and `GET /api/v1/services/{slug}/health` — were added
without extending `OrgIsolationIT`, and so had unverified isolation for two
phases. So had the scorecard and audit-log endpoints from phase 3 and
`/sources/{id}/enable` from phase 6. All six are covered now, and the test
carries positive controls (a service of ours registered and observed in the same
request) so that a leak check cannot pass against an endpoint that answers
nobody. Checked by mutation: removing the org filter from the audit log, the
fleet health rollup and the environment lookup fails exactly three tests.

The lesson worth keeping is that the rule "extend `OrgIsolationIT` whenever an
endpoint is added" was written down in `CLAUDE.md` and still missed three times,
because nothing failed when it was ignored. So it is now a test:
`no_endpoint_escapes_this_test` enumerates every `/api/v1` mapping from the
handler mapping and fails unless each appears in a `COVERED` set or in
`NOT_TENANT_SCOPED` with a written reason. Adding an endpoint without covering
its isolation now breaks the build. Verified by removing an entry: the failure
names the endpoint and says what to do about it.

**Second gap closed after phase 8:** the retention job had no test. It is
`@Scheduled`, so nothing in the suite ever ran it, and ADR 0009's claim that
storage is bounded is only true because it does — `environment_day` otherwise
accumulates a row per environment per day forever. `ObservationRetentionIT` now
calls it directly against a fixed clock and pins six properties, including the
one that is easy to get silently wrong: the retention window has to stay wider
than the window the console draws, or a prune landing between two requests puts
a gap at the ribbon's oldest edge that reads as "never probed" rather than "no
longer kept". Both windows are read from the real configuration, so narrowing
`OPSATLAS_RETENTION_DAYS` below the ribbon fails the build. Verified by mutation
in both directions: disabling the pruning fails four of the six, and narrowing
the production default fails the ribbon test.

Writing it also found an off-by-one in this file's own understanding rather than
in the code: a window of N days means today and the N-1 days before it, so a
30-day ribbon's oldest day is `today - 29`. The first draft of the test planted
`today - 30`, got one day back instead of two, and was wrong.

**Third gap closed after phase 8:** `CLAUDE.md` §10 requires keyboard navigation
with visible focus. The stylesheet had a `:focus-visible` rule and nothing
asserted it, which is the failure mode worth naming: `outline: none` in a later
change breaks it silently and is invisible to everyone using a mouse.
`e2e/keyboard.spec.ts` now tabs through the catalog in a real browser and fails
if any element takes focus without showing it, checks that the nav, the filters
and the service list are reachable in that order, and opens a service and a tab
with Enter alone. Verified by mutation: replacing the rule with `outline: none`
fails the two visibility tests and correctly leaves the two reachability tests
passing.

It has to be a browser test. `:focus-visible` does not match a scripted
`.focus()` — the first draft of the last test called it, and would have been
asserting the browser's mouse behaviour while claiming to test keyboard support.

**Fourth gap closed after phase 8:** test isolation. The Testcontainers
instance is shared by every integration test, and seven classes each carried
their own hand-written list of `delete from` statements — in different orders,
covering different tables. Every list was a chance to miss one, and twice a new
class did, leaking rows into `CatalogApiIT`'s empty-catalog assertion. The reset
now lives once in `PostgresTestBase`, discovers its tables from `pg_tables` so a
future migration's table is covered without anybody remembering, and preserves
whatever Flyway seeded rather than hardcoding the organization id — which is
deliberately package-private in `identity.internal`, and widening production
visibility to let a test know it would be the wrong trade.

Deliberately **not** `@Transactional` with a rollback, which is the usual answer.
Several tests here depend on real commit semantics — `REQUIRES_NEW` in the source
store, optimistic locking, and idempotent ingestion reading back what it
committed — and wrapping them in a rolled-back transaction would quietly change
what they prove.

Verified in both directions: all seven hand-written lists removed and the suite
still passes 257, and disabling the shared reset fails 96 tests across 13
classes. Runtime is unchanged at roughly half a minute.

**Fifth gap closed after phase 8:** `PolicyCatalog.VERSION`. `CLAUDE.md` said to
bump it whenever a rule changes, because it is what lets a score from last month
still be read as what it meant then — and nothing enforced it. A rule could be
added, every scorecard could start meaning something different, and every stored
`policy_set_version` would still claim otherwise.

`PolicySetIT` closes both halves. The rule-id set is pinned beside the version it
stands for, which catches a rule appearing or disappearing. The README's fleet
table — the most falsifiable claim in that document, six services with exact
scores and exact failing rules — is asserted against what the scorer actually
produces, which catches a rule quietly changing its mind about a manifest.

It found the table already wrong: three failing checks were named
`environment-urls`, `liveness-probe` and `readiness-probe`, and the real ids are
`environment-urls-declared`, `liveness-probe-declared` and
`readiness-probe-declared`. Every number in the table was right; the ids a reader
would have searched for did not exist.

**And it found something worse than the thing it was written for.** The first
mutation check appeared to pass in under a second: Gradle had the test task
up-to-date, because `README.md` was not a declared input. A test that reads a
file at runtime is not re-run when that file changes — so the test existed,
looked green, and was skipped by the exact edit it was written to catch. CI never
hit it, having no cache to be up to date against, which is the worst shape for
this kind of bug: correct in the place nobody watches, wrong in the place
everybody works. `README.md`, `examples/services` and
`packages/contracts/schemas` are declared inputs now.

**A note on the keyboard tests, added in the same stretch.** They press keys and
expect things to happen, which runs into something `catalog.spec.ts` already had
written down: an interaction landing between the server-rendered markup
appearing and the App Router hydrating is swallowed. `waitForLoadState("networkidle")`
does not close that window — network idle is not hydration — and a helper named
`ready()` that waits for the wrong thing is worse than no helper, because it
reads as a guarantee. Where a key press must produce a navigation, the
press-and-assert pair is retried with `toPass`, as the existing test does.

**One assertion was dropped rather than stabilised.** The tab test also asserted
that Enter on the Scorecard tab changes the URL, and failed about one run in
thirty — not the brief swallow the retries handle, but a state where the retry
exhausted a full fifteen seconds with the URL never changing. The cause is not
understood. It was removed because it was duplicate coverage: `catalog.spec.ts`
already asserts that activating that exact tab changes the URL, and the test
above it already asserts that Enter opens a focused link. What the tab test
uniquely covers — that the tab is reachable by tabbing and shows its focus — is
unaffected and still asserted. A longer timeout would have hidden the lead
instead; the comment in the test says so, so nobody re-adds it believing it was
merely slow.

Chasing that produced a wrong conclusion on the way, worth recording because it
was stated before it was checked: a mouse click on the tab did not navigate
either, which looked like a product bug. It is not one — the same click works
once the router has attached, and a plain link to the identical URL works
throughout.

**Decided: option 2, a published image.** Storm-Gate's CI now builds a
multi-architecture image and pushes it to `ghcr.io/hoseacodes/storm-gate`, and
this project's compose pulls it. `make up` starts PostgreSQL, MongoDB and the
identity provider together — not behind a profile, because since ADR 0013 a
stack without an issuer is a control plane nobody can talk to.

Getting there meant fixing what the image would have shipped, which was the
point of looking:

- **22 production vulnerabilities, including 2 critical, down to 2 moderate.**
  One of the highs was `jws` — the library on the token signing and verification
  path this project is about to trust. The last critical needed a major bcrypt
  bump, so the compatibility question was answered rather than assumed: a hash
  written by bcrypt 5, read out of the running database, still verifies under
  bcrypt 6, and a wrong password is still rejected. No forced password resets.
- **The Dockerfile pinned Node 18**, which left support in April 2025, while the
  project's own `.nvmrc` and README said Node 20. An authentication service on
  an end-of-life runtime ships whatever that runtime stops receiving.
- **The build toolchain shipped in the final image.** Multi-stage now, so gcc,
  make and python3 are not present in something that runs on the internet.
- **The application could rewrite its own source.** The original chowned all of
  `/app` to the runtime user; the replacement grants only `logs/`. Found because
  a comment claiming otherwise was written first and then tested.

The workflow gates on the suite and on `npm audit --omit=dev --audit-level=high`,
so a known-vulnerable production dependency stops a publish rather than becoming
one.

**Done, and the suite is green again.** The image publishes, `make up` starts the
issuer from it, and `make first-user` closes the bootstrap gap in one command:
it creates the local account at the issuer, reads back the subject the issuer
assigned, and records it so `make dev` provisions it on startup. The browser
suite signs in once in a `setup` project and shares that session — 26 tests
passing against the whole stack.

Two things that went wrong on the way are worth keeping, because both looked
like product bugs and neither was. The publish failed twice: once on tag casing,
and once because the step read `.target."docker-metadata-action".tags` from
`DOCKER_METADATA_OUTPUT_JSON` — that is the *bake file* layout, and the variable
holds the flat `{"tags":[...]}` one, so correctly computed tags were thrown away
by the filter meant to read them. And a health assertion started failing because
the observer, doing its job against the same database, had probed the service the
test asserts is at 100%. That spec now registers a service of its own per run
rather than assuming nothing else has ever touched one.

**The superseded question.** The browser suite needs an identity provider,
because the console it drives needs somebody to sign in as. `make e2e` starts
PostgreSQL, the control plane and the console; it does not start Storm-Gate, and
Storm-Gate needs MongoDB of its own. Three ways out, none free:

1. **Add Storm-Gate and MongoDB to compose.** Honest, and it makes `make e2e`
   exercise the real thing. The wrinkle is that Storm-Gate lives in a different
   repository, so compose would either build from a path outside this project —
   which only works on a machine that has both — or pull a published image,
   which does not exist yet.
2. **Publish a Storm-Gate image** and depend on the tag. Cleanest for CI, and it
   makes this project's tests depend on another project's release cadence.
3. **A stub issuer for tests only.** No external dependency and fast, but it
   proves the console agrees with a fake, and CLAUDE.md §3 rule 2 would require
   labelling it as mocked wherever it appears.

Worth choosing deliberately rather than by whichever is easiest at the time: it
decides what "the tests pass" means from here on.

**Deferred, with reasons:**

- **Drift detection.** CLAUDE.md §5 lists it as the observer's job. It needs a
  declared version to compare a running one against, and there is no deployment
  concept yet. A failed probe is an outage, not a drift.
- **Percentiles.** Deliberately impossible from what is stored (ADR 0009), and
  belong with the telemetry pipeline.
- **Multiple vantage points.** One observer means one network position, so
  "available" means "available from here".

### Phase 6 — GitHub sync ✓ **complete**

`integrations` module, `source` table, a read-only GitHub Contents reader with
conditional requests, a five-minute poller, `POST/GET/DELETE /api/v1/sources`
plus sync, enable and disable, and a console page. ADR 0008 records why this
polls rather than registering webhooks.

**Verified by:** 219 JVM tests. `SourceSyncIT` covers the happy path and seven
failure modes, each asserting the registered service survives; `SourceRefTest`
covers traversal, injection and malformed-repository refusals; `OrgIsolationIT`
grew by seven tests covering every new endpoint. 14 Playwright tests against the
real stack, five of them on the sources page. Additionally verified once against
the real api.github.com, unauthenticated: content fetched, ETag returned, a
conditional refetch answered 304, an unknown path answered 404.

**Deferred, with reasons, in ADR 0008:** the GitHub App, and backoff on
repeatedly failing sources (`consecutive_failures` is recorded but nothing reads
it yet).

### Phase 5 — CI and documentation close-out ✓ **complete**

`.github/workflows/ci.yml` with five jobs: contract fixtures, control-plane
tests, the OpenAPI drift check, the console build and component tests, and the
browser smoke test. README table finalised, `docs/architecture/slice-one.md`
written, `CLAUDE.md` §2 rewritten.

**Verified after phase 8, and not before.** For the life of the project this
entry read "the workflow has never run", and the reason it gave — no remote —
had quietly stopped being true. The repository was on GitHub, public, with the
workflow registered and active, and GitHub reported **zero runs**: `on: push`
named `branches: [master]` and no branch by that name existed. A pipeline that
looks present and fires on nothing is worse than no pipeline, because the
badge-shaped absence reads as coverage.

Resolved by creating `master` and making it the default branch, so the trigger
now names the branch work lands on. **Run #1 passed — all six jobs**, on commit
`16b3435`: contract fixtures 23s, console 43s, observer 84s, control plane 119s,
OpenAPI drift 119s, browser smoke 190s.

Three things had never been exercised anywhere but a developer's machine, and
all three worked on a clean runner first time: the Java 21 toolchain provisioning
itself through the foojay resolver with no JDK installed, Testcontainers starting
PostgreSQL inside a GitHub runner, and the smoke job booting the control plane,
building and serving the console and driving Playwright against both.

**The trap is still armed, in a smaller way.** `slice-one-foundation` still
exists, and a push to it triggers nothing; only `master` and pull requests into
it do. The two branches point at the same commit today. Work landing on the
wrong one produces a green history that describes nothing.

### Phase 8 — Telemetry ✓ **complete**

W3C trace context on both processes, OTLP export to a collector, and Tempo,
Prometheus and Grafana behind a compose profile so the default `make up` stays a
database and nothing else. The decision that shapes it is ADR 0011: **a request's
correlation ID is its trace ID**, so the string a failure hands you searches the
logs, the traces and the audit log rather than requiring a join on timestamps.

**Verified by:** 243 JVM tests including `TraceCorrelationIT` (5) — a traced
request's correlation ID is a trace ID, an inbound `traceparent` is joined rather
than replaced, a caller's own ID is echoed unchanged, an error response carries
the same identifier, and an unreachable collector neither fails nor slows a
request. 33 Go tests, race-clean, three of them new and covering the propagator,
`traceparent` on control-plane calls, and its deliberate absence on probes — that
last one checked by mutation, since a test asserting a header is missing passes
for free if the mechanism is never wired. Additionally verified live: a
correlation ID read off a response found its trace in Tempo, one trace spanned
both processes (`observer.pass` → the control plane's `POST
/api/v1/observations`), and Prometheus scraped both targets `up` with
`opsatlas_observer_probes_total` returning series.

**Three things this phase got wrong first, recorded because they all fail
silently:**

- **Filter ordering.** `CorrelationIdFilter` at `HIGHEST_PRECEDENCE` ran *before*
  the observation filter that creates the span, so there was no current span to
  read and every correlation ID quietly fell back to a generated UUID — which
  looks exactly like it working. It runs at `+5` now, with the reason in the
  class comment.
- **A configuration class that never loaded.** `management.tracing.propagation.type=w3c`
  was ignored and the propagator was the no-op one. Two things were needed: a
  `TextMapPropagator` bean rather than a `ContextPropagators` one, and removing a
  `@ConditionalOnProperty` that was stopping the class from loading at all.
- **The trace ID overriding a caller's own correlation ID.** Two existing tests
  caught it. §9 says the ID is accepted from the header and echoed, and a caller
  who sends one and gets a different one back cannot correlate anything. The
  amendment is recorded in ADR 0011 rather than quietly applied.

**Deferred, with reasons:**

- **Loki.** The control plane already logs structured JSON carrying the
  correlation ID. Shipping it needs an agent, a retention policy and a second
  query language, for value `docker logs | grep` already provides at this scale.
- **Sampling below 100%.** Right at a poll every five minutes, wrong at a hundred
  times the volume. Recorded in ADR 0011 as the first thing to revisit.
- **Percentiles from stored history.** Span durations are in Tempo, but nothing
  aggregates them and the rollups deliberately keep mean and max only (ADR 0009).
- **Metrics from the observer into the control plane.** Prometheus scrapes the
  observer directly; teaching the control plane to read Prometheus would make a
  monitoring system a dependency of the catalog, which is a bigger decision than
  this phase needed.

---

## After slice one

Not implemented, not scaffolded, not configured. `CLAUDE.md` §14: if one of these
seems necessary to finish the current slice, stop and say so rather than quietly
expanding scope.

| Phase | What | Why it waits |
|---|---|---|
| ~~6~~ | ~~GitHub sync~~ — **done**, by polling rather than webhooks (ADR 0008). A GitHub App is still deferred: it needs a registered application, a private key, an installation flow and somewhere to keep per-installation tokens, and identity is still a stub. | |
| ~~7~~ | ~~Go observer~~ — **done**. Drift detection is deferred: it needs a deployment concept to compare a declared version against a running one. | |
| ~~8~~ | ~~OpenTelemetry, Prometheus, Tempo, Grafana~~ — **done**. Loki is still deferred: structured JSON on stdout already carries the correlation ID, and shipping it needs an agent, a retention policy and a second query language. | |
| ~~9~~ | ~~**Authentication and authorization**~~ (ADR 0013) — **done**. Every `/api/v1` endpoint needs a verified RS256 token, and a verified token is not a membership: a `principal` row keyed on issuer **and** subject is required, or the answer is 403. The stub resolver is deleted. The four items this row listed as open are all closed — the observer carries its own key on `X-OpsAtlas-Key`, `OrgIsolationIT` covers authorization across organizations and `no_endpoint_escapes_this_test` keeps it covering every endpoint, Prometheus scrapes with a credential, and Swagger UI is off unless asked for | |
| ~~10~~ | ~~Packaging~~ — **done**. Multi-stage Dockerfiles for the control plane (JDK builds, JRE runs) and the console (Next standalone output), both non-root with their code read-only to the process. `make up-app` runs the stack containerised; verified by signing in through a real browser against it. Images are built from this repository rather than pulled — there is no published OpsAtlas image, and naming one that does not exist would be a promise the compose file cannot keep. | |
| 11 | Transactional outbox, platform events, Redis read models | Only once there is a demonstrated need (§6). Nothing today has a second consumer of registration events, no observer needs coordinating, and no read model is slow |
| **12** | **Deployment** — one box, compose behind Caddy (ADR 0014) | **Deployed.** Running on a DigitalOcean droplet since 2026-09-15 at `https://opsatlas.hoseacodes.com`: seven containers, images pulled from GHCR by tag, TLS from Let's Encrypt, one public hostname. Doing it found three faults no local check could have — an impossible first start, a console image permanently unhealthy while working, and a `latest` tag the workflow claimed not to produce. **Still open:** no backups, no zero-downtime deploy, no redundancy, and the telemetry stack is not deployed. Terraform, Kubernetes, Helm and k6 still wait, but no longer circularly — there is now something for the IaC to describe |
| **13** | **Deployments and drift** — what version is running where | **Next, and the one that unblocks the others.** The catalog knows what a service declares and whether its health endpoint answers. It does not know what is *running*, and that single gap is why drift detection was deferred in phase 7, why the prototype's entire Promotion block has nothing behind it, and why an incident would have no "what changed" to point at. Detailed below |
| **14** | **Incidents** | Needs 13. Recording an incident with no deployment history is a worse spreadsheet; the value is the correlation. Detailed below |

### Phase 13 — Deployments and drift

The first phase since the observer that adds a new fact about the world rather
than a new view of an existing one.

**What it adds.** A `deployment` table under `environment`, and the
`DesiredState` that §7 has listed since the beginning and nothing has ever
written: the version an environment is *supposed* to be running. A deployment
row is version, commit SHA, who or what deployed it, and when. "In place for 9h"
falls out of `deployed_at` and needs no column.

Storage is bounded by deploy frequency rather than probe frequency, so ADR 0009's
rule does not apply and these are rows, not counters. They are platform metadata,
which is exactly what §6 says PostgreSQL is the system of record for.

**Where the declared version comes from.** An endpoint the deploying pipeline
calls — `POST /api/v1/services/{slug}/environments/{name}/deployments`, with an
idempotency key per §9, because a retried deploy notification must not become two
deployments. The thing that deploys is the only thing that reliably knows it
happened. OpsAtlas does not watch a registry and does not infer.

**Where the observed version comes from, and the hard part.** The observer has to
be able to ask a running service what it is. There is no universal convention for
that, which is the real reason this phase is not free: it needs an additive
optional `spec.observability.versionEndpoint` in the v1 schema — a declared path
returning a version string. Additive and optional, so every existing manifest
still validates and `apiVersion` does not move (§9). A service that declares none
can be deployed-to and tracked; it simply cannot be drift-checked, and must be
shown as *not checkable* rather than as *no drift*. That distinction is the same
one the ribbon already makes between "never probed" and "healthy", and it will be
got wrong the same way if it is not asserted.

**What it unblocks, which is the argument for doing it next:**

- **Drift detection**, deferred since phase 7 for exactly this reason. Declared
  version ≠ observed version, per environment. A failed probe stays an outage;
  drift is a different fact and must keep its own name.
- **The Promotion view** — prod on 5.0.2 while staging is on 5.1.0-rc1, and how
  long each has been there. Every field in that block of the prototype except
  instance counts.
- **Phase 14**, below.

**Deliberately not in this phase, so the scope does not drift the way the code
might:**

- **Deploy gates.** Blocking somebody's pipeline on a scorecard is a much larger
  promise than recording what it did, and it makes OpsAtlas a hard dependency of
  everyone else's release. Recording first, gating only if asked for.
- **Triggering a deploy.** OpsAtlas never writes to a monitored repository
  (ADR 0008) and this does not change that. It records; it does not act.
- **"Instances 10 / 10" and "3 consecutive failures to evict."** These are
  orchestrator facts. Probing one URL through a load balancer cannot count
  replicas, and rendering a number that cannot be measured is the failure §10
  rules out. They need a Kubernetes integration, not a deployment table.

### Phase 14 — Incidents

Listed in §7's domain model, in §10's eventual navigation, and in the four jobs
colour is rationed to ("open-incident emphasis"). Until now it had no phase,
which meant it was in the vocabulary of the project and in nobody's plan.

**The decision that shapes it: OpsAtlas records incidents, it does not declare
them.** Opening one automatically from probe failure is the obvious feature and
the wrong one at this fidelity — probe availability from a single vantage point
is not an outage, and an incident that pages someone because one network position
had a bad minute is worse than no incident at all. Incidents are opened by a
person, or by an external alerting system through the API. What OpsAtlas adds is
the correlation nobody else has in one place: the affected service and
environments, the deployments that landed just before it (phase 13), the
declared `spec.journeys` that say who is affected, and the audit trail.

**What it needs:** a lifecycle (open → mitigated → resolved), a severity rendered
as a count of filled marks rather than a colour (§10), affected environments, and
a timeline of entries. The nav item appears when the page is real, not before.

**The honest caveat to write down now, before it is convenient to forget:** an
incident record is only as good as the discipline of the people filling it in,
and a half-maintained incident log reads as a claim that nothing has broken
recently. The phase is not finished until the empty state says which it is.

### Deferred decisions, recorded so they are not lost

- **Policy exceptions** — dated, auto-expiring waivers with a named approver. The
  best governance idea in the prototype, deferred because an approver requires
  identity, and identity is stubbed (ADR 0003, ADR 0004).
- **PostgreSQL row-level security** — the strongest form of org scoping, and the
  right answer once real authentication lands (ADR 0003).
- **Database-level audit immutability** — `REVOKE UPDATE, DELETE` on `audit_event`
  for the application role. Enforced only in the application layer in slice one,
  and described that way.
- **Dependency graph** — `spec.dependencies` is stored in slice one but not
  resolved into a graph. Blast radius needs both the graph and the observer.
- **Idempotency keys** — §9 requires them for observation and webhook ingestion.
  Neither exists in slice one; re-registration is made idempotent by the manifest
  digest instead (ADR 0007).
- **Manifest moves** — a `service.yaml` moved to a new path registers a second
  service, because the path is part of the registration key (ADR 0007). Detecting
  a move needs the GitHub integration's view of a diff.
- **Service renames** — refused today. Supporting them means addressing services
  by UUID in URLs and carrying an alias, which is a migration feature rather than
  an edit.
- **Sync backoff** — `source.consecutive_failures` is recorded and shown but
  nothing throttles on it. A repository that has 404'd two hundred times is still
  polled every five minutes, which is the rate limit being spent on a known-dead
  source.
- **A vanished repository is never retired** — a deleted repository and an outage
  both surface as a failing sync, and deciding that a service is gone is not a
  decision a poll timeout should make.
- **Observer high availability** — one observer is a single point of blindness,
  and two would double-count into the same counters. Reconciling that needs
  per-observer attribution the schema does not have.
- **Offset pagination** — never. §9 requires cursor pagination for every
  collection, and slice one sets that precedent with the first endpoint.
