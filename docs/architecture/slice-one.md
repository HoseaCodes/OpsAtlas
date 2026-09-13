# Architecture — what exists today

Slice one plus phases 6, 7 and 8. Deliberately a description of what is built,
not of what is planned; the target layout and the remaining phases are in
[`../roadmap.md`](../roadmap.md).

---

## Context

```mermaid
flowchart LR
    repo["Monitored repository<br/><i>service.yaml</i>"]
    operator(["Operator"])
    console["Console<br/><i>Next.js</i>"]
    cp["Control plane<br/><i>Java 21 · Spring Boot</i>"]
    db[("PostgreSQL<br/><i>platform metadata</i>")]

    operator -->|"pastes the manifest"| console
    console -->|"HTTP · server-side only"| cp
    cp -->|"polls, read-only, every 5m"| repo
    cp --> db

    observer["Observer<br/><i>Go</i>"]
    services["Monitored services"]
    observer -->|"reads the service list"| cp
    observer -->|"probes health endpoints"| services
    observer -->|"reports observations"| cp
```

The observer is the only thing here that measures anything. It reads the
catalog, probes what the manifests declare, and reports results back; the
control plane folds them into counters rather than storing a row per probe
(ADR 0009).

An environment the observer has not reached has **no health record at all**,
and the console renders that as an outline rather than a reading. Never
observed and observed-and-broken are different facts, and the absence of a row
is what keeps them apart.

A manifest reaches the catalog two ways, and both end in the same ingestion
path: an operator pastes it, or the control plane polls a watched repository for
it. The arrow to the repository points outward and only outward — OpsAtlas reads
and never writes, which is what ADR 0008 trades webhook latency for.

The console never talks to the control plane from the browser. Reads happen in
Server Components and the single write goes through a Next route handler, so the
control plane's address stays server-side and there is no CORS policy to get
wrong.

---

## Modules

```mermaid
flowchart TD
    subgraph cp["Control plane — one deployable, six modules"]
        direction TB
        operations["<b>operations</b><br/>observations · rollups<br/>health read model"]
        integrations["<b>integrations</b><br/>watched sources · polling<br/><i>read-only, outbound</i>"]
        catalog["<b>catalog</b><br/>services · environments<br/>service.yaml ingestion"]
        governance["<b>governance</b><br/>policy rules · scorecards<br/>audit"]
        identity["<b>identity</b><br/>organizations · principals<br/><i>auth is stubbed</i>"]
        shared["<b>shared</b><br/>errors · pagination · correlation<br/><i>depends on nothing</i>"]
    end

    integrations -->|"catalog.api"| catalog
    integrations -->|"identity.api"| identity
    operations -->|"catalog.api"| catalog
    operations -->|"identity.api"| identity
    catalog -->|"governance.api"| governance
    catalog -->|"identity.api"| identity
    governance -->|"identity.api"| identity
    integrations --> shared
    operations --> shared
    catalog --> shared
    governance --> shared
    identity --> shared
```

Each module exposes an `api` package and hides an `internal` one. `ArchitectureTest`
fails the build when anything crosses that line, when `shared` reaches sideways,
when a persistence entity escapes its module, when governance reaches back into
catalog, or when a package appears for a phase that is not active. Those rules
are what make this a modular monolith rather than a monolith with some packages —
see [ADR 0001](../adr/0001-modular-monolith-as-one-gradle-module.md).

**The dependencies run one way: integrations → catalog → governance.** Catalog
calls governance when it registers something, and never calls back. Catalog also
does not know that `integrations` exists — it accepts a document from anywhere,
which is precisely why a polled manifest and a pasted one cannot be treated
differently. Both directions are enforced by `ArchitectureTest`, as is the rule
that **only `integrations` may make an outbound HTTP call at all** — that is ADR
0008's "OpsAtlas never writes to a repository" expressed as something the build
can check.

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
    organization ||--o{ source : "scopes"
    source |o--o| service : "produces (nullable)"
    environment ||--o| environment_state : "current health"
    environment ||--o{ environment_day : "one row per UTC day"
    organization ||--o{ observation_batch : "idempotency keys"

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
    environment_state {
        uuid environment_id PK
        text status "HEALTHY · DEGRADED · DOWN — no UNKNOWN"
        text detail "required unless HEALTHY"
        integer consecutive_failures "a blip from an outage"
        timestamptz last_probe_at
        timestamptz last_healthy_at "null until one succeeds"
        bigint version "optimistic lock"
    }
    environment_day {
        uuid environment_id PK
        date day PK
        integer probes
        integer successes
        bigint response_ms_sum "with min and max — not a distribution"
        integer response_ms_min
        integer response_ms_max
    }
    observation_batch {
        uuid org_id PK
        text idempotency_key PK
        jsonb response "replayed verbatim to a retry"
        timestamptz received_at "pruned by the retention job"
    }
    audit_event {
        uuid id PK
        text action
        text correlation_id "ties to the request's log lines"
        jsonb payload
    }
    source {
        uuid id PK
        uuid org_id FK
        text repository "with path, unique per org"
        text etag "replayed as If-None-Match"
        text last_outcome "REGISTERED · UNCHANGED · REJECTED · ..."
        text last_detail "required when not a success"
        timestamptz last_attempt_at
        timestamptz last_success_at "how current the catalog is"
        uuid service_id FK "set once, never cleared"
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
- **`environment_state` has no probe counters and no `UNKNOWN` status.** Current
  state is a status, a reason and a failure streak; the counting happens in
  `environment_day`. An environment nobody has probed has **no row at all**, so
  absence means never-observed and cannot be confused with a measurement — which
  is why the status column has three values rather than four.
- **`environment_day` stores a sum, a min and a max — and that is the ceiling.**
  Storage is environments × retained days, independent of how often the observer
  probes (ADR 0009). The deliberate cost is that no percentile can ever be
  computed from it, which is why the API reports mean and max and calls them
  mean and max.
- **`observation_batch` stores the response, not just the key.** A retry replays
  the first attempt's answer verbatim; recomputing it could return something
  different once state has moved on, and a retry must not be able to observe
  that. Counters are exactly the shape where a double-write is silent.
- **`source` carries two timestamps, not one.** `last_attempt_at` and
  `last_success_at` together answer "how current is what I am looking at", which
  is the question a reader of a failing source actually has. Neither answers it
  alone, and `service_id` is set once and never cleared so a failing source can
  still say which service has gone stale.

---

## One request, end to end

The decision this draws is [ADR 0011](../adr/0011-correlation-id-is-the-trace-id.md):
**a request's correlation ID is its trace ID.** Without it, a log line says
`correlationId=8f2c…` while the trace covering the same work says `traceId=a41b…`,
and joining them means joining on timestamps — which is how a two-minute
investigation becomes twenty.

```mermaid
sequenceDiagram
    autonumber
    participant Obs as Observer (Go)
    participant Svc as A monitored service
    participant CP as Control plane
    participant Col as OTLP collector
    participant T as Tempo

    Note over Obs: span observer.pass — trace 4bf92f35…
    Obs->>Svc: GET /actuator/health/readiness
    Note right of Svc: no traceparent — we do not<br/>own this service's logs
    Svc-->>Obs: 200

    Obs->>CP: POST /api/v1/observations<br/>traceparent: 00-4bf92f35…-…-01
    Note over CP: joins the trace<br/>correlationId = 4bf92f35…
    CP-->>Obs: 202 · X-Correlation-Id: 4bf92f35…

    CP-->>Col: spans (OTLP/HTTP, batched)
    Obs-->>Col: spans (OTLP/HTTP, batched)
    Col-->>T: one trace, two services
```

Four properties of that picture are the design, and each is asserted rather than
asserted-in-prose:

- **The observer's span and the control plane's are one trace.** `traceparent`
  goes out on the catalog and reporter clients, and the control plane joins it
  rather than starting a second trace — `TraceCorrelationIT`.
- **The probe carries nothing.** It reaches a system OpsAtlas does not own, and
  putting our identifiers in somebody else's headers and logs is not a decision a
  monitoring tool gets to make unilaterally. A Go test fails if a probe sends
  `traceparent`, and it was checked by mutation rather than trusted.
- **A caller's own `X-Correlation-Id` wins**, and is tagged on the span. §9 says
  the ID is accepted and echoed; a caller who sends one and gets a different one
  back cannot correlate anything.
- **Export is best-effort.** The collector being down is an ordinary condition
  and must cost a request nothing — `TraceCorrelationIT` points the exporter at a
  closed port and asserts the requests still succeed.

Metrics go the other way: nothing is pushed. Prometheus scrapes
`/actuator/prometheus` on the control plane and `:9090/metrics` on the observer,
so neither process needs to know who is collecting, and neither fails if nobody
is.

---

## What is not here

| Not present | Why | Phase |
|---|---|---|
| Real-user SLO measurement | probe availability is not an SLO; this needs real traffic, not a prober | later |
| Latency percentiles | span durations are in Tempo, but nothing aggregates them, and the rollups store mean and max by design (ADR 0009) | later |
| Log shipping (Loki) | stdout JSON already carries the correlation ID; an agent, a retention policy and a second query language is a lot to beat `docker logs \| grep` (ADR 0011) | later |
| Desired-versus-observed drift | needs a deployment concept to compare against | later |
| Dependency graph, blast radius | the traces exist now, but deriving a graph from them is its own feature | later |
| Authentication and authorization | stubbed by design — [ADR 0003](../adr/0003-org-scoping-stub.md) | later |
| Policy exceptions | need an approver, which needs identity | later |
| A GitHub App (higher rate limits, no personal credential) | needs a registered application, a private key and an installation flow | later |
| Redis, Kafka, outbox | only once there is a demonstrated need (§6) | 9 |
| Terraform, Kubernetes, Helm | nothing to deploy until there is something to run | 10 |
