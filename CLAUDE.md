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

- **Phase:** slice one, phases 0 and 1 complete. Phase 2 (ingestion and
  registration) is next. The phase list is in `docs/roadmap.md`.
- **What exists:** the `service.yaml` v1 JSON Schema and fixtures; a running
  control plane serving `GET /api/v1/services` (an empty page — registration is
  phase 2) with cursor pagination, RFC 9457 problem responses, correlation IDs
  and the org-scoping stub; Flyway schema for `organization`, `team`, `service`,
  `environment`; ADRs 0001–0006; `docs/design/tokens.md`.
- **Build:** pnpm workspace plus Gradle, `make` as the single entry point.
  `make dev` runs the control plane on :8080 against compose PostgreSQL.
  **No JDK needs to be installed** — the build declares a Java 21 toolchain and
  Gradle provisions Temurin 21 itself (this machine has only 17 and 25).
- **Tests:** `make test` — 14 schema fixtures plus 31 JVM tests, all passing.
  Integration tests use Testcontainers and need a running Docker daemon.
- **Database:** PostgreSQL 16 via `deploy/compose`. Flyway owns the schema;
  Hibernate runs `ddl-auto: validate` so entity/migration drift fails startup.
- **Unverified, and described as such:** cross-organization isolation. The design
  is in ADR 0003; `OrgIsolationIT` cannot exist until registration does.
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
   variables with documented defaults for local development only.
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
query filters on it. Until authentication exists, the organization is resolved by
a single stub `PrincipalResolver` in `identity` that returns the one seeded
organization. Swapping that resolver for real auth is the entire migration path,
and it is marked with a `TODO(auth)` comment.

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