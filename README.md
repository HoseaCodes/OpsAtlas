# OpsAtlas

A service operations control plane: it catalogs services, records who owns them,
checks whether they are healthy, and scores them against production-readiness
policy.

> **Status: phase 1 of slice one.** The control plane runs and serves an API,
> against a real PostgreSQL. The catalog it serves is **empty** — registration
> arrives in phase 2. Everything else in this README is described as what it
> will be, and labelled as such. The
> [what actually works](#what-actually-works-today) table below is the
> authoritative answer.

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
| Control plane (Java 21 / Spring Boot) | **Real** | `make test` — 31 tests |
| PostgreSQL schema and Flyway migrations | **Real** | `SeedConsistencyIT`, and Hibernate `ddl-auto: validate` refuses to start on drift |
| `GET /api/v1/services` with cursor pagination | **Real, and returns an empty page** | `CatalogApiIT`, plus curl against a running server |
| RFC 9457 problem responses with `correlationId` and `violations[]` | **Real** | `CatalogApiIT` |
| Correlation ID accepted, generated, echoed | **Real** | `CatalogApiIT` |
| Module boundaries between `catalog`, `identity`, `shared` | **Real, enforced** | `ArchitectureTest` — 6 rules |
| Org scoping via stub `PrincipalResolver` | **Real, but unauthenticated** | `SeedConsistencyIT`; cross-org isolation is **not yet tested** (phase 3) |
| Service registration API | **Not built** | phase 2 |
| Scorecard evaluation | **Not built** | phase 3 |
| Web console | **Not built** | phase 4 |
| Health monitoring, SLO attainment, 30-day history | **Not built** | phase 7 — needs the Go observer |
| Dependency graph and blast radius | **Not built** | phase 7 |
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
- **Nothing has been load-tested, security-tested, or run in production.** No
  performance, scale, availability or security claim appears anywhere in this
  repository, because none has been measured.

---

## Architecture

Six planes, of which slice one builds parts of two:

| Plane | What | Slice one |
|---|---|---|
| Presentation | Next.js console | phase 4 |
| **Control** | catalog, ownership, policy, scorecards | **phases 1–3** |
| Execution | Go observers, probes, reconciliation | phase 7 |
| Event | durable normalized platform events | phase 9 |
| Telemetry | metrics, logs, traces | phase 8 |
| Data | the monitored applications themselves | — |

The control plane is a **modular monolith**, not microservices:

```text
com.ambitiousconcepts.opsatlas
├── catalog        services, environments, registration, service.yaml ingestion
├── governance     policies, scorecards, audit events
├── identity       organizations, teams, principals
└── shared         errors, pagination, correlation, time — depends on nothing
```

Modules talk through public interfaces in their own `api` package. Nothing
reaches into another module's `internal` package, and a test fails the build if
it does. `integrations` and `operations` are in
[`docs/roadmap.md`](docs/roadmap.md) and not on disk, because they have no
contents yet. See [ADR 0001](docs/adr/0001-modular-monolith-as-one-gradle-module.md).

### Key decisions

| ADR | Decision |
|---|---|
| [0001](docs/adr/0001-modular-monolith-as-one-gradle-module.md) | One Gradle module, boundaries enforced by an ArchUnit test |
| [0002](docs/adr/0002-safe-service-yaml-ingestion.md) | Parse to plain data, validate against schema, *then* bind — never deserialize repository content into arbitrary types |
| [0003](docs/adr/0003-org-scoping-stub.md) | Explicit `orgId` on every query, behind a swappable `PrincipalResolver` |
| [0004](docs/adr/0004-scorecard-rule-model.md) | Typed check beans, versioned rule sets, per-check rows, `NOT_APPLICABLE` as a first-class outcome |
| [0005](docs/adr/0005-openapi-generated-committed-drift-checked.md) | OpenAPI generated from code, committed, and drift-checked in CI |
| [0006](docs/adr/0006-prototype-is-not-committed.md) | The v3 HTML prototype stays out of the repository |

---

## Stack

The target stack is fixed for the life of the project. What is **wired today** is
marked; the rest is listed so the direction is clear, not to imply it is present.

**Control plane** — Java 21 ✓, Spring Boot 3 ✓, Gradle ✓, Spring Web ✓, Spring
Validation ✓, Spring Data JPA ✓, PostgreSQL ✓, Flyway ✓, Actuator ✓,
Testcontainers ✓, ArchUnit ✓. Not yet added: Spring Security (deliberately — the
starter would put every endpoint behind a generated password, which is a security
posture the project does not actually have), Micrometer, OpenTelemetry, Springdoc
OpenAPI (phase 4).

**Console** — Next.js App Router, React, TypeScript in strict mode, Tailwind,
TanStack Query, TanStack Table, Recharts, and a TypeScript client generated from
the backend's OpenAPI document. Phase 4; none of it exists yet. Authorization,
policy evaluation and scorecard computation live in the control plane, never in
the console.

**Observer** — Go. Phase 7.

**Contract tooling** — Ajv ✓ for schema validation in the workspace.
snakeyaml-engine and networknt/json-schema-validator join on the JVM side in
phase 2, when there is something to ingest.

---

## Local setup

### Requirements today

| Tool | Version | Needed for |
|---|---|---|
| Node | ≥ 20.11 | the contract checks |
| pnpm | 10.x | the contract checks |
| GNU Make | 3.81+ | the entry point |
| Docker | any recent | PostgreSQL, and the integration tests |
| Java | **none required** | Gradle provisions a Temurin 21 toolchain itself |

You do not need a JDK installed. The build declares a Java 21 toolchain and
Gradle downloads one on first use — verified on a machine with only JDK 17 and
25 present.

### Running it

```bash
make install    # install workspace dependencies
make up         # start PostgreSQL and wait until it is accepting connections
make dev        # start the database, then run the control plane on :8080
make test       # every test there is: schema fixtures + 31 JVM tests
make            # list the targets that exist
```

Then:

```bash
curl -s localhost:8080/api/v1/services
# {"items":[],"nextCursor":null}
```

That empty array is the honest current state, not a failure. Registration lands
in phase 2.

```bash
# The error shape, which is real now and will not change:
curl -s 'localhost:8080/api/v1/services?cursor=nope' | jq
```

`make check-examples` validates the six example manifests, then asserts that each
of the eight invalid fixtures fails at exactly the JSON Pointer recorded in
`examples/services/invalid/expected.json`. It fails if a good manifest breaks
*and* if a bad manifest stops being bad — a checker that cannot fail is worthless,
and both directions are exercised.

---

## Repository layout

Only directories with real contents exist. The full target layout is in
[`docs/roadmap.md`](docs/roadmap.md).

```text
OpsAtlas/
├── apps/control-plane/     Java 21 / Spring Boot 3 modular monolith
│   └── src/main/java/com/ambitiousconcepts/opsatlas/
│       ├── shared/         errors, pagination, correlation — depends on nothing
│       ├── identity/       the org-scoping stub
│       └── catalog/        services and environments
├── packages/contracts/     service.yaml JSON Schema and the fixture validator
├── deploy/compose/         local PostgreSQL
├── examples/services/      six valid manifests, eight invalid fixtures
└── docs/
    ├── adr/                0001–0006
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
| 2 | Ingestion and registration | next |
| 3 | Scorecard and audit |  |
| 4 | OpenAPI client and the web console |  |
| 5 | CI and documentation close-out |  |

After slice one: GitHub sync, the Go observer, the telemetry pipeline, events,
and deployment. Details and the reasoning for the ordering are in
[`docs/roadmap.md`](docs/roadmap.md).
