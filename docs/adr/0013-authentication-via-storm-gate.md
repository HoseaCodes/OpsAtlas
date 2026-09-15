# 0013 — Authentication: OpsAtlas verifies Storm-Gate's tokens

- **Status:** Accepted
- **Date:** 2026-09-13
- **Phase:** 9 — authentication

## Context

Nothing authenticates anything. There is no Spring Security on the classpath, no
`SecurityFilterChain`, and **eight write endpoints** open to anything that can
reach the port — including `POST /api/v1/observations`, which takes an
environment id from the request body and folds results into counters, so
fabricated health leaves no per-probe record to audit against. Swagger UI is on
by default, publishing a try-it-out console over those endpoints.

This was a deliberate slice-one decision (ADR 0003) and it is the one thing
blocking deployment. It is also the last thing in the system that is stubbed:
`audit_event.actor` says `local-operator` and carries a `TODO(auth)`, so the
audit log records what happened and cannot say who did it.

ADR 0003 named the migration path: swap `SeededOrgPrincipalResolver`, which is
the only thing that resolves an organization. `OrgIsolationIT` already proves
every endpoint scopes correctly behind it — 22 tests, with a guard that fails the
build if an endpoint is added without one. So the question is not whether scoping
works. It is where identity comes from.

## Decision

**OpsAtlas becomes an OAuth2 resource server and verifies RS256 access tokens
issued by Storm-Gate**, an authentication service in this author's other
repository that publishes its signing keys at `/.well-known/jwks.json`.

- OpsAtlas holds **no signing key and no shared secret**. It fetches public keys
  from the published JWKS and verifies locally. It can check a token; it cannot
  mint one. That asymmetry is the reason for choosing a JWKS-publishing issuer
  over anything symmetric.
- **Tenancy stays in OpsAtlas.** Storm-Gate's tokens carry no organization, and
  no claim will be added to them. A `principal` table maps the token's subject to
  an organization and a role. Storm-Gate stays a generic authentication service
  that knows nothing about this system, and the thing that models tenancy keeps
  modelling it.
- **The observer gets an OpsAtlas-issued credential**, not a Storm-Gate account.
  It is an internal component of OpsAtlas rather than a user, and inventing a
  fake person for it would put a lie in the audit log.

### Verified before deciding, not after

Storm-Gate was run locally against a throwaway MongoDB with a generated key:

- Its own suite passes: 85 tests, covering algorithm confusion, `kid` spoofing,
  issuer enforcement and pinned algorithms — the failures that actually matter in
  a verifier.
- It serves a valid JWKS **and** a valid OIDC discovery document, so the Spring
  side is `issuer-uri` and nothing hand-rolled.
- A real login token verified against the **published** public key.
- A forged token carrying the same `kid`, signed by a different key, was
  rejected.

### Three gaps this decision inherits

1. **RS256 is not Storm-Gate's default.** `JWT_SIGNING_ALG` defaults to `HS256`,
   and under HS256 a verifier needs the shared secret — which would also let it
   mint tokens, defeating the entire point. Any deployment OpsAtlas trusts must
   set `JWT_SIGNING_ALG=RS256` and `JWT_PRIVATE_KEY`. This is a deployment
   precondition, not a preference, and OpsAtlas should refuse to start against an
   issuer that is not publishing RS256 keys rather than silently accepting less.
2. **The subject claim is `id`, not `sub`.** Spring's default principal name is
   `sub`, so the principal claim name has to be set explicitly or every request
   authenticates as nobody.
3. **There is no machine-to-machine grant.** Hence the observer credential above.

## Alternatives rejected

**OpsAtlas issues its own API tokens**, hashed in PostgreSQL. No external
dependency, nothing new to deploy, runs entirely offline — the best answer for
"clone it and it works", which matters for a reviewer with five minutes.
Rejected because it cannot produce a human identity: the console would
authenticate as a service, and `audit_event.actor` would stay coarse forever.
An audit log that cannot name a person is a feature the README would have to
apologise for. This remains the right answer if the dependency below proves
painful, and is a day's work away.

**A vendor identity provider** — Auth0, Cognito, Entra directly. Identical work
on the OpsAtlas side, with nothing to run or patch and someone else owning the
CVEs. Rejected because the setup then lives outside the repository: a clone plus
`make dev` no longer reproduces the system, and local development needs a dev
tenant or a bypass. The bypass is the part that worries: an auth path that is
never exercised locally is an auth path nobody notices breaking.

**An authenticating reverse proxy** — Tailscale, oauth2-proxy, basic auth at the
edge. Cheapest by a wide margin and genuinely how many internal tools work.
Rejected because the control plane would stay wide open to anything that can
reach it directly, which is one misconfigured ingress away from today. Local
development would have no authentication at all, so the code path would never
run, and the security story would live outside the repository — the README would
have to say so in those words.

## Consequences

**Good.** One identity, verified without a secret OpsAtlas could abuse. The audit
log gets a real actor, which is most of what an audit log is for. Human sign-in
arrives without building a login screen, since Storm-Gate already brokers Azure
Entra ID. And the whole story stays inside repositories this author controls, so
`make dev` can still bring up everything.

**A second runtime and datastore.** The deployment story goes from one JVM and
one PostgreSQL to that plus Node and MongoDB. That is a real, permanent operating
cost, accepted because the alternative — building and owning authentication — is
a larger one.

**Inherited dependency risk, stated plainly.** Storm-Gate's own README reports
**3 critical and 30 high** advisories in its production dependency tree. That is
a patching problem rather than a design problem, but it is now OpsAtlas's problem
too, and it must be resolved before anything is publicly reachable.

**Local development now needs two services running.** `make dev` currently means
PostgreSQL and the control plane. It will mean PostgreSQL, the control plane,
Storm-Gate and MongoDB. Every extra process is one more thing between a clone and
a running system, and this doubles that number. The mitigation is compose, not
a bypass: an authentication path that is skipped locally is one nobody notices
breaking.

**A port collision.** Storm-Gate defaults to 8080, which is the control plane's
port. One of them has to move locally, and whichever moves will surprise somebody
who reads the other project's README.

**Tokens expire in a day and refresh is Storm-Gate's business.** OpsAtlas will
reject an expired token and say so; it will not refresh on a caller's behalf.
That is correct for a resource server and worth writing down, because the first
report will be "it logged me out".
