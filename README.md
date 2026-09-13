# OpsAtlas

A service operations control plane: it catalogs services, records who owns them,
checks whether they are healthy, and scores them against production-readiness
policy.

> **Status: slice one complete.** The catalog vertical slice works end to end:
> a `service.yaml` is submitted through the console, validated, stored, scored
> against ten policy rules and audited, and the result is browsable. **Nothing in
> this system observes anything** — there is no health monitoring, and there will
> not be until the observer (phase 7). Everything else in this README is described
> as what it will be, and labelled as such. The
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
| Control plane (Java 21 / Spring Boot) | **Real** | `make test` — 162 JVM tests |
| PostgreSQL schema and Flyway migrations | **Real** | `SeedConsistencyIT`, and Hibernate `ddl-auto: validate` refuses to start on drift |
| `POST /api/v1/services` — register from a `service.yaml` | **Real** | `RegistrationApiIT`, plus 13 curl assertions against a running server |
| Safe YAML ingestion — size cap, no alias expansion, no type construction | **Real** | `ManifestValidationTest` — billion-laughs, `!!java` tags, duplicate keys and a 70 KiB body are all refused |
| Located validation errors (JSON Pointer + keyword + sentence) | **Real** | every fixture in `examples/services/invalid/` asserted against `expected.json`, by both the JVM and the Node checker |
| Idempotent re-registration by manifest digest | **Real** | `RegistrationApiIT` — a replay returns 200 and does not move `version` |
| `PUT` with `If-Match` optimistic locking (428 / 412) | **Real** | `RegistrationApiIT` |
| `GET /api/v1/services` and `/{slug}` with cursor pagination | **Real** | `CatalogApiIT`, `RegistrationApiIT` |
| Cross-organization isolation | **Real, and verified** | `OrgIsolationIT` — 7 tests, including one proving the database refuses a cross-org reference |
| OpenAPI document generated from the code, drift-checked | **Real** | `make check-openapi` fails the build on any difference |
| Swagger UI over that document, at `/swagger-ui.html` | **Real** | served by springdoc; on by default locally, off when `OPSATLAS_SWAGGER_UI=false` |
| Typed TypeScript client, no hand-written API types | **Real** | `make typecheck` |
| Web console — catalog, detail, scorecard, register by paste | **Real** | 29 component tests, 9 Playwright tests against the real stack |
| A copyable prompt on `/register`, generated from the schema and the live rules | **Real** | `manifestPrompt.test.ts` walks the real schema and fails if a field is missing from the prompt; a Playwright test reads the clipboard |
| Loading / empty / partial-failure / error / never-observed states | **Real** | `states.test.tsx`, and the detail page settles its two requests independently |
| CI pipeline | **Written, never executed** | `.github/workflows/ci.yml` — there is no remote to run it |
| Scorecard — ten declaration rules, tier-conditional | **Real** | `PolicyCheckTest` (49), `ScorecardApiIT` (12) |
| `NOT_APPLICABLE` as a real outcome, with a moving denominator | **Real** | a tier 3 service is scored out of 7, not 10 |
| Scorecard + audit written in the registration transaction | **Real, and verified** | `ScorecardApiIT` — a forced mid-registration failure leaves no service, no scorecard and no audit row |
| Audit log with correlation IDs, cursor-paged | **Real** | `ScorecardApiIT` |
| `GET /api/v1/policy/rules` — the rule set and its rationale | **Real** | `ScorecardApiIT` |
| RFC 9457 problem responses with `correlationId` and `violations[]` | **Real** | `CatalogApiIT` |
| Correlation ID accepted, generated, echoed | **Real** | `CatalogApiIT` |
| Module boundaries between `catalog`, `governance`, `identity`, `shared` | **Real, enforced** | `ArchitectureTest` — 9 rules |
| Org scoping via stub `PrincipalResolver` | **Real, but unauthenticated** | `SeedConsistencyIT`, `OrgIsolationIT` |
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
| [0007](docs/adr/0007-service-identity-and-re-registration.md) | Registration is keyed by where the manifest lives; idempotency by content digest; POST never overwrites |

Diagrams of what exists — context, modules, the registration sequence and the
data model — are in [`docs/architecture/slice-one.md`](docs/architecture/slice-one.md).

### The scorecard, and what it does not check

Registering a service evaluates ten rules and stores the result in the same
transaction as the service row. Registering the six example manifests produces:

```text
service                  tier  score    failing
orders-api               1     10/10    -
pricing-engine           1     8/10     journeys-declared, runbook-linked
billing-worker           2     7/10     environment-urls, liveness-probe, readiness-probe
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
| Docker | any recent | PostgreSQL, and the integration tests |
| Java | **none required** | Gradle provisions a Temurin 21 toolchain itself |

You do not need a JDK installed. The build declares a Java 21 toolchain and
Gradle downloads one on first use — verified on a machine with only JDK 17 and
25 present.

### Running it

```bash
make install    # install workspace dependencies
make up         # start PostgreSQL and wait until it is accepting connections
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

## Repository layout

Only directories with real contents exist. The full target layout is in
[`docs/roadmap.md`](docs/roadmap.md).

```text
OpsAtlas/
├── apps/control-plane/     Java 21 / Spring Boot 3 modular monolith
│   └── src/main/java/com/ambitiousconcepts/opsatlas/
│       ├── shared/         errors, pagination, correlation — depends on nothing
│       ├── identity/       the org-scoping stub
│       ├── catalog/        services, environments, service.yaml ingestion
│       └── governance/     policy rules, scorecards, audit
├── apps/web/               Next.js console — catalog, scorecard, register
├── packages/contracts/     service.yaml JSON Schema and the fixture validator
├── deploy/compose/         local PostgreSQL
├── examples/services/      six valid manifests, eight invalid fixtures
└── docs/
    ├── adr/                0001–0007
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

After slice one: GitHub sync, the Go observer, the telemetry pipeline, events,
and deployment. Details and the reasoning for the ordering are in
[`docs/roadmap.md`](docs/roadmap.md).
