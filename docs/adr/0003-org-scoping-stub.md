# 0003 — Org scoping: explicit orgId behind a swappable PrincipalResolver

- **Status:** Accepted
- **Date:** 2026-09-12
- **Phase:** Slice one

## Context

`CLAUDE.md` §7 settles the tenancy question so it is not re-litigated: every
tenant-scoped table carries `org_id UUID NOT NULL` with a foreign key to
`organization`, and every query filters on it. Until authentication exists, the
organization is resolved by a single stub `PrincipalResolver` in `identity` that
returns the one seeded organization. Swapping that resolver for real auth is the
entire migration path, marked with `TODO(auth)`.

§7 is equally clear about what this is not: **single-tenant data modelled so that
multi-tenancy is possible later.** The project does not support multi-tenancy and
must not be described as doing so until authorization is implemented and tested.

What §7 does not settle is *how* the filter is applied at the query layer, and
that choice determines how auditable the guarantee is.

## Decision

```java
// identity.api
public interface PrincipalResolver {
    Principal resolve(HttpServletRequest request);
}
public record Principal(UUID orgId, String subject, String displayName) {}
```

The slice-one implementation, `SeededOrgPrincipalResolver`, returns the single
Flyway-seeded organization and is marked `// TODO(auth)`. It is the only class
that needs replacing when authentication arrives.

`OrgContextFilter`, a `OncePerRequestFilter`, resolves the principal once per
request, stores it in a request-scoped `RequestContext`, places `orgId` and
`correlationId` into the MDC, and clears both in a `finally` block.

**Every repository method takes `orgId` as its first parameter.** There is no
implicit filtering, no interceptor, and no framework magic between the call site
and the `WHERE` clause.

Cross-organization reads return **404, not 403** — a 403 confirms the row exists.

## Alternatives rejected

**Hibernate `@Filter` or `@TenantId`.** The filter is applied automatically and
cannot be forgotten, which is a genuinely stronger guarantee than discipline.
Rejected on three grounds: it is invisible at the call site, so a reader auditing
whether scoping happens has to know the annotation is there and enabled; it does
not apply to native queries or to anything bypassing the session; and it makes
the eventual migration to real authorization harder to reason about, because the
scoping decision lives in framework configuration rather than in code.

**PostgreSQL row-level security.** The strongest option — the database refuses to
return the row regardless of what the application does. Rejected for slice one
because it requires a session variable set per connection, interacts awkwardly
with connection pooling and with Flyway, and is a large amount of machinery for a
system with exactly one seeded organization. It is recorded in `docs/roadmap.md`
as the right answer for the phase where real authorization lands.

**No scoping until auth exists.** Rejected: retrofitting `org_id` across every
table and query later is exactly the migration §7 is written to avoid.

## Consequences

**Good.** The scoping is visible in every method signature, so reviewing whether
a query is scoped requires reading only that query. The migration path is one
class. Nothing about the data model changes when auth arrives.

**The cost, stated plainly.** An explicit parameter is forgettable. Nothing in the
type system stops someone writing a repository method without `orgId`, and it
will work perfectly in a single-organization system — the bug is invisible until
a second organization exists, which is precisely when it is most dangerous.

The mitigation is a test, not a type: `OrgIsolationIT` seeds a second
organization and sweeps every read endpoint, asserting the other organization's
rows are invisible and that a cross-organization fetch returns 404. That test is
load-bearing and must be extended whenever an endpoint is added. It is the only
thing standing between this design and a data leak.

**Also.** Until that test covers an endpoint, that endpoint's isolation is
unverified — and must be described as unverified, not as working.

## What this does not do

This does not make OpsAtlas multi-tenant. There is no authentication, no
authorization, and no second organization outside a test fixture. The README says
so explicitly, and no document in this repository may claim otherwise until
`identity` implements real authentication and `OrgIsolationIT` covers every
endpoint.
