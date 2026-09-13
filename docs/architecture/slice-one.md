# Slice one architecture

What exists today, not the target. The target layout and the phases after this
one are in [`../roadmap.md`](../roadmap.md).

---

## Context

```mermaid
flowchart LR
    repo["Monitored repository<br/><i>service.yaml</i>"]
    operator(["Operator"])
    console["Console<br/><i>Next.js</i>"]
    cp["Control plane<br/><i>Java 21 · Spring Boot</i>"]
    db[("PostgreSQL<br/><i>platform metadata</i>")]

    repo -. "copied by hand<br/>(GitHub sync is phase 6)" .-> operator
    operator -->|"pastes the manifest"| console
    console -->|"HTTP · server-side only"| cp
    cp --> db

    observer["Observer<br/><i>Go · phase 7</i>"]
    services["Monitored services"]
    observer -. "not built" .-> cp
    observer -. "not built" .-> services

    style observer stroke-dasharray: 4 3
    style services stroke-dasharray: 4 3
```

The dashed boxes are the reason nothing in this system reports health. The
observer is what would probe a service and publish an observation; it does not
exist, so every health field is null and the console says **never observed**
rather than showing a number nobody measured.

The console never talks to the control plane from the browser. Reads happen in
Server Components and the single write goes through a Next route handler, so the
control plane's address stays server-side and there is no CORS policy to get
wrong.

---

## Modules

```mermaid
flowchart TD
    subgraph cp["Control plane — one deployable, four modules"]
        direction TB
        catalog["<b>catalog</b><br/>services · environments<br/>service.yaml ingestion"]
        governance["<b>governance</b><br/>policy rules · scorecards<br/>audit"]
        identity["<b>identity</b><br/>organizations · principals<br/><i>auth is stubbed</i>"]
        shared["<b>shared</b><br/>errors · pagination · correlation<br/><i>depends on nothing</i>"]
    end

    catalog -->|"governance.api"| governance
    catalog -->|"identity.api"| identity
    governance -->|"identity.api"| identity
    catalog --> shared
    governance --> shared
    identity --> shared

    ops["operations<br/><i>phase 7</i>"]
    integrations["integrations<br/><i>phase 6</i>"]
    style ops stroke-dasharray: 4 3
    style integrations stroke-dasharray: 4 3
```

Each module exposes an `api` package and hides an `internal` one. `ArchitectureTest`
fails the build when anything crosses that line, when `shared` reaches sideways,
when a persistence entity escapes its module, when governance reaches back into
catalog, or when a package appears for a phase that is not active. Those rules
are what make this a modular monolith rather than a monolith with some packages —
see [ADR 0001](../adr/0001-modular-monolith-as-one-gradle-module.md).

**The catalog → governance dependency runs one way.** Catalog calls governance
when it registers something; governance never calls back. If it did, the two
would be one module wearing two names.

---

## Registering a service

The sequence worth drawing, because it is where the transactional guarantee lives.

```mermaid
sequenceDiagram
    autonumber
    participant C as Console
    participant API as ServiceController
    participant Reg as ServiceRegistrationService
    participant In as ManifestIngestor
    participant Gov as governance
    participant DB as PostgreSQL

    C->>API: POST /api/v1/services<br/>Content-Type: application/yaml
    API->>Reg: register(orgId, document, sourcePath)

    rect rgba(128,128,128,0.10)
        note over In: ADR 0002 — no stage runs<br/>unless the one above it passed
        Reg->>In: ingest(document)
        In->>In: 1. cap at 64 KiB
        In->>In: 2. parse (no type construction,<br/>no alias expansion)
        In->>In: 3. validate against the schema<br/>apiVersion selects
        In->>In: 4. bind, then semantic checks
        In-->>Reg: manifest · schemaVersion · digest
    end

    alt manifest is invalid
        In-->>API: ValidationFailedException
        API-->>C: 422 + violations[] located by JSON Pointer
    else already registered, identical bytes
        Reg-->>API: replay — nothing written
        API-->>C: 200, version unchanged
    else already registered, different bytes
        Reg-->>API: ConflictException
        API-->>C: 409 naming the PUT and If-Match to use
    else new
        rect rgba(128,128,128,0.10)
            note over Reg,DB: one transaction — CLAUDE.md §8
            Reg->>DB: insert service + environments
            Reg->>Gov: evaluateAndStore(facts)
            Gov->>DB: insert policy_result + per-check rows
            Reg->>Gov: record(service.registered)
            Gov->>DB: insert audit_event
        end
        API-->>C: 201 + ETag
    end
```

Steps 12–16 are one transaction. A service row, its first scorecard and its audit
entry all exist or none do — asserted by forcing a mid-registration failure and
checking that all three are absent.

---

## Data model

```mermaid
erDiagram
    organization ||--o{ team : "scopes"
    organization ||--o{ service : "scopes"
    team ||--o{ service : "owns (nullable)"
    service ||--o{ environment : "runs in"
    service ||--o{ policy_result : "scored by"
    policy_result ||--o{ policy_result_check : "one row per rule"
    organization ||--o{ audit_event : "scopes"

    organization {
        uuid id PK
        text slug UK
    }
    team {
        uuid id PK
        uuid org_id FK
        text slug "unique per org"
    }
    service {
        uuid id PK "UUIDv7 — also the pagination key"
        uuid org_id FK
        uuid team_id FK "null when unowned"
        text slug "unique per org"
        text repository "with source_path, the registration key"
        jsonb manifest "normalized, as validated"
        text manifest_digest "sha256 of raw bytes"
        bigint version "optimistic lock"
    }
    environment {
        uuid id PK
        uuid org_id FK
        uuid service_id FK
        text name "unique per service"
    }
    policy_result {
        uuid id PK
        text policy_set_version "so an old score stays readable"
        smallint checks_passed
        smallint checks_applicable "moves with the tier"
    }
    policy_result_check {
        text check_id PK
        text status "PASS · FAIL · NOT_APPLICABLE"
        text detail "required when FAIL"
    }
    audit_event {
        uuid id PK
        text action
        text correlation_id "ties to the request's log lines"
        jsonb payload
    }
```

Three things in that diagram are load-bearing and easy to miss:

- **`service.team_id` is nullable.** An unowned service must be registerable,
  because the catalog's job includes showing that nobody is accountable for
  something.
- **`service` and `environment` reference their parent by `(org_id, id)`, not by
  `id` alone.** A single-column foreign key would let a scoping bug attach rows
  across the organization boundary while every individual constraint stayed
  satisfied. The composite version makes the database refuse it, and
  `OrgIsolationIT` proves it does.
- **`audit_event` has no foreign key to `service`.** An audit entry must outlive
  what it describes; a cascade would erase exactly the record worth keeping.

---

## What is not here

| Not present | Why | Phase |
|---|---|---|
| Health, SLO attainment, 30-day history | needs the observer to probe something | 7 |
| Dependency graph, blast radius | needs the observer and trace data | 7 |
| Authentication and authorization | stubbed by design — [ADR 0003](../adr/0003-org-scoping-stub.md) | later |
| Policy exceptions | need an approver, which needs identity | later |
| Reading `service.yaml` from GitHub | needs an App identity and permission model | 6 |
| Redis, Kafka, outbox | only once there is a demonstrated need (§6) | 9 |
| Terraform, Kubernetes, Helm | nothing to deploy until there is something to run | 10 |
