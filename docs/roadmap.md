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
│   │       ├── identity/          ✓ organizations, principals, token resolution
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
│   ├── k8s/                         phase 28
│   └── helm/                        phase 28
├── examples/
│   └── services/                  ✓ example and fixture manifests
├── scripts/                       ✓ check-secrets.sh — a layout deviation, ADR 0012
├── docs/
│   ├── adr/                       ✓ 0001-0015
│   ├── architecture/              ✓ slice-one.md
│   ├── design/                    ✓ tokens.md
│   └── roadmap.md                 ✓ this file
├── terraform/                       phase 27
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
Server-side filtering is phase 18.

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

## Unfinished business from slice one

Slice one's definition of done named four commands. Three of them do not exist,
and the fourth was replaced without the swap being written down. They are
recorded here rather than quietly dropped, because a definition of done that
names a command nobody can run teaches everybody to skim the list.

| Asked for | State | What it would take |
|---|---|---|
| `make lint` | **Missing.** `apps/web/package.json` declares a `lint` script wrapping `next lint`, but **eslint is not a dependency**, so the script cannot run. Nothing lints the Java at all | An eslint config and dependency for the console, and a Java linter — Checkstyle, or Spotless with `palantir-java-format` — as a Gradle plugin. Both are dependencies under `CLAUDE.md` §5 and need the one-line justification |
| `make format:check` | **Missing**, and the name is wrong for this Makefile besides: every other check is hyphenated (`check-openapi`, `check-secrets`), so it would be `make check-format` | Prettier for the workspace, Spotless for the JVM. The cost is one formatting commit that touches nearly every file |
| `make build` | **Missing.** The work happens — the console's standalone build and the control plane's bootable jar are both produced inside the phase 10 Dockerfiles — but nothing exposes it, so there is no way to check that both still build without building images | A thin target over `pnpm --filter @opsatlas/web build` and `./gradlew :control-plane:bootJar` |
| `make seed` | **Superseded, not dropped.** Registering the example manifests is now `make first-user` plus the console, or a watched source, or curl with a token. Seeding stopped being one command when authentication landed (ADR 0013): a seeder needs a credential, and one committed for convenience is exactly what §3 rule 5 exists to stop | Either a target that reuses the operator token `make first-user` already produces, or a decision to leave it and say so |

**None of this is a correctness risk, and that is why it survived this long.**
`make test` already runs `check-secrets`, `check-examples`, `typecheck` and
three test suites, and CI runs the same six jobs a developer does. What is
missing is style enforcement, not verification. It is listed because slice one
claimed it and slice one did not deliver it.

---

## After slice one

Not implemented, not scaffolded, not configured. `CLAUDE.md` §14: if one of these
seems necessary to finish the current slice, stop and say so rather than quietly
expanding scope.

| Phase | What | Why it waits |
|---|---|---|
| ~~6~~ | ~~GitHub sync~~ — **done**, by polling rather than webhooks (ADR 0008). A GitHub App is still deferred, though no longer because identity is stubbed — it needs a registered application, a private key, an installation flow, and somewhere to keep a per-installation secret at rest. That last one is the same missing piece phases 16 and 17 need, and it should be solved once for all three. | |
| ~~7~~ | ~~Go observer~~ — **done**. Drift detection is deferred: it needs a deployment concept to compare a declared version against a running one. | |
| ~~8~~ | ~~OpenTelemetry, Prometheus, Tempo, Grafana~~ — **done**. Loki is still deferred: structured JSON on stdout already carries the correlation ID, and shipping it needs an agent, a retention policy and a second query language. | |
| ~~9~~ | ~~**Authentication and authorization**~~ (ADR 0013) — **done**. Every `/api/v1` endpoint needs a verified RS256 token, and a verified token is not a membership: a `principal` row keyed on issuer **and** subject is required, or the answer is 403. The stub resolver is deleted. The four items this row listed as open are all closed — the observer carries its own key on `X-OpsAtlas-Key`, `OrgIsolationIT` covers authorization across organizations and `no_endpoint_escapes_this_test` keeps it covering every endpoint, Prometheus scrapes with a credential, and Swagger UI is off unless asked for | |
| ~~10~~ | ~~Packaging~~ — **done**. Multi-stage Dockerfiles for the control plane (JDK builds, JRE runs) and the console (Next standalone output), both non-root with their code read-only to the process. `make up-app` runs the stack containerised; verified by signing in through a real browser against it. Images are built from this repository rather than pulled — there is no published OpsAtlas image, and naming one that does not exist would be a promise the compose file cannot keep. | |
| 11 | Transactional outbox, platform events, Redis read models | Only once there is a demonstrated need (§6). Nothing today has a second consumer of registration events, no observer needs coordinating, and no read model is slow |
| **12** | **Deployment** — one box, compose behind Caddy (ADR 0014) | **Deployed.** Running on a DigitalOcean droplet since 2026-09-15 at `https://opsatlas.hoseacodes.com`: seven containers, images pulled from GHCR by tag, TLS from Let's Encrypt, one public hostname. Doing it found three faults no local check could have — an impossible first start, a console image permanently unhealthy while working, and a `latest` tag the workflow claimed not to produce. **Still open, and now each with a phase of its own rather than a clause here:** backups are **phase 25**, k6 is **phase 26**, Terraform is **phase 27**, Kubernetes and Helm are **phase 28**. Zero-downtime deploy and redundancy follow 28; the telemetry stack is still not deployed and stays a local profile until the box has room. None of them is circular any more — there is something for the IaC to describe |
| **13** | **Deployments** — what version is running where | **Built, minus drift.** A `deployment` table, `POST /api/v1/services/{slug}/environments/{name}/deployments` with a required idempotency key, `GET /api/v1/services/{slug}/deployments`, and the Promotion view in the console. `converge.sh` reports OpsAtlas's own deploys, so the platform's own entry carries real data. **Drift is still not built**: it needs an *observed* version and nothing exposes one — see ADR 0017. Instance counts are not possible here at all. Original reasoning below |
| **14** | **Incidents** | Needs 13. Recording an incident with no deployment history is a worse spreadsheet; the value is the correlation. Detailed below |
| **15** | **Drift detection** — declared version versus running version | The other half of 13, and the thing `CLAUDE.md` §5 has listed as the observer's job since the beginning. Needs a service to expose a running version, which means a new manifest field and observer support. Detailed below |
| **16** | **Live on-call** — who is answering right now | A read-only paging-provider integration (PagerDuty, Opsgenie). ADR 0016 built the declared half; this is the half that needs somebody else's API and a credential per organization |
| **17** | **Runtime topology** — instances, replicas, pod readiness | The prototype's "Instances 9 / 14" and "5 pods failing readiness". Needs an orchestrator, cluster credentials and a workload-to-service mapping that follows from nothing in the manifest today. Detailed below |
| **18** | **Catalog query** — server-side search and filtering | Slice one asked for a list endpoint filterable by team and tier and searchable by name; what shipped takes `cursor` and `limit`, and the console filters the one page it holds. Invisible at seven services, wrong at a hundred. Detailed below |
| **19** | **Alerting** — telling somebody without being asked | **The largest gap in the project, and until now it had no phase at all.** A platform that measures health and never tells anyone is a dashboard you have to remember to open. Every input landed in phases 7, 13 and 16. Detailed below |
| **20** | **Declared operational posture** — backups, retirement dates, tests, scheduled jobs, API spec | One phase because they are one pattern: additive optional manifest fields plus scorecard rules. The catalog cannot verify any of them and must not pretend to. Detailed below |
| **21** | **Dependency graph and blast radius** | The cheapest thing on this list: the edges are already stored, nothing walks them. Detailed below |
| **22** | **Delivery metrics** — deploy frequency, lead time, change failure rate, pipeline health | Half of it falls out of the deployment ledger phase 13 already built. The other half needs a CI provider. Detailed below |
| **23** | **Supply chain and security posture** — SBOM, known vulnerabilities, image provenance | Different in kind from 20: these must be *verified* from an external source, not declared. Detailed below |
| **24** | **Log aggregation** | Deferred in ADR 0011 with reasons that still hold. Given a number here so it stops being a gap with no home |
| **25** | **Backups and restore** | **No phase has ever owned this**, and ADR 0014 named it a consequence and left it there. Two volumes are the system of record, not one — PostgreSQL, and the issuer's MongoDB, without which nobody can sign in. Ready now; nothing blocks it. Detailed below |
| **26** | **Load and soak testing** — k6 | Named in `CLAUDE.md` §14 and in the phase 12 row, written down nowhere. It is the only item on this list that deletes a sentence from the README rather than adding a feature. Ready now. Detailed below |
| **27** | **Infrastructure as code** — Terraform | ADR 0014 rejected it with a reason that still holds: the box is worth describing in code once its shape has stopped changing, and it has not. Has a stated trigger rather than a date. Detailed below |
| **28** | **Kubernetes and Helm** | "Deferred, not rejected" in ADR 0014. Coupled to phase 17, which needs a cluster to read; decide them together. Detailed below |
| **29** | **Security posture and threat model** — `docs/security/` | Asked for by the founding prompt, never created, while the decisions it would document all exist and are tested. No new code; it is the consolidation plus the honest list of what is *not* defended. One finding already in hand. Ready now. Detailed below |
| **30** | **Inbound rate limiting** | **There is none.** The only rate limit in this codebase is GitHub's, outbound. The founding prompt required org scope in rate limits; an authenticated API on a public address has nothing bounding call rate. Detailed below |
| **31** | **The public case study surface** | The deployed console is a login wall: six routes, all gated, no public page at all. This project has two jobs and the deployment does one of them. Blocks on nothing; the decision inside it is how much live data a public page shows. Detailed below |

### Phase 11 — Outbox, platform events, read models

**No design here, deliberately.** Every decision this phase needs depends on the
consumer that does not exist: whether events are ordered per service or globally,
whether a read model is Redis or a materialized view, and whether the outbox is
polled or tailed are all answerable once something consumes them and guesses
before that. Writing the design now would mean writing it twice, and the second
version would have to argue with the first.

**The trigger to watch for**, so this is deferred by judgement rather than by
inertia — any one of:

- A second consumer of registration. Today the only one is the scorecard, and it
  runs inside the same transaction, which is the correct design for exactly one
  consumer and the wrong one for two.
- A read query slow enough to need caching and not fixable by an index. None
  exists; the fleet is small enough that every catalog query is a sequential scan
  nobody notices.
- Work that has to survive a control-plane restart. The retention job and the
  source poller are both `@Scheduled` and both re-run harmlessly on the next
  tick, which is why neither needs durability.

Until one of those is true, this phase is a directory that would exist to look
serious — which is what §3 rule 3 rules out.

### Phase 13 — Deployments and drift

> **Built on 2026-09-16, except drift.** The table, the endpoints, the console
> view and OpsAtlas reporting its own deploys are done and tested. What follows
> was written before that and is kept because the reasoning still holds — the
> deferred half is the same half. ADR 0017 records what was decided.


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

### Phase 15 — Drift detection

Phase 13 records what somebody said they deployed. Drift is the gap between that
and what is **actually answering**, and it is the whole reason the deployment
concept was worth building.

**The hard part is getting an observed version at all.** There is no universal
way to ask a running service what it is. Two routes:

- **A declared version endpoint.** An additive optional
  `spec.observability.versionEndpoint` — a path the observer GETs during a pass.
  Additive and optional, so every existing manifest still validates and
  `apiVersion` does not move (§9). The response convention has to be lenient and
  written down: a trimmed `text/plain` body, or JSON carrying `version` or
  `build.version` (which is what Spring's `/actuator/info` already returns).
  Size-capped and treated as untrusted, like every other byte from a monitored
  repository — it is parsed, never evaluated.
- **A header on the existing health probe**, say `X-App-Version`. No new request
  and no new field, and that is also its defect: nothing in the manifest declares
  it, so no scorecard rule can ask for it and no page can explain its absence.

**The declared endpoint is the better route** precisely because it is declarable.
A field the scorecard can score is a field teams can be told they are missing.

**Storage is `environment_state`, not a new table.** An observed version is
current state like `status` is — one row per environment, updated in place,
bounded. Two columns: `observed_version` and `observed_version_at`.

**Three states, and the third is the one that gets botched.** `IN_SYNC`,
`DRIFTED`, and `NOT_CHECKABLE` — no version endpoint declared, or it never
answered. `NOT_CHECKABLE` must never collapse into `IN_SYNC`; that is the same
mistake as rendering a never-probed environment as healthy, and it is the mistake
this project has already made once and written a test against.

**Two timing problems that will make drift cry wolf if ignored:**

1. **A rollout looks exactly like drift.** For the length of a deploy, the
   reported version and the answering version genuinely differ. Drift must not be
   asserted until a mismatch has *persisted* past a grace window measured from
   the last reported deployment — configurable, and generous by default. A drift
   signal that fires on every successful deploy is a signal everybody turns off.
2. **During a rolling deploy, replicas disagree.** One probe reaches one replica
   through a load balancer, so the observed version flaps between old and new for
   the duration. Phase 17 is what actually fixes this; until then the grace
   window is the mitigation and the limitation should be stated on the page.

**ADR 0018 made this phase bigger.** Drift here was designed as *reported*
versus *observed*. Desired state — declared intent, which nothing records today —
is a third version, and the gap between desired and reported is a failure this
phase as written cannot see: the deploy that never ran. Phase 15 therefore lands
the desired-state endpoint too, and a view that distinguishes three gaps rather
than two. Read ADR 0018 before starting; it argues its own rejected alternative
well enough to be worth re-reading at that point.

**Deliberately not in this phase:** any remediation. OpsAtlas does not write to
systems it monitors (ADR 0008) and drift detection must not become the exception.
It reports a mismatch; a human decides.

### Phase 16 — Live on-call

ADR 0016 built the declared half: `spec.operations.oncall` carries a rotation
URL, a `coverage` enum and an escalation target, and `oncall-declared` scores it,
stricter at tier 1 than at tier 2. It stops short of naming a person, and
`CLAUDE.md` says why — a name this system could not refresh would go stale into
the one page somebody reads at 03:00.

**What it needs:** a read-only integration with a paging provider (PagerDuty,
Opsgenie), a credential per organization, and a mapping from a declared rotation
URL to that provider's schedule id.

**The decision that shapes it is the same one ADR 0016 made.** A name this system
displays must carry the time it was fetched, and must fall back to the declared
rotation link when the provider cannot be reached. **"Nobody is on call" and "we
could not ask" are different answers and must never render the same** — which is
the never-probed rule from phase 7 applied to a different absence.

**Where it lives:** `integrations`, with the rest of the outbound HTTP.
`ArchitectureTest` enforces that, and a paging provider is exactly the kind of
caller that would otherwise be wired straight into a console component.

**The cost worth stating before starting, because it is larger than the feature:**
this is the first integration needing a per-organization secret at rest, and there
is nowhere to put one today. GitHub polling is unauthenticated, the observer's key
is a single shared value, and every other credential is process configuration. Per
tenant secret storage is its own decision with its own ADR — and it is the same
problem ADR 0008 deferred for the GitHub App and phase 17 needs for cluster
credentials. **It should be solved once, for all three**, and whichever phase goes
first pays for it.

### Phase 17 — Runtime topology

The prototype's `Instances 9 / 14`, `Passing 10 of 10`, and
`5 pods failing readiness — /actuator/health/readiness returned 503 three times
running`. None of it is reachable from where OpsAtlas stands today: a probe hits
one URL through whatever sits in front of it and cannot see how many replicas
answered, or which ones did not.

**What it needs, in the order the cost lands:**

1. **Cluster credentials.** The largest decision, not the API work. This is a
   public repository deployed on one box where `docker` group membership is
   root-equivalent (ADR 0015); a kubeconfig on that machine is a much bigger
   surface than polling public GitHub unauthenticated. Where those credentials
   live, and per-organization rather than per-installation, is the same problem
   ADR 0008 deferred for the GitHub App — and it should be solved once, for both.
2. **A workload-to-service mapping.** Nothing in a manifest says this service is
   Deployment `orders-api` in namespace `prod` of cluster `eu-1`. It needs a
   declared field, per environment, and it is per-environment because the same
   service is a different workload in staging.
3. **A client per provider.** Kubernetes, ECS, Nomad. `integrations` currently
   speaks to exactly one external system, read-only and unauthenticated.
   `ArchitectureTest` enforces that only `integrations` makes outbound HTTP
   calls, so this belongs there and not in `operations`.

**What it gives back, beyond the counts:** a far better observed version than
phase 15 can manage. An orchestrator knows the image tag of every replica, which
answers "what is running" exactly and fixes the flapping problem a single probe
cannot. If both phases happen, this one subsumes the version-endpoint route for
any orchestrated service — which is an argument for doing 15 cheaply, or for
doing 17 first if a cluster is available.

**Non-negotiable: read-only.** No scaling, no restarting, no rollout triggering,
no `kubectl` behind a button. The moment OpsAtlas can act on a cluster it is a
deploy tool with a catalog attached, and the credential it holds stops being
something a public repository's deployment can justify.

**The caveat to write on the page from day one:** a service that is not
orchestrated has no instances. A VM, a serverless function and a static site must
read as *not applicable*, never as `0 / 0` — which looks exactly like everything
being down.

### Phase 18 — Catalog query

Slice one's definition of done asked for `GET /api/v1/services` "filterable by
team and tier, searchable by name". What shipped takes `cursor` and `limit`, and
the console filters the page it already holds. With seven services that is
invisible. At a hundred it is wrong, and wrong in the way that matters most: a
search box that silently searches one page of results looks exactly like a search
box that found nothing.

The console's empty state says so, which is the honest handling of a gap and not
a substitute for closing it.

**What it needs:** `team`, `tier` and `q` parameters on the list endpoint, pushed
into the repository query rather than applied after paging.

**The part that is easy to get wrong:** a cursor encodes a position in an ordered
set, so changing the filter changes the set the cursor refers to. A cursor issued
under one filter and replayed under another silently skips or repeats rows. The
filter therefore belongs *inside* the cursor, and a cursor whose filter does not
match the request is a `400` naming the mismatch — not a best effort.

**Why the console cannot fix this at any page size:** it never sees the rows it
did not fetch. This is the API's problem by construction.

**Deliberately not in this phase:** ranking, full-text search, or anything needing
an index this database does not have. `name ILIKE '%q%'` with a trigram index is
the whole feature. A search engine is a different decision, with a different
operational cost, and nothing here justifies it yet.

**Isolation applies as it does everywhere:** a new endpoint parameter does not
escape `OrgIsolationIT`, and a filter that reaches across organizations is the
exact failure `no_endpoint_escapes_this_test` exists to catch. A search that
returns another tenant's service names is a data leak whether or not it returns
their details.

### Phase 19 — Alerting

**The gap that most contradicts the product's own description.** OpsAtlas
measures probe availability, knows a service's tier, knows its declared SLO
target, and since ADR 0016 knows where its rotation lives — and it has never
told anybody anything. It is a dashboard somebody has to remember to open.

Worth saying plainly because the repository nearly hides it: "alerting" appears
in this codebase twice, both times as *rationale* for another rule ("an error
budget is what makes an alert threshold something other than a guess"), and
never as a capability. Every input for it now exists; nothing consumes them.

**What it needs:**

- **A rule: what is worth waking somebody for.** Tier-conditional, like every
  other judgement here. A tier 1 production environment DOWN for three
  consecutive probes is not the same event as a tier 3 internal tool failing
  once.
- **A route: where it goes.** `spec.operations.oncall.rotation` is a link to a
  schedule, not an address that accepts a page. Routing needs either the paging
  provider from phase 16, or a webhook per organization, or email. The honest
  first version is probably a webhook: it needs no provider integration and
  makes the delivery someone else's problem.
- **Deduplication and state.** An alert must fire on a *transition*, not on
  every evaluation, or a service down for an hour delivers a page per probe. This
  is the part that looks trivial and is not.
- **Silences.** Planned maintenance, a known-broken staging environment, a
  service being retired. Without them the first noisy week trains everyone to
  ignore the channel, and the feature is then worse than its absence.

**The honest constraint that shapes the whole phase:** what OpsAtlas can alert on
is **probe availability from one vantage point** (`CLAUDE.md` §2). It is not an
SLO, and it is not what users experience. An alert that says "orders-api is down"
when one network position could not reach a health endpoint is going to be wrong
sometimes, and the message has to say what was actually observed rather than
what somebody would like it to mean.

**Deliberately not in this phase:** paging as in phone calls and escalation
policies. That is what PagerDuty is, and reimplementing it badly is the same
mistake ADR 0016 rejected for rotations. OpsAtlas should deliver an event to
something that already does escalation.

### Phase 20 — Declared operational posture

Six fields that share one shape: a team declares something, the scorecard scores
whether they did, and **OpsAtlas cannot verify any of it.** They are one phase
because they are one afternoon's pattern each — additive optional field, rule,
render — which is exactly the shape `spec.operations.oncall` took in ADR 0016.

| Field | Why it earns a rule |
|---|---|
| `spec.operations.backup` | Whether data is backed up, how often, and where the restore is documented. A tier 1 service with a datastore dependency and no declared backup is a gap the catalog is currently blind to |
| `spec.lifecycle.retiresOn` | `lifecycle: deprecated` with no date is a state nothing ever leaves. A date makes a deprecation reviewable |
| `spec.quality.tests` | Where the suite runs and what gate it must pass. Not coverage as a number — a percentage in a manifest is a number nobody updates |
| `spec.operations.schedules` | Cron jobs the service owns. An unlisted scheduled job is the classic thing nobody knows about until it stops |
| `spec.observability.apiSpec` | The OpenAPI or schema document. The prototype's "API spec" link, which the Operations footer deliberately omits today because there is no field for it |
| `spec.data.classification` | Whether this handles personal or regulated data. It changes what every other answer here has to be |

**The rule that governs all of them, and the risk:** each is a *declaration*.
`backup: daily` in a YAML file is not a backup, and a scorecard that reads 11/11
because every field is filled in is a scorecard measuring paperwork. Each rule's
failure message must say what it checked, and the page must not let a filled-in
field read as a verified one. Phase 22 is where some of these become verifiable;
until then the honesty burden is entirely on the wording.

**A caution about count.** Every rule added moves every stored score's
denominator (ADR 0016 consequences). Six at once is a large single movement in
the fleet number for reasons unrelated to anything getting worse, and
`PolicyCatalog.VERSION` exists so an old score still means what it meant. Adding
them in one release is fine; adding them without bumping the version is not.

### Phase 21 — Dependency graph and blast radius

**The cheapest item in this file.** `spec.dependencies` has been stored,
normalized and scored since phase 3. Nothing has ever walked it backwards.

The console already says outright that its dependency list is *not* a blast
radius, because nothing computes reverse edges. Computing them is a query and a
page: for a service, which registered services declare it as a dependency, and
transitively what a failure reaches. The prototype's "2 registered services call
this one" and its Blast radius tab are both this.

**What it needs:** either an index over the manifest JSONB, or a `dependency_edge`
table written during registration. The second is probably right — the same
transaction that scores and audits already has the parsed manifest in hand, and
a table is joinable where a JSONB scan is not.

**Two honest limits to render, not hide:**

- **Declared edges only.** A service that calls another and does not say so is
  invisible, and the graph will be confidently incomplete. Traces would show the
  real calls; joining them needs `spec.observability.serviceName`, which is
  already declared and already scored — which makes this a plausible *second*
  version rather than a fantasy.
- **A dependency by name is not a registered service.** `payments-api` as a
  string may match a catalog entry or may be a service nobody registered. Both
  are useful to show and they are not the same fact.

### Phase 22 — Delivery metrics

**Half of this is already sitting in the database.** The `deployment` table from
phase 13 records version, commit and time per environment, which is deploy
frequency and time-between-deploys with no new ingestion at all. Lead time needs
a commit timestamp, which the commit SHA makes fetchable from the source that is
already being polled read-only (ADR 0008). Change failure rate needs incidents —
phase 14 — correlated to the deployment that preceded them, which is the
correlation phase 14 exists for.

So the useful ordering is: this after 14, and most of it costs a query rather
than an integration.

**The half that does need a provider:** pipeline health — run duration, failure
rate, flakiness, whether the linter and the test gate actually ran. That needs
GitHub Actions or equivalent, read-only, and it is the answer to "do you track
linters and tests" that is worth anything: not *whether a team declared* a
linter, but whether the pipeline that enforces it passed.

**The caveat:** these are DORA-shaped numbers, and DORA-shaped numbers get used
to compare teams. A deploy-frequency figure computed from whoever remembered to
call the reporting endpoint (ADR 0017) measures reporting discipline as much as
delivery. If this is built, the page has to say what the denominator really is.

### Phase 23 — Supply chain and security posture

Distinct from phase 20 because these cannot be declarations. "We scan our
images" in a manifest is worth nothing; the finding is the thing.

**What it would track:** an SBOM per released artifact, known vulnerabilities
against it with severity, base-image age, and build provenance — whether the
image running was built by the pipeline it claims.

**What it needs:** a scanner or a registry that already produces this, read-only,
plus a link from a service to its artifact. That link is the same
workload-to-service mapping phase 17 needs, which is an argument for doing them
near each other.

**Why it is late rather than never:** it is the most valuable thing on this list
for a real fleet and the most dependent on infrastructure this project does not
have. A vulnerability count that is stale is worse than none, because it reads as
an all-clear.

**Out of scope permanently:** OpsAtlas scanning anything itself. It reads
findings somebody else produced. A control plane that pulls and analyses
arbitrary images from monitored repositories is a much larger attack surface than
a catalog needs, and it violates the spirit of §3 rule 4.

### Phase 25 — Backups and restore

**The only item on this list that is a risk rather than a feature.** ADR 0014
listed "backups are not solved by this ADR" under consequences and no phase
picked it up, so it has been true and unowned since the deployment went live.
`README.md` and `CLAUDE.md` both say the data does not survive the box, which is
honest and is not a plan.

**Two volumes are the system of record, not one.** The obvious one is
PostgreSQL — the catalog, the scorecards, the audit log, the deployment ledger.
The one that is easy to miss is the issuer's **MongoDB**: it holds the accounts,
and losing it means nobody can sign in to a control plane whose data survived
perfectly. The `principal` rows in PostgreSQL are keyed on issuer and subject,
so restoring one without the other leaves rows pointing at subjects that no
longer exist.

**What it needs:**

- **A scheduled dump off the host.** `pg_dump` and `mongodump` on a timer, in
  the same shape as the convergence timer that already exists, written to object
  storage rather than to the disk that is the thing being protected.
- **Retention, and a size ceiling.** A dump per day forever is a bill that grows
  without anybody deciding to spend it.
- **A restore that has actually been run.** This is the part that is the phase.
  A backup nobody has restored is a hypothesis, and `CLAUDE.md` §3 rule 1 rules
  out describing an unexercised one as working. The acceptance criterion is a
  restore into a scratch stack, with a note of how long it took and what it
  needed.

**The unconfirmed alternative:** DigitalOcean's droplet-level backups may be
enabled — `CLAUDE.md` says this has never been checked from inside the box, and
checking it is ten minutes. It is not a substitute even if it is on. A weekly
whole-droplet snapshot has a recovery point measured in days, restores the
secrets along with the data, and gives no way to recover one table.

**The downside to write into the ADR:** a dump in object storage is a copy of
every principal row and every audit event sitting somewhere else, reachable with
a credential that lives on the box. It needs encryption at rest and a key scoped
to write-only where the provider supports it, or the backup is a second copy of
the thing to protect with half the protection.

### Phase 26 — Load and soak testing

**k6 has been named twice in this repository and written down nowhere** — a
clause in the phase 12 row and a line in the target layout. Meanwhile
`README.md` carries "nothing has been load-tested or security-tested" in two
places. That sentence is currently permanent by omission rather than by
decision, and this phase is what makes it a decision.

**What it needs:** a k6 script against the authenticated surface — the catalog
list, a service detail, a scorecard — carrying a real token, because an
unauthenticated run measures the 401 path and nothing else. Plus a **soak run**,
which is the more interesting half at this size: the failure mode for a
single-box Spring Boot application is a connection pool or a heap over hours,
not a request rate over minutes.

**Two numbers, labelled separately, and never averaged:**

1. **The application number**, from `make up-app` locally. This is the one that
   says something about the code.
2. **The deployment number**, from a bounded run against the box. This one
   measures 2 vCPU and 4 GB shared by the control plane, the console,
   PostgreSQL, MongoDB, Storm-Gate, the observer and Caddy — with traces sampled
   at 100% (ADR 0011). It is a fact about that droplet, and the write-up has to
   say so or it will be read as the application's ceiling.

**A caution that applies to the second:** the only deployment is the production
one, so a load test is run against the thing people can open. Bound it, run it
deliberately, and expect the observer's own probe results for that window to be
affected — OpsAtlas will record its own load test as a degradation, which is
either a nuisance or the most honest demonstration in the project.

**Deliberately not in this phase: security testing.** The README sentence pairs
them because they are both unmeasured, not because they are one job. A
penetration test is a different discipline, and folding it in here would let a
green k6 run quietly imply half of a claim nobody made.

### Phase 27 — Infrastructure as code

**ADR 0014 rejected Terraform for the box, and the reason it gave still holds:**
one VM, one firewall and three DNS records is under an hour of clicking and a
week of learning a provider's resource model, and the box is worth describing in
code once its shape has stopped changing — which, days after the first
deployment, it has not.

So this phase gets a **trigger rather than a date**: 30 consecutive days with no
change to the droplet's shape — no resize, no newly published port, no new DNS
record, no attached volume. If the shape is still moving, describing it in code
means editing two things every time instead of one.

**Scope when it fires:** the droplet, the firewall, the DNS records, the GHCR
read credential and the SSH key. **Import the existing box rather than
recreate it.** `terraform import` against what is running keeps the IP, and a
recreate means a new address, a DNS propagation window and a certificate
reissue in exchange for nothing.

**What the value actually is**, stated because it is not provisioning: a written
record of what the box is, and a `plan` that reports drift when somebody changes
it by hand. Provisioning one droplet is the cheap part. The second box, if there
ever is one, is where the code pays for itself — and that is also phase 28's
trigger, which is not a coincidence.

**The downsides:**

- **State has to live somewhere.** Not in this repository, which is public. A
  DigitalOcean Spaces backend needs a credential, so protecting the box's
  description becomes one more secret to hold — and the state file contains the
  firewall rules and the DNS layout, which is a map.
- **It does not cover the inside of the box.** The SSH hardening that
  `CLAUDE.md` records as never applied is host configuration, not
  infrastructure; Terraform will not do it and this phase must not be described
  as though it did. That is a separate, smaller job that should not wait for
  this trigger.
- **It needs an ADR** that either supersedes ADR 0014's "Terraform for the box
  itself" paragraph or restates it as still correct. Leaving that paragraph
  standing while `terraform/` exists is the contradiction this roadmap keeps
  trying to avoid.

### Phase 28 — Kubernetes and Helm

ADR 0014 called this **"deferred, not rejected"** and gave the honest argument in
both directions: it is what the roles this project is evidence for actually run,
and five containers that must start in order on one host is precisely the case
where an orchestrator's scheduling, discovery and rollout machinery all cost
something and return nothing.

**The trigger is any one of:**

- **A second box.** Two hosts is where compose stops being the simpler thing.
- **A requirement for zero-downtime deploys.** Today `docker compose up -d`
  stops and starts containers and the console is down for the seconds that
  takes. That is currently acceptable and is written down as such.
- **Phase 17 — runtime topology.** It needs an orchestrator to read instance and
  readiness counts from, and there is nothing to read today. If 17 is wanted,
  this stops being optional.

**Decide 17 and 28 together.** 17 needs cluster credentials on a box where
`docker` group membership is already root-equivalent, and 17 stays **read-only**
by rule — the moment OpsAtlas can act on a cluster it is a deploy tool with a
catalog attached. Building the cluster and the read-only integration in
isolation from each other gets the credential question answered twice.

**The downsides:**

- **Local and deployed topologies would drift.** Today `deploy/compose` and
  `deploy/production` are the same file shape with different values, and
  `make up-app` exercises the arrangement that actually runs. Charts mean the
  thing a developer runs and the thing that serves traffic are described by two
  different systems — which is one of the reasons ADR 0014 rejected Fly.io.
- **ADR 0015 would be replaced, not extended.** Deploying is a commit to
  `deploy/production/VERSION` and rolling back is `git revert`. A chart changes
  the mechanism; if the replacement does not keep "the deploy history is
  `git log`", it is a regression wearing a better-known name.
- **It is an operating cost, not a one-off.** A managed cluster for seven
  containers is several times the droplet's bill, plus a control plane to
  upgrade — in order to run a control plane.

### Phase 29 — Security posture and threat model

**No new code.** Every decision this phase writes down has already been made and
tested; none of it is anywhere a reader can find in one piece. The founding
prompt asked for `docs/security/` and this project's second job names security
explicitly, so the absence is conspicuous — a publicly deployed control plane
with authentication, tenancy isolation and machine credentials, and no page
saying what it defends against.

**Three files, not a directory of stubs** (§3 rule 3 applies to `docs/` too):

**`docs/security/threat-model.md`**

- **Assets**, including the one that is easy to miss: the probe target list is a
  map of somebody's infrastructure. With it, the audit log — where integrity
  matters more than confidentiality — the `principal` rows, and the machine
  credentials.
- **Trust boundaries**, which are unusually drawable here because ADR 0014 made
  them small: the internet reaches Caddy on 80 and 443 and nothing else; Caddy
  reaches the console; the console calls the control plane and the issuer over
  the compose network, holding the signed-in user's token in an httpOnly cookie;
  the observer comes in on `X-OpsAtlas-Key`; JWKS and GitHub are outbound only;
  PostgreSQL, MongoDB and Storm-Gate are published nowhere.
- **Actors:** anonymous, authenticated-but-unprovisioned (403 rather than 401,
  and why), a member of another organization, the observer's machine identity,
  Prometheus, and whoever holds the box.
- **What is defended, with the evidence beside each claim** rather than as
  assertion — RS256 verified against the issuer's JWKS with no signing key held
  here, membership requiring an issuer-and-subject pair, per-query org filtering
  proven by `OrgIsolationIT` and kept honest by `no_endpoint_escapes_this_test`,
  parse-never-execute ingestion (ADR 0002), read-only GitHub scope (ADR 0008),
  no session and therefore no CSRF category, and `${VAR:?}` with no production
  defaults (ADR 0014).
- **What is not defended**, which is the section that makes the document worth
  reading at all: no inbound rate limiting (phase 30); no roles, so every
  principal can do everything within their organization; no row-level security,
  so isolation is a test somebody remembered rather than the database refusing;
  audit immutability enforced only in the application; `docker` group membership
  on the box being root-equivalent; **the SSH hardening that was never applied**;
  no backups (phase 25); and never having been penetration-tested.

**One finding is already in hand**, and it is the reason to write this rather
than assume it would say nothing new: `deploy/production/Caddyfile` sets HSTS and
`X-Frame-Options: DENY` and **no Content-Security-Policy**. That matters more
here than on a typical application, because the console holds the user's token in
a cookie — a console XSS is a token compromise, and `httpOnly` is the only thing
standing between those two sentences.

**`docs/security/secrets.md`** — the six credentials this system has (the
issuer's signing key, `ACCESS_TOKEN_SECRET`, the observer key, the Prometheus
key, `deploy/production/deploy-key`, and the operator password that lives on the
box and has never been transmitted), where each lives, who can read it, and
**how to rotate it.** That last column exists nowhere today. §3 rule 5 says a
leaked credential has to be rotated rather than deleted in a later commit, and
nothing in this repository says how to rotate any of them — which makes the rule
an instruction with no procedure behind it.

**`docs/security/README.md`** — an index, and a date on every claim.

**The downside, which belongs in the document itself:** a public threat model is
a public list of what is not defended. That is the right trade for this asset set
and it should be stated outright rather than left as something the author hopes
nobody notices. And it has to be dated and maintained, or it becomes another
paragraph that was true once — this repository has already had to correct one of
those.

**Acceptance: the document causes at least one fix.** A threat model that
inventories a system and recommends nothing was written to be filed rather than
read. The CSP and the unapplied SSH hardening are the two expected to fall out.

### Phase 30 — Inbound rate limiting

**There is none, anywhere.** The only rate limit in this codebase is GitHub's,
and it is outbound and somebody else's. There is an authenticated API on a public
address with nothing bounding how fast anyone may call it.

**The design question is which layer, and the answer is not the obvious one.**
Caddy sits in front of everything and is the conventional place. It cannot work
as the primary control here: **every console-originated request reaches the
control plane from one container over the compose network**, because the console
calls the API server-side. An IP-keyed limit at the edge would therefore put
every signed-in user in a single bucket or limit nobody, depending on the number
chosen. Caddy cannot see who is calling; only the control plane can.

So:

- **Primary — an application filter keyed on the principal and the organization**,
  which is also what the founding prompt asked for when it listed rate limits
  among the things that must carry tenant scope.
- **Edge IP limiting is a separate, coarser, later layer** for the unauthenticated
  surface. Worth noting its real cost before anybody assumes it is a config line:
  Caddy's `rate_limit` is a community plugin, so it means building a custom Caddy
  image in CI and a change to `publish.yml`.

**What it needs:**

- **A filter ordered after authentication**, so there is a principal to key on,
  and **without disturbing `CorrelationIdFilter` at `HIGHEST_PRECEDENCE + 5`** —
  `CLAUDE.md` records that ordering as load-bearing and silently-failing, and a
  new filter in the chain is exactly the kind of change that has broken it.
- **An in-memory token bucket**, with the limitation stated where somebody will
  read it: this is **per instance**, so a second control plane doubles the real
  limit. That is also the first honest trigger for Redis under §6 — the
  demonstrated need it has been waiting for, and not before.
- **Two buckets, not one.** A generous default, and a tighter one for the
  expensive paths: registration parses YAML, validates it, scores eleven rules
  and writes an audit event inside a single transaction, and source sync spends a
  GitHub allowance that is already only 60 requests an hour.
- **A separate allowance for the observer**, which is the trap in this phase. It
  is a machine identity making a legitimately high request rate, and if it shares
  the human default then the first pass over a large fleet trips the limit and
  health data stops arriving — silently, because a limiter returns a clean HTTP
  response rather than an error anybody notices.
- **429 as an RFC 9457 problem with `Retry-After`**, in the same shape as every
  other error here. `ProblemSecurityResponses` is the precedent to follow.
- **The organization as a metric dimension**, so the first question after a limit
  fires — who was it — has an answer.

**Deliberately out of scope: limiting `/actuator/health` and `/v3/api-docs`.**
They are cheap, they disclose nothing, and a limiter on a health endpoint is a
denial-of-service lever pointed at your own load balancer.

**The downside:** there is no production traffic data, so the first numbers are
guesses, and a limit set too low is a self-inflicted outage that looks exactly
like a bug. Start generous, ship the metric first, tighten from what it shows —
and say in the code that the numbers are guesses, so the next person changes them
rather than treating them as measured.

### Phase 31 — The public case study surface

**The deployment currently serves an audience of one.** `apps/web/middleware.ts`
matches every path except `/login`, `/api` and static assets, and `/` redirects
to `/catalog`, which redirects to `/login`. So `https://opsatlas.hoseacodes.com`
is a sign-in form and nothing else. Every artefact that would interest a reader —
nineteen ADRs, the roadmap, the mocked-versus-real table, the reasoning about
what this system refuses to claim — lives in the GitHub repository, which means
the deployment adds nothing for that reader over the README.

That is the gap. This project has two jobs (`CLAUDE.md` §1) and the deployed
system does exactly one of them.

**What it needs:**

- **A public route group**, excluded from the middleware matcher. The matcher is
  the security boundary for the whole console, so the change is a deliberate
  allow-list of new public paths rather than a loosened regex — `/`, plus a page
  each for the architecture, the decisions and the security model.
- **`/` stops redirecting.** It becomes the public overview and renders the same
  for everybody; a signed-in visitor gets a link into the console rather than a
  redirect, because a redirect would make the case study unreachable to the one
  person who is always signed in.
- **The console stays exactly as gated as it is today.** `/catalog`,
  `/catalog/{slug}`, `/register` and `/sources` keep their current behaviour.

**Content, and where it comes from:**

The pages are the overview, the architecture (the Mermaid diagram in
`docs/architecture/slice-one.md` already exists and renders), the engineering
decisions, and the security model once phase 29 has written one.

**Generate them from the repository, do not transcribe them.** The ADR index
should be read off `docs/adr/*.md` and the capability table off `README.md`, the
same discipline `manifestPrompt.test.ts` already applies by walking the real
schema rather than a copy of it. A hand-written architecture page is the next
paragraph to go stale, and this project has had to correct two in a single
session — a README that said Spring Security was not a dependency, and a
controller comment that still described itself as unauthenticated.

**The decision inside this phase, which is not a UI decision:** what the public
pages show of the live system.

1. **Prose and diagrams only.** The console stays private; the public pages
   describe and link to the repository. Cheapest, adds no attack surface, and
   shows no live data.
2. **A read-only public organization** with the example manifests registered and
   genuinely probed, served through the existing org scoping. By far the most
   convincing — a reader sees a real catalog with real probe history rather than
   a description of one. The cost is not UI work: it means an anonymous path
   through `ProvisionedPrincipals`, which is the single place authorization is
   decided, and an exemption in `no_endpoint_escapes_this_test` with a written
   reason. It also publishes a probe target list, which phase 29's threat model
   names as an asset.
3. **Screenshots.** Middle ground, and they go stale silently, which is the
   failure mode this phase is otherwise designed against.

**Recommendation: (1) now, and (2) as its own ADR later if it is still wanted.**
Reopening the authorization model that ADR 0013 closed, in order to improve a
marketing page, is not a trade to make in passing.

**House rules that constrain this more than they look like they do:**

- **No fake buttons (§10).** A "try the demo" call to action that leads to a
  login wall is exactly the prohibited thing. Under option (1) the honest call to
  action is the repository, and a plain statement that the console needs an
  account.
- **§3 rule 1 applies to every claim on the page.** A performance number needs
  phase 26 to have run first. A capacity figure needs phase 32's designed-versus-
  tested framing and its labels. The public page is the most tempting place in
  the project to write something unmeasured, and it is the worst place to do it.
- **It must not leak into the console's navigation.** A marketing surface inside
  an operations tool is noise for the operator; the link goes one way.

**Tests:**

- A Playwright spec asserting the public routes render **signed out**. That is
  the entire point of the phase and is the first thing to regress silently.
- A spec asserting every console route still redirects to `/login` when signed
  out. This one matters more than the first: it is a security regression test on
  a matcher that this phase edits, and the failure it guards against is a private
  catalog quietly becoming public.

**The downside:** it is the first thing in this repository whose audience is not
an operator, and that makes it the first thing that will be tempting to
exaggerate. Its accuracy is only as good as its generation — which is the
argument for reading the ADR list and the capability table off disk rather than
copying them, restated as a consequence.

### Deferred decisions, recorded so they are not lost

- **Policy exceptions** — dated, auto-expiring waivers with a named approver.
  The best governance idea in the prototype. The reason it waited has changed:
  identity is no longer a stub (ADR 0013), so a principal *can* be named. What is
  missing now is **roles** — every principal can do everything within their own
  organization, so "approved by" would record who clicked rather than who was
  entitled to, and a waiver anyone can grant themselves is not a control
  (ADR 0004).
- **PostgreSQL row-level security** — the strongest form of org scoping.
  **Its trigger has fired.** ADR 0003 deferred it until real authentication
  landed, and ADR 0013 landed it. Today isolation is enforced by every query
  filtering on `org_id`, proven by `OrgIsolationIT` and kept honest by
  `no_endpoint_escapes_this_test` — which is a test that a developer remembered,
  where RLS would be the database refusing regardless. Worth revisiting as its
  own decision rather than left on a list of things waiting for something that
  already happened.
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
