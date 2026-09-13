# 0010 — Health is served separately from the catalog

- **Status:** Accepted
- **Date:** 2026-09-13
- **Phase:** 7 — the observer

## Context

The catalog list wants a health column. The service detail page wants a health
meter and a thirty-day ribbon. The obvious implementation is to put a `health`
field on `ServiceSummary` and an `EnvironmentHealth` on each environment of
`ServiceDetail`, and have the catalog fill them in.

That was implemented, and it created a module cycle.

`operations` has to resolve an environment id to the service that owns it, because
an observer reports environment ids and the rollups are keyed by service. That
makes `operations` depend on `catalog`. Putting health on the catalog's own
response types makes `catalog` depend on `operations`. Both are `api`-package
dependencies rather than reaching into internals, so neither breaks the letter of
`CLAUDE.md` §5 — but two modules that each need the other are one module wearing
two names, and `ArchitectureTest` has enforced exactly that rule against
`catalog` and `governance` since phase 3.

## Decision

**The dependency runs one way: `operations` → `catalog`. The catalog carries no
health, and health is served by its own endpoints.**

```
GET /api/v1/health?serviceIds=…          worst status per service, for the list
GET /api/v1/services/{slug}/health       per-environment state and daily history
```

`catalog.api.EnvironmentLookup` is the seam `operations` uses to resolve
environment ids, replacing a direct `SELECT` against catalog's `environment`
table. That query was a boundary violation ArchUnit could never have caught,
because a SQL string is not an import.

**The console composes the two.** The catalog page fetches services and health
together with `Promise.allSettled`, exactly as the service detail page already
fetches its detail and scorecard.

## Alternatives rejected

**Leave the cycle in.** Both directions are through public APIs, and one
deployable means it compiles and runs perfectly well. Rejected because the rule
against it is not decorative: two mutually dependent modules cannot be reasoned
about, tested or extracted separately, and this project has been enforcing that
rule on other module pairs since phase 3. Exempting the pair where it became
inconvenient would make the rule worth nothing.

**Invert it: let `catalog` own the rollup and have `operations` push health into
it.** Keeps health on the catalog's response. Rejected because it makes the
catalog store a denormalised copy of something another module owns, which has to
be kept current, and which is wrong in exactly the window that matters.

**A third module that depends on both and composes them.** The textbook fix, and
correct at larger scale. Rejected as premature: it is a module whose entire
contents would be two endpoints joining two calls, and §3 rule 3 is explicit
about not creating structure before it has contents.

**Keep the direct `SELECT` against `environment` and just not talk about it.**
Rejected. It is the kind of shortcut that is invisible to every automated check
here, which is precisely why it would survive — and the module boundary would be
a thing the tests believed in and the code did not.

## Consequences

**Good.** No cycle. `ArchitectureTest` can assert `catalog` does not depend on
`operations`, which is now a real constraint rather than a comment. Health is
genuinely optional to the catalog: a slow or failing health read no longer slows
or fails the service list, and the console renders the list with health missing
rather than rendering nothing — which is the partial-failure behaviour §10 asks
for, arrived at for a structural reason rather than a cosmetic one.

**The cost, stated plainly.** The console makes two requests where it made one,
and has to join them by service id. That join is a place a future bug can put the
wrong health against the wrong service, which a single response would have made
impossible. The requests go in parallel, so the latency cost is one round trip
rather than two, but it is not free.

**A client that wants one call does not get one.** Anything consuming this API
directly — a future CLI, someone's dashboard — has to make both requests and do
the join itself. That is a real ergonomic cost of keeping the modules honest, and
it would be the first thing to reconsider if a second consumer appeared.

**`EnvironmentLookup` is a new piece of catalog's public surface** that exists
only because operations needs it. It is small and honest, but it is API surface
added to serve one caller.
