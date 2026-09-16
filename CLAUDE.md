# CLAUDE.md — OpsAtlas

Standing rules for every session in this repository. Read this first, follow it
without being re-asked, and update the **Current state** section when it stops
being true.

---

## 1. What this is

OpsAtlas is a Service Operations Platform: a control plane that catalogs
services, records who owns them, checks whether they are healthy, and scores
them against production-readiness policy.

It has two jobs, and they are both real:

1. A genuinely useful place to monitor deployed applications.
2. Portfolio evidence of senior backend, platform, distributed-systems,
   observability and solutions-architecture work.

The second job means the code is read by people deciding whether to hire. Honest
scoping beats broad scaffolding every time. An empty `terraform/` directory is
worse than no `terraform/` directory.

## 2. Current state

> Update this section whenever it becomes inaccurate. It is the first thing
> future sessions read.

- **Phase:** **phases 0–10 done, and 12's deployment is live.** Authentication (9) and
  packaging (10) are complete. The system measures something and can explain it:
  a Go observer probes declared health endpoints, the catalog reports what it
  found, and one identifier follows a request through the logs, the traces and
  the audit log. Phase 11 — the transactional outbox and platform events — waits
  for a demonstrated need (§6). Phase 12 was split: the **deployment is live**
  (ADR 0014), and **Terraform, Kubernetes, Helm and k6 still wait** — no longer
  circularly, since there is now something for them to describe. **Phases 13
  (deployments and drift) and 14 (incidents) are now written down**, which they
  never were — incidents were in the domain model, the navigation plan and the
  colour rules, and in no phase. The phase list is in `docs/roadmap.md`.
- **What exists:** the `service.yaml` v1 JSON Schema and fixtures; a running
  control plane that registers services from a real manifest and serves them —
  `POST`, `GET /{slug}`, `GET` (cursor-paged), `PUT` with `If-Match` — with the
  ADR 0002 ingestion pipeline, RFC 9457 problem responses, correlation IDs and
  real authentication (ADR 0013); a `governance` module scoring eleven declaration rules and
  auditing every change, both inside the registration transaction; Flyway schema
  for `organization`, `team`, `service`, `environment`, `policy_result`,
  `policy_result_check`, `audit_event`; a generated-and-drift-checked OpenAPI
  document with a typed TypeScript client; a Next.js console (catalog, detail,
  scorecard, register by paste, sources); an `integrations` module that polls
  watched GitHub repositories read-only and registers what they declare, with
  conditional requests and a `source` table recording sync state; an
  `operations` module folding probe results into per-environment and per-day
  counters; a Go observer in `apps/observer`; W3C trace context across both
  processes with OTLP export, and a collector, Tempo, Prometheus and Grafana
  behind the compose `telemetry` profile; a production deployment configuration
  in `deploy/production` with a runbook beside it; ADRs 0001–0015;
  `docs/design/tokens.md`; `docs/architecture/slice-one.md`.
- **Build:** pnpm workspace plus Gradle, `make` as the single entry point.
  `make up-app` builds and runs the control plane and console as containers,
  which is the arrangement a deployment uses; `make dev` runs the control plane
  from Gradle instead, which is faster to iterate on.
  `make up` starts **PostgreSQL, MongoDB and Storm-Gate** (the identity
  provider, from `ghcr.io/hoseacodes/storm-gate`), because since ADR 0013 a
  stack without an issuer is a control plane nobody can talk to. It also runs
  `make issuer-key` first, which generates a local RSA signing key into
  `deploy/compose/.env` — gitignored, local-only, never reused anywhere real.
  `make dev` runs the control plane on :8080 against compose PostgreSQL,
  `make dev-observer` the observer, and `make up-telemetry` the collector, Tempo
  (:3200), Prometheus (:9091) and Grafana (:3001). The telemetry stack is a
  compose profile, so plain `make up` stays a database and nothing else.
  **No JDK needs to be installed** — the build declares a Java 21 toolchain and
  Gradle provisions Temurin 21 itself. Go 1.27+ **is** required for
  `apps/observer`; it is installed here via Homebrew.
- **Tests:** `make test` — 15 schema fixtures, 73 console component and unit
  tests, 35 Go tests (race-clean) and 305 JVM tests, all passing. `make test-all`
  adds 61 Playwright tests against the real stack. Integration tests use
  Testcontainers and need a running Docker daemon; the Playwright tests need the
  stack running.
- **There is no `make lint`, `make build` or `make seed`**, and slice one's
  definition of done named all three. Nothing lints or formats either language:
  `apps/web/package.json` declares a `lint` script wrapping `next lint`, but
  eslint is not a dependency, so it cannot run. `make build` is missing even
  though both build paths exist inside the phase 10 Dockerfiles, so nothing
  checks that the jar and the console still build without building images.
  Seeding was superseded rather than dropped — a seeder needs a credential now
  (ADR 0013), and committing one is what §3 rule 5 forbids. None of this is a
  correctness gap: `make test` and CI verify the same six things a developer
  does. It is style enforcement that is absent, and it is written down in
  `docs/roadmap.md` under **Unfinished business from slice one** rather than
  rediscovered each session.
- **CI is green on `master`.** Run #3 on 2026-09-15, commit `4e23098`: all six
  jobs — contract fixtures (20s), observer (21s), console (54s), OpenAPI drift
  (130s), browser smoke (152s), control plane (201s). The browser smoke job is
  the one worth knowing passed, and it had been **silently broken** since
  authentication landed: it started PostgreSQL and nothing else, while
  `auth.setup.ts` needs an issuer to sign in against. It now runs `make up` and
  `make first-user` — what a developer runs — so a fresh runner brings
  PostgreSQL, MongoDB and Storm-Gate and generates a throwaway signing key into
  a gitignored `.env`, which is why `make check-secrets` still passes.
  Still do not describe a *later* state of the pipeline as passing without
  checking: `/repos/HoseaCodes/OpsAtlas/actions/runs` answers unauthenticated.
- **Only pushes to `master` run CI.** That is the trigger (`branches: [master]`)
  and `master` is now the default branch. `slice-one-foundation` still exists and
  pushes to it fire nothing — a pull request into `master` does. This is the
  failure that hid a non-running pipeline for the life of the project, so check
  which branch work is landing on before trusting a green history.
- **Database:** PostgreSQL 16 via `deploy/compose`. Flyway owns the schema;
  Hibernate runs `ddl-auto: validate` so entity/migration drift fails startup.
- **`deploy/production` is not `deploy/compose` with different values**, and the
  difference is the whole file (ADR 0014). The local file publishes PostgreSQL on
  5432 and MongoDB on 27017, the second with no authentication — correct on a
  laptop, an open database on a public IP. In the production file **only Caddy
  publishes anything**, and only 80 and 443; Mongo requires authentication; every
  secret is `${VAR:?message}` with no default, so a missing one stops the stack
  naming itself rather than starting on `ACCESS_TOKEN_SECRET`'s local fallback,
  which is a string committed to a public repository. Images are pulled by commit
  SHA or release tag and **never `latest`** — a running version you cannot name
  is one you cannot roll back. Do not "simplify" these back toward the local
  file's shape.
- **It is deployed, and the URL is `https://opsatlas.hoseacodes.com`.** A
  DigitalOcean droplet (2 vCPU, 4GB, Ubuntu 24.04) since 2026-09-15, running
  `deploy/production` at image tag `0.1.0` from GHCR, TLS from Let's Encrypt.
  Seven containers, one public hostname. The box is reached as `opsatlas@` with
  `~/.ssh/id_ed25519_ocean`; root login still works and **the SSH hardening was
  never applied** (see below).
- **There are still no backups**, no zero-downtime deploy and no redundancy —
  `docker compose up -d` stops and starts containers. Do not describe the data as
  surviving the box until something copies it off, and never call this highly
  available. DigitalOcean's droplet-level backups may be enabled; that has not
  been confirmed from inside the box.
- **Deploying it found three faults nothing local could have.** Worth knowing
  because each was a confident-looking configuration that had never met a host:
  the documented first start was impossible (the compose file derived
  `OPSATLAS_BOOTSTRAP_ISSUER` unconditionally, and the control plane rightly
  refuses half an identity); the console image was permanently unhealthy while
  serving traffic perfectly, because its healthcheck used `localhost`, which
  resolves to `::1` first while Next binds IPv4 only — the control plane is
  unaffected and was *checked*, since Tomcat listens dual-stack; and
  `publish.yml` produced a `latest` tag that it, and ADR 0014, both claimed it
  never did. All three are fixed. **The `latest` tags published for `0.1.0` still
  exist** and should be deleted from GHCR.
- **Deployment is pulled, not pushed (ADR 0015).** `deploy/production/VERSION`
  holds the image tag the box should run, committed; a systemd timer runs
  `converge.sh` every minute as `opsatlas`, which fetches `master`, hard-resets
  to it, and applies the declared version. **Deploying is a commit**, rolling
  back is `git revert`, and `git log -- deploy/production/VERSION` is the deploy
  history. There is deliberately **no SSH key in GitHub Actions**: this
  repository is public, and `docker` group membership on that box is
  root-equivalent. Do not add a push-based deploy job while production is the
  only target.
- **The box hard-resets to `master` every minute.** Anything edited there is
  lost without warning — that is the point, but it means the temporary-patch
  trick used during the first deployment no longer survives. Change the
  repository, not the machine.
- **The operator's password is on the box, not in this repository**, at
  `~/operator-credentials.txt` (mode 600, owned by `opsatlas`). It was generated
  on the droplet and has never been transmitted or printed. The account is
  `info@ambitiousconcept.com`, subject `6aa8b8d71a306f969fee6ac3`, issuer
  `https://auth.opsatlas.hoseacodes.com`.
- **The observer is packaged now too** (`apps/observer/Dockerfile`), because a
  deployment without it is a catalog rather than a monitoring platform: nothing
  probes, so every environment reads "never probed" forever. Alpine rather than
  scratch for two stated reasons — probes are HTTPS and need a CA bundle, and the
  healthcheck needs something that can make a request.
- **Every `/api/v1` endpoint requires a verified RS256 token** (ADR 0013).
  OpsAtlas is a resource server: it fetches public keys from the issuer's JWKS
  and holds no signing key, so it can check a token and cannot mint one. Open by
  design and nothing else: `/actuator/health`, `/actuator/info`, `/v3/api-docs`
  and the Swagger UI assets.
- **The metrics endpoints are no longer open.** Prometheus carries a credential
  of its own (`make prometheus-key`, mounted as a file it reads), sent on
  `Authorization: Bearer` because Prometheus cannot set an arbitrary header. A
  `BearerTokenResolver` refuses to hand anything with this system's key prefix to
  the token decoder, so a key and a JWT never contend for the same string.
  Verified live: anonymous scrape 401, credentialled scrape 200, and the
  Prometheus target back to `up`.
- **Swagger UI defaults OFF**; `OPSATLAS_SWAGGER_UI=true` turns it on. Its assets
  are permitted so the page can load and offer Authorize, while the endpoints it
  calls still require a credential. It answered 401 — and was therefore broken —
  for the whole window between authentication landing and this.
- **A verified token is not a membership.** The issuer mints tokens for its own
  accounts, which are not this catalog's users. `ProvisionedPrincipals` requires
  a row in `principal` matching the token's **issuer and subject together** — the
  pair, because two providers can hand out the same opaque id. An authenticated
  caller with no row gets **403, not 401**: they have a credential, it is simply
  not one this system knows, and retrying will not help.
- **The first principal comes from configuration**, not from the first caller.
  `OPSATLAS_BOOTSTRAP_ISSUER` / `_SUBJECT` / `_DISPLAY_NAME` provision one
  identity on startup, idempotently. "Whoever authenticates first becomes the
  administrator" is a race with the internet and losing it once is
  unrecoverable. Without this a fresh deployment admits nobody, because rows are
  created by somebody already inside.
- **⚠ Both clients are broken by authentication, and this is the most immediate
  work outstanding.** Turning it on refused every caller that does not present a
  token, which is correct and was also going to break everything that did not.
  - ~~The Go observer cannot report.~~ **Fixed.** It carries a key this system
    issued, sent as `X-OpsAtlas-Key` and configured as `OPSATLAS_API_KEY`;
    generate one with `make observer-key` and set the same value as
    `OPSATLAS_OBSERVER_KEY` on the control plane. Its own header rather than
    `Authorization`, which already means a JWT here. The audit log calls it
    `service:observer`, because it is a machine and naming a person who does not
    exist would be worse. Verified live: without the key the service list refuses
    to refresh, with it a pass reports `probed 4, applied 4`.
  - ~~The console cannot read the catalog.~~ **Fixed.** A person signs in at
    `/login`, the console holds *their* token in an httpOnly cookie and forwards
    it — it deliberately holds no credential of its own, because one would make
    every write in the audit log read as "the console did it". Verified live
    against a real Storm-Gate: redirect to sign-in, sign in, catalog renders,
    session survives a reload, sign out.
  - ~~`make e2e` is red.~~ **Green: 26 passed.** A Playwright `setup` project
    signs in once through the form and every other spec reuses that
    `storageState`; `signin.spec.ts` runs without it, since it is about not being
    signed in yet. Specs' direct API calls go as the operator via
    `e2e/support/session.ts`.
  - **The browser suite needs the whole stack**, identity provider included:
    `make up && make first-user && make dev`. `make first-user` creates the local
    account at the issuer, reads back the subject the issuer assigned, and
    records it in `deploy/compose/.env` so `make dev` provisions it on startup —
    a fresh stack otherwise admits nobody.
  - ~~The generated OpenAPI document declares no security scheme.~~ **Fixed.**
    It declares `bearerToken` and `serviceCredential` as alternatives, so Swagger
    UI can send either and the contract states what every endpoint requires.
    **`make check-openapi` stays red until the regenerated contract is
    committed** — that is the check doing its job, not a failure.
- **`OrgIsolationIT` proves scoping *and* authorization.** Its 30 tests plant a
  second organization's rows, and now a principal who belongs to it, so three of
  them authenticate as that caller and assert they reach their own catalog and
  not ours. Worth remembering why: until those existed, a resolver that ignored
  the principal's organization and always returned the seeded one would have
  passed the entire suite. Checked by mutation — doing exactly that now fails
  two tests.
- **Cross-organization isolation is verified** by `OrgIsolationIT` (30 tests),
  and it now covers **every org-scoped endpoint in the contract** — services,
  sources, the scorecard, the audit log, both health endpoints and observation
  ingestion. `GET /api/v1/policy/rules` is the only operation it does not cover,
  because the rule catalog is static and holds no tenant data.
  **This is now enforced, not remembered.** `no_endpoint_escapes_this_test`
  enumerates every `/api/v1` mapping and fails the build unless each appears in
  that test's `COVERED` set or in `NOT_TENANT_SCOPED` with a written reason — so
  adding an endpoint without covering it breaks the build rather than passing
  review. Its positive controls (registering and observing a service of our own
  in the same request) are what stop the leak checks passing against an endpoint
  that answers nobody; keep them.
- **Health is probe availability, and never an SLO.** It is the share of probes
  that succeeded from one vantage point against a health endpoint. A service can
  serve errors to every real user while its readiness endpoint answers happily.
  The field is `probeAvailability`, the API says so in its payload, and no
  document here may call it an SLO measurement.
- **An environment with no health record has never been probed**, which is not
  the same as being healthy. Absence is rendered as an outline, never as a
  reading. Do not default it.
- **There are no percentiles and cannot be**, from what is stored: a sum, a min
  and a max are not a distribution (ADR 0009). Report mean and max, called mean
  and max. Span durations are in Tempo after phase 8, but nothing aggregates
  them, so there is still no percentile to quote.
- **Observations are counters, never rows per probe.** Storage is environments ×
  retained days and must stay independent of probe frequency. The retention job
  is what makes that true, so it is not optional — and it is now covered by
  `ObservationRetentionIT` (6 tests), which calls it directly against a fixed
  clock because nothing else in the suite ever executes a `@Scheduled` method.
- **The retention window must stay wider than the window the console draws.**
  Retention keeps 35 days, the ribbon draws 30, and `ObservationRetentionIT`
  reads both from the real configuration rather than restating them — narrowing
  `OPSATLAS_RETENTION_DAYS` below the ribbon fails the build. A prune that landed
  inside the drawn window would leave a gap that reads as "never probed" rather
  than "no longer kept".
- **A window of N days means today and the N-1 days before it.** So a 30-day
  ribbon's oldest day is `today - 29`. Easy to get wrong by one in either
  direction, and it has been.
- **A key press or click that lands before the App Router hydrates is
  swallowed.** It is a real window, not a test artefact, and
  `waitForLoadState("networkidle")` does not close it — network idle is not
  hydration. Where a Playwright test presses something and expects a navigation,
  retry the press-and-assert pair with `toPass`, as `catalog.spec.ts` and
  `keyboard.spec.ts` do. That keeps the whole property under test. One assertion
  resisted even that and was removed as duplicate coverage rather than given a
  longer timeout — `keyboard.spec.ts` records why, so it is not re-added on the
  assumption it was only slow.
- **Keyboard focus is visible, and asserted.** §10 requires keyboard navigation
  with visible focus; `e2e/keyboard.spec.ts` tabs through the catalog and fails
  if anything takes focus without showing it. Deleting the `:focus-visible` rule
  from `globals.css` fails two of its four tests. It has to be a browser test —
  `:focus-visible` does not match a scripted `.focus()`, so a component test
  asserting the ring would be asserting its own simulation.
- **Tests start from the seeded state; `PostgresTestBase` guarantees it.** One
  Testcontainers instance is shared by every integration test, and a `@BeforeEach`
  in the base class truncates every table except `organization` and preserves
  whatever Flyway seeded. **A test only has to plant what it needs** — do not add
  hand-written `delete from` lists, which is what seven classes used to carry,
  in different orders, covering different tables. The tables are discovered from
  `pg_tables`, so one added by a future migration is covered without anybody
  remembering. Disabling that reset fails 96 of 257 tests, which is the measure
  of how much it was holding up.
- **The observer detects no drift.** §5 lists it as its job; it needs a
  deployment concept that does not exist. A failed probe is an outage, not a
  drift, and must not be described as one.
- **The correlation ID is the trace ID** when a request is traced; a
  caller-supplied `X-Correlation-Id` still wins over both and is tagged on the
  span (ADR 0011). Two things there are load-bearing and fail *silently* if
  disturbed: `CorrelationIdFilter` must stay ordered **after** Spring's
  observation filter (`HIGHEST_PRECEDENCE + 5`), or there is no span to read and
  every ID quietly becomes a generated UUID; and `TracingConfiguration` must stay
  unconditional, because a `@ConditionalOnProperty` on it once stopped the class
  loading and left a no-op propagator in place with nothing failing.
- **Probes must never carry OpenTelemetry context.** A probe reaches a service
  OpsAtlas does not own, and injecting our identifiers into their headers — and
  so their logs — is not our decision to make. Only the catalog and reporter
  clients use `tracing.Transport`; `apps/observer/internal/tracing/tracing_test.go`
  fails if a probe sends `traceparent`.
- **Traces are sampled at 100% and logs are not shipped.** Sampling is right at
  this volume and wrong at a hundred times it; Loki is deferred because stdout
  JSON already carries the correlation ID. Both are recorded in ADR 0011 rather
  than left as silent gaps.
- **Tests run with OTLP export off** (`src/test/resources/application.properties`).
  An exporter retrying against a collector that is not there once turned a
  20-second suite into four minutes. `TraceCorrelationIT` turns tracing back on
  for itself, deliberately pointed at a closed port.
- **The scorecard scores manifests, not running systems.** All eleven rules are
  declaration checks. `GET /api/v1/policy/rules` says so in its payload; do not
  describe them as production-readiness checks.
- **Bump `PolicyCatalog.VERSION`** whenever a rule is added, removed, or changed
  in a way that alters its verdict. It is what keeps an old score readable as
  what it meant when it was computed. `PolicySetIT` enforces it from both
  directions: the rule-id set is pinned beside the version it stands for, and the
  README's fleet table is asserted against what the scorer actually produces, so
  a rule that quietly changes its mind about a manifest fails the build.
- **The README's fleet table is a test fixture, not prose.** `PolicySetIT` parses
  it and compares it to real scores. When it fails it prints the correct table in
  the README's own column widths, ready to paste. **Adding an example manifest
  means adding it to `FLEET` and to that table** — and that is now enforced:
  `no_example_manifest_escapes_the_fleet_table` reads `examples/services` off
  disk, so a manifest nobody documented fails the build instead of quietly making
  the README describe a fleet that is missing one. It was added because exactly
  that happened when `docs-portal` was written.
- **`docs-portal` is the empty-dependency fixture.** It is the only manifest that
  states `spec.dependencies: []` — "this service calls nothing", which is an
  answer, as against an absent key, which is a question nobody answered. Both the
  scorecard and the detail page distinguish them, and before `docs-portal`
  existed the second branch was covered by a unit test and by nothing a browser
  ever rendered. It is also the only manifest declaring every field the overview
  can show, which is what `declarations.spec.ts` uses to assert that a fully
  declared service shows no "none declared" anywhere.
- **A file a test reads at runtime must be declared as a Gradle input.** README.md,
  `examples/services` and `packages/contracts/schemas` are. An undeclared one
  leaves the test task up-to-date and the test unrun — green for having been
  skipped by the exact change it exists to catch.
- **Run `make openapi` after any controller or response-shape change**, and
  commit the result. CI fails on the difference otherwise (ADR 0005).
- **Extend `OrgIsolationIT` whenever an endpoint is added.** An endpoint it does
  not cover has unverified isolation and must be described that way.
- **A service can now be edited and deleted from the console.** `PUT` existed
  with no UI and `DELETE` did not exist; both are reachable from the detail
  page's Manage section, each sending `If-Match` built from the version that
  page was rendered with. **Retiring is the recommended path** —
  `spec.lifecycle: retired` through the edit form keeps the entry, its
  scorecards and its probe history. `DELETE` is for a registration that should
  not exist.
- **The hard delete is safe because of the schema, not because of care.**
  `environment`, `policy_result` and the observation tables cascade;
  `source.service_id` is set null so a watched repository stays watched; and
  **`audit_event` declares no foreign key to `service`**, so `service.deleted`
  outlives the row it describes. Do not add such a foreign key — it would turn
  every deletion into the erasure of its own record. Deleting a service that a
  source still watches is temporary: the next sync registers it again.
- **The console's environment label is configuration, not decoration.** It read
  the hardcoded string `local` and so announced the production deployment as a
  laptop. It is now `OPSATLAS_ENVIRONMENT`, read at request time so one image
  still runs anywhere; a `NEXT_PUBLIC_` variable would bake it in at build.
- **Deployments are reported, never discovered (ADR 0017).** A pipeline POSTs to
  `/api/v1/services/{slug}/environments/{name}/deployments`; OpsAtlas records it
  and **does not verify it**. A row says somebody claimed a version went out, not
  that it is running. Three things there are load-bearing: `version` is free text
  because a version is a semver, a date stamp or a branch name depending on whose
  pipeline is talking; `deployed_at` comes from the reporter, not the server's
  clock, or "in place for" silently measures how long ago OpsAtlas was told; and
  the **idempotency key is required and never generated**, because a key this
  endpoint invented would differ on every retry — which is the exact failure it
  exists to prevent.
- **A constraint violation aborts the whole PostgreSQL transaction.** The
  idempotent write is "insert, and on conflict read back the row that beat us",
  and doing both in one transaction cannot work: every statement after the
  violation fails with *current transaction is aborted*. `DeploymentWriter`
  exists solely to hold the insert in `REQUIRES_NEW` so the failure is confined.
  Found by a test, not by review — do not collapse it back into one method.
- **`converge.sh` reports OpsAtlas's own deploys**, keyed on version and commit,
  so the platform's own catalog entry carries real promotion data. It is
  **best-effort on purpose**: a failed report must never fail a convergence that
  already succeeded, or a reporting hiccup teaches everyone to ignore the exit
  code. It needs `deploy/production/deploy-key`; without it the script says so
  and moves on.
- **Drift is still not built, and must not be implied.** This supplies the
  declared version only. Drift is declared-versus-*observed*, and nothing exposes
  a running version to compare against — a service declaring no version endpoint
  would have to read as *not checkable*, never as *no drift*. Two environments on
  different versions is a **promotion**, which the console says outright. Written
  up as **phase 15** in `docs/roadmap.md`, including the two timing traps that
  will make it cry wolf: a rollout looks exactly like drift for its duration, and
  replicas disagree with each other while one is in progress.
- **Instance counts are not coming from here.** "Instances 9 / 14" and "5 pods
  failing readiness" are orchestrator facts. A probe reaches one URL through
  whatever sits in front of it and cannot see how many replicas answered. The
  Health checks section says so rather than leaving a blank that reads as a bug.
  **Phase 17** in `docs/roadmap.md`: the cost is cluster credentials on a box
  where `docker` membership is already root-equivalent, not the API work, and it
  stays **read-only** — the moment OpsAtlas can act on a cluster it is a deploy
  tool with a catalog attached. A service that is not orchestrated must read
  *not applicable*, never `0 / 0`, which looks like everything being down.
- **On-call is declared, never observed (ADR 0016).** `spec.operations.oncall`
  carries a rotation URL, a `coverage` enum and an escalation target, and
  `oncall-declared` scores it — REQUIRED at tier 1 and 2, NOT APPLICABLE at
  tier 3. It is **stricter at tier 1**: a rotation declaring `business-hours`
  passes at tier 2 and fails at tier 1, because a tier 1 service is paged around
  the clock by definition. **Nothing here says who is on call now** — that is
  live state in a paging provider, and a name this system could not refresh
  would go stale into the one page somebody reads at 03:00. Do not add a
  who-is-on-call field without the integration behind it.
- **The policy set is eleven rules at `2026-09-15.1`.** Adding `oncall-declared`
  moved every stored score's denominator; the README fleet table was regenerated
  from what the scorer actually produces, which is what `PolicySetIT` prints on
  failure.
- **`spec.health.readiness` and `.liveness` are rendered now, and were not.**
  They were in `ServiceDetail.EnvironmentView` and shown nowhere — the same
  "validated, stored, dropped" failure as the manifest fields below, found by
  reading the page against the prototype. The Health checks section states
  plainly that the probe interval and timeout are the *observer's* configuration
  and that nothing can count replicas behind a URL, so neither is invented.
- **The Operations links footer lists only links that resolve to somewhere.**
  Runbook (when it is an https URL rather than a repository path), dashboard and
  rotation. The prototype's Traces, Logs and API spec are deliberately absent:
  there is no log store (Loki is deferred, ADR 0011), nothing knows where
  Grafana lives, and there is no `apiSpec` field. A dead link is a UI element
  implying a capability the backend does not have.
- **Five more manifest fields are rendered now, and were not before.**
  `spec.operations.runbook`, `spec.operations.slo`, `spec.dependencies` and
  `spec.journeys` each moved a scorecard check and nothing else;
  **`spec.operations.contact` moved nothing at all** — a team could declare a
  chat channel and no part of this system would mention it again. The readers
  are in `apps/web/lib/manifestLinks.ts` and each has a test, because this is
  the second time a validated field reached no reader. Three things there are
  deliberate: a repository-relative runbook stays a **path**, since
  `metadata.repository` is `owner/name` with no host and linking it would mean
  guessing github.com; the **SLO target sits away from the ribbon** and says
  outright that nothing measures against it; and an absent `spec.dependencies`
  renders differently from an empty one, which is the distinction the schema
  exists to keep. The dependency list states that it is **not a blast radius**,
  because nothing computes the reverse edges.
- **`spec.observability.dashboard` is rendered now, and was not before.** It was
  validated, parsed and dropped — no column, no rule, nothing shown — so
  declaring one did nothing. `ServiceDetail` already carried the whole manifest,
  so the fix was rendering rather than plumbing. The link is scheme-checked in
  the console as well as the schema: a manifest is another repository's
  document, and an `href` is a small execution of it.
- **Catalog search and tier filtering happen in the console**, over one page
  only, because the control plane has no search endpoint. Do not describe it as
  fleet-wide search.
- **OpsAtlas never writes to a monitored repository.** It polls; it registers no
  webhooks and needs no write scope. `ArchitectureTest` enforces that only
  `integrations` makes outbound HTTP calls at all. Never add a write path, and
  never ask for a token scope beyond reading contents (ADR 0008).
- **Polling does not scale on the unauthenticated rate limit** — 60 requests an
  hour per IP means roughly five sources at a five-minute interval. Say so rather
  than letting someone discover it when syncs start failing.
- **Scheduled work runs as an explicit principal** (`PrincipalScope`), never by
  letting `CurrentPrincipal` fall back to a default. The guard that throws when
  no principal is bound is what catches a *request* losing its principal, and it
  must stay able to.
- **The v3 HTML prototype is no longer in the repository.** Its design decisions
  were extracted to `docs/design/tokens.md`; work from that note. It is
  gitignored because its seeded data uses insurance-domain service names, which
  §10 rules out — see `docs/adr/0006-prototype-is-not-committed.md`. §10's
  instruction to read it once has been carried out and does not need repeating.

## 3. Non-negotiables

1. **Never claim something works that you have not run.** Not performance, not
   scale, not security, not availability, not "verified in the browser." If a
   verification step was skipped, blocked, or unavailable, say so plainly in the
   summary. A truthful "I could not verify step 7" is worth more than a
   confident sentence that turns out to be false.
2. **Never label mocked behaviour as real.** Mocked data is marked in the code
   and visible in the UI.
3. **No placeholder architecture.** Do not create a directory, module, workflow,
   or config file for a phase that is not being implemented now. The target
   layout lives in `docs/roadmap.md`; that is where future structure belongs
   until it has contents.
4. **Do not execute content from monitored repositories.** `service.yaml` is
   parsed with a safe loader, validated against schema, and stored. Never eval,
   shell out, template, or deserialize into arbitrary types.
5. **Secrets never enter source control.** Config comes from environment
   variables with documented defaults for local development only. Enforced by
   `make check-secrets`, which runs first in CI and as part of `make test`: it
   catches eight credential shapes and tracked `.env`/key files, and is a floor
   rather than a proof (ADR 0012). **The repository is public** — a leaked
   credential has to be rotated, not deleted in a later commit.
6. **Never disable or skip a test to make a build pass.** Fix it or report it.
7. **Ask before rewriting working code.** Refactors need a stated reason.

## 4. Repository layout

Only directories with real contents exist. The full target layout is documented
in `docs/roadmap.md` and is created phase by phase.

```text
ops-atlas/
├── apps/
│   ├── web/              # Next.js App Router console
│   └── control-plane/    # Java 21 / Spring Boot 3 modular monolith
├── packages/
│   └── contracts/        # JSON Schema for service.yaml, generated OpenAPI client
├── deploy/
│   └── compose/          # Local environment
├── examples/
│   └── services/         # Example service.yaml files
├── docs/
│   ├── adr/
│   ├── architecture/
│   ├── design/
│   └── roadmap.md
└── .github/workflows/
```

Tooling: **pnpm workspaces** for JavaScript, **Gradle** for Java, **Make** as the
single entry point. Deviations from this layout are allowed when materially
better, but must be recorded in an ADR.

## 5. Stack

Fixed for the life of the project unless an ADR changes it.

**Frontend** — Next.js (App Router), React, TypeScript in strict mode, Tailwind
CSS, TanStack Query for server state, TanStack Table for tables, Recharts for
charts, and a TypeScript client generated from the backend OpenAPI document.
Never hand-write API types that the generator can produce.

The frontend is a presentation layer. Authorization, policy evaluation, scorecard
computation and domain rules live in the control plane, never in Next.js. If a
React component is deciding whether something passes policy, that logic is in the
wrong place.

**Control plane** — Java 21, Spring Boot 3, Gradle, Spring Web, Spring
Validation, Spring Security, Spring Data JPA, PostgreSQL, Flyway, Actuator,
Micrometer, OpenTelemetry, Springdoc OpenAPI, Testcontainers.

A modular monolith, not microservices:

```text
com.ambitiousconcepts.opsatlas
├── catalog        # services, environments, registration, service.yaml ingestion
├── integrations   # external systems (GitHub, CI, cloud) — later phases
├── governance     # policies, scorecards, exceptions, audit events
├── operations     # desired state, observations, deployments, incidents
├── identity       # organizations, teams, principals
└── shared         # cross-cutting: errors, pagination, correlation, time
```

Modules talk through public interfaces in their own package. No reaching into
another module's internals or repositories. `shared` depends on nothing.

**Observer** (later phase) — Go. Pulls registered services from the control-plane
API, probes health endpoints with bounded concurrency, timeouts and jittered
retries, records availability and response time, detects desired-versus-observed
drift, publishes normalized observations back, exposes Prometheus metrics, shuts
down gracefully.

Adding any dependency not listed above requires a one-line justification in the
PR or summary.

## 6. Architectural planes

- **Presentation** — Next.js console.
- **Control** — catalog, ownership, policy, scorecards, desired state, incidents.
- **Execution** — Go observers, probes, reconciliation.
- **Event** — durable normalized platform events.
- **Telemetry** — metrics, logs, traces.
- **Data** — the monitored applications themselves.

Storage rules:

- PostgreSQL is the system of record for platform metadata. **Never** store raw
  metrics, logs or traces in it. Observations are stored as rolled-up state, not
  as a time series.
- Redis only for derived read models or caching, and only once there is a
  demonstrated need. Not in slice one.
- Kafka only for durable asynchronous processing. Never for request-response.
  Not in slice one.

## 7. Domain model and tenancy

```text
Organization
└── Team
    └── Service
        └── Environment
            ├── DesiredState
            ├── Observation
            ├── Deployment
            ├── PolicyResult
            └── Incident
```

**Tenancy decision, so this does not get re-litigated each session:** every tenant-scoped
table has `org_id UUID NOT NULL` with a foreign key to `organization`, and every
query filters on it. The organization comes from the authenticated caller:
`TokenPrincipalResolver` reads the verified token and looks the caller up in the
`principal` table (ADR 0013). The stub that preceded it returned one seeded
organization for every request, and ADR 0003's claim that swapping that single
implementation would be the entire migration path turned out to be exactly
true — no query, no table and no caller of `CurrentPrincipal` changed.

This is single-tenant data modelling done so multi-tenancy is possible later. The
README says exactly that. **Do not describe the project as supporting
multi-tenancy** until authorization is implemented and tested.

Identifiers are UUIDv7 where ordering helps (rows), UUIDv4 elsewhere. All
timestamps are UTC, stored as `timestamptz`, serialized as ISO-8601 with `Z`.
Database constraints back up every application-level validation — not-null,
foreign keys, unique constraints, check constraints. Application validation is
for good error messages; the database is for correctness.

## 8. The service.yaml contract

Monitored repositories declare themselves in `service.yaml`. Slice-one shape:

```yaml
apiVersion: opsatlas.ambitiousconcepts.io/v1
kind: Service
metadata:
  name: orders-api
  displayName: Orders API
  owner: ambitious-concepts
  repository: ambitious-concepts/orders-api
spec:
  tier: 2
  runtime: spring-boot
  environments:
    - name: production
      url: https://orders.example.com
  health:
    readiness: /actuator/health/readiness
    liveness: /actuator/health/liveness
  observability:
    serviceName: orders-api
  operations:
    slo:
      availability: 99.9
      window: 30d
    runbook: docs/runbook.md
  dependencies:
    - postgres
    - payments-api
```

Rules:

- The JSON Schema is versioned and lives in `packages/contracts/`. `apiVersion`
  selects the schema version; an unknown version is a validation error, not a
  guess.
- Parse with a safe YAML loader. No custom tags, no anchors that expand
  unboundedly, size-capped input.
- Validation errors are actionable and structured: JSON Pointer path, what was
  expected, what was found. "Invalid YAML" is not an acceptable error message.
- Store normalized metadata plus the source repository and the schema version the
  document was validated against.
- Registration computes an initial scorecard and writes an audit event. Both are
  part of the same transaction as the service row.

## 9. API conventions

Versioned under `/api/v1`. Slice-one surface is defined in the slice prompt; the
conventions below apply to every endpoint ever added.

- OpenAPI generated from the code, not hand-maintained.
- Cursor-based pagination for every collection. No offset pagination.
- Structured errors, one shape everywhere: `type`, `title`, `status`, `detail`,
  `correlationId`, and a `violations[]` array for validation failures.
- Optimistic locking (`version` column, `If-Match` or a body field) wherever
  concurrent updates are plausible — desired state above all.
- Idempotency keys for observation and webhook ingestion, so a retried POST does
  not double-write.
- Correlation ID accepted from `X-Correlation-Id` or generated, put in the MDC,
  logged on every line, echoed in every response and error.
- Trace context propagated (W3C `traceparent`).
- Additive, backward-compatible changes within a version.

## 10. UI rules

The prototype `service-operations-platform-v3.html` is the visual and product
reference. **Read it once**, extract the design decisions into
`docs/design/tokens.md`, and work from that note afterwards rather than
re-reading 123 KB every session. Do not port its markup or its mock data.

Its defining decision, which must survive the rebuild: **status is carried by
shape, height and weight, never by hue.** Specifically —

- The three-notch health meter, where bar heights encode healthy / degraded /
  down.
- The 30-day ribbon, where bar height encodes daily SLO attainment.
- Scorecard results as a thin check versus a filled square, not green versus red.
- Severity as a count of filled marks.
- Colour rationed to four jobs and no others: selection, focus ring, error-budget
  fill, open-incident emphasis.

The test: screenshot any page, convert to greyscale, and every status must still
be readable. Colour may reinforce a signal; it may never be the only carrier of
one.

Also required: dense but readable tables, clear hierarchy, responsive to 375px,
the dark theme carried over, keyboard navigation with visible focus, and real
loading, empty, stale, partial-failure and error states — the partial-failure
state especially, since a control plane whose upstreams are down is the normal
case, not the exception.

Prohibited: emojis; buttons that do nothing; any UI element that implies a
capability the backend does not have. Mocked data is labelled in the interface,
not only in a comment.

Example services are neutral: `orders-api`, `pricing-engine`, `billing-worker`,
`customer-portal`, `identity-bff`. Never insurance-specific names, never anything
that reads as derived from an employer's systems.

Eventual navigation: Catalog, Incidents, Scorecards, Golden Paths, Costs,
Architecture, Audit Log. A nav item appears only when its page is real.

## 11. Testing and verification

Every functional change ships with tests. Backend: unit tests for domain logic,
Testcontainers integration tests against real PostgreSQL for repositories and
API. Frontend: component and behaviour tests, querying by role and accessible
name rather than test IDs where possible.

What you may and may not claim:

- You **cannot** see a browser. Never write "confirmed it appears in the
  browser." Assert through a Playwright smoke test or say it was not verified.
- `curl` plus an assertion on the JSON is a real verification. A screenshot
  description is not.
- If a command fails for environmental reasons (no Docker daemon, no network),
  report it as unavailable rather than passing.

Every session that changes code ends with a report: what works, what changed,
commands to run it, verification actually performed, and known limitations.

## 12. Git

Commit in logically scoped units as you go — not one commit at the end of a
session. Conventional commit subjects (`feat:`, `fix:`, `docs:`, `test:`,
`chore:`). Never amend or force-push commits that already exist on a branch you
did not create in this session. Never commit `.env`, credentials, or generated
build output.

## 13. Documentation upkeep

- `README.md` — purpose, architecture summary, stack, local setup, test commands,
  what works today, **an explicit mocked-versus-real table**, and roadmap.
- `docs/adr/` — one ADR per significant decision, numbered, with context,
  decision, consequences, and the alternatives rejected. An ADR that lists no
  downside is not finished.
- `docs/architecture/` — Mermaid diagrams, kept current.
- `docs/roadmap.md` — the phase list and the target repository layout.

When architecture changes, documentation changes in the same commit.

## 14. Phase order

Slice one is the catalog vertical slice only. Do not implement, scaffold, or
configure any of the following until its phase is active: GitHub App and
webhooks, the Go observer, OpenTelemetry pipeline, Prometheus/Loki/Tempo/Grafana,
transactional outbox and events, Redis read models, incidents and deploy gates,
Terraform, Kubernetes and Helm, k6 load tests.

If one of these seems necessary to finish the current slice, stop and say so
rather than quietly expanding scope.