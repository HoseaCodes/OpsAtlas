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
│   │       ├── catalog/           ✓ services and environments; ingestion in phase 2
│   │       ├── governance/          phase 3 — policies, scorecards, audit
│   │       ├── operations/          phase 7 — desired state, observations, deployments
│   │       └── integrations/        phase 6 — GitHub, CI, cloud
│   ├── web/                         phase 4 — Next.js App Router console
│   └── observer/                    phase 7 — Go prober
├── packages/
│   ├── contracts/                 ✓ service.yaml schema; OpenAPI and TS client in phase 4
│   └── ui/                          not planned; extract only if a second consumer appears
├── deploy/
│   ├── compose/                   ✓ local PostgreSQL
│   ├── k8s/                         phase 10
│   └── helm/                        phase 10
├── examples/
│   └── services/                  ✓ example and fixture manifests
├── docs/
│   ├── adr/                       ✓ 0001-0006
│   ├── architecture/                phase 5 — Mermaid diagrams
│   ├── design/                    ✓ tokens.md
│   └── roadmap.md                 ✓ this file
├── terraform/                       phase 10
└── .github/workflows/               phase 5
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

**Known gap carried into phase 3:** cross-organization isolation is designed but
not yet tested. `OrgIsolationIT` needs a second organization to exist, which
needs registration. Until that test exists, the isolation is unverified and is
described that way.

### Phase 2 — Ingestion and registration

The ADR 0002 pipeline: size cap, snakeyaml-engine load, schema validation,
violation mapping, semantic normalization. `POST /api/v1/services`,
`GET /api/v1/services/{slug}`, `PUT /api/v1/services/{id}` with `If-Match`.

**Verified by:** every fixture in `examples/services/` registering, every fixture
in `examples/services/invalid/` producing exactly the violations in
`expected.json`, a 70 KiB body rejected before parsing, and an alias-expansion
document rejected by the loader's limits.

### Phase 3 — Scorecard and audit

`governance` module, the ten declaration checks from ADR 0004, `PolicyCatalog`,
evaluation inside the registration transaction, `policy_result`,
`policy_result_check`, `audit_event`, `GET /api/v1/services/{id}/scorecard`,
`GET /api/v1/audit-events`.

**Verified by:** per-check pass/fail/not-applicable unit tests; an integration
test that forces a mid-registration failure and asserts no service row, no policy
result and no audit event survive; `OrgIsolationIT`.

### Phase 4 — Contracts and console

`make openapi`, generated TypeScript types, the typed fetch client. Next.js
console: catalog list with search, tier and runtime filters and cursor paging;
detail with Overview, Scorecard and service.yaml tabs; register-by-paste with
inline violations. Design tokens from `docs/design/tokens.md`. Loading, empty,
stale, partial-failure, error and never-observed states.

Navigation contains **Catalog and nothing else.**

**Verified by:** component tests queried by role and accessible name, and a
Playwright smoke test that registers a fixture, finds it in the list and opens its
scorecard.

### Phase 5 — CI and documentation close-out

`.github/workflows/ci.yml` running Gradle with Testcontainers, pnpm lint,
typecheck and build, the OpenAPI drift check, and the Playwright smoke. README
mocked-versus-real table finalised, `docs/architecture/` diagrams, `CLAUDE.md` §2
rewritten to match reality.

---

## After slice one

Not implemented, not scaffolded, not configured. `CLAUDE.md` §14: if one of these
seems necessary to finish the current slice, stop and say so rather than quietly
expanding scope.

| Phase | What | Why it waits |
|---|---|---|
| 6 | GitHub App, webhooks, automatic `service.yaml` sync | Slice one registers by paste; sync needs an App identity and a permission model |
| 7 | Go observer, `operations` module, health probing, 30-day attainment | Everything health-shaped in the console depends on this and on nothing else |
| 8 | OpenTelemetry pipeline, Prometheus, Loki, Tempo, Grafana | Needs services actually running to observe |
| 9 | Transactional outbox, platform events, Redis read models | Only once there is a demonstrated need (§6) |
| 10 | Terraform, Kubernetes, Helm, k6 load tests | Nothing to deploy until there is something to run |

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
  digest instead.
- **Offset pagination** — never. §9 requires cursor pagination for every
  collection, and slice one sets that precedent with the first endpoint.
