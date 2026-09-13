# 0007 — Service identity, and what re-registering means

- **Status:** Accepted
- **Date:** 2026-09-12
- **Phase:** Slice one, phase 2

## Context

A `service.yaml` is committed in someone else's repository and will be submitted
repeatedly — on every edit, on every retry, and eventually on every push once the
GitHub integration exists. Three questions have to be answered before the first
POST is written, and answering them wrongly is expensive later:

1. What makes two submissions "the same service"?
2. What should a repeated submission of an unchanged manifest do?
3. What should a submission of a *changed* manifest do?

`CLAUDE.md` §9 requires idempotency for retried writes and optimistic locking
wherever concurrent updates are plausible. §14 rules out the GitHub App in slice
one, so the document arrives in a request body rather than being fetched.

## Decision

**Identity for lookup is `(org_id, slug)`. Identity for registration is
`(org_id, repository, source_path)`.**

Those are deliberately different. A human looks a service up by its name. A
*registration* is identified by where its manifest lives, because that is the
thing that is stable across an edit: a manifest that changes `tier` is still the
same service, and the only field that could not have changed is the location it
was submitted from. Both are enforced by unique constraints, not just by code.

This gives three cases, and the API distinguishes all three:

| Submitted | Already registered from that location? | Result |
|---|---|---|
| any manifest | no | **201 Created** |
| byte-identical manifest | yes | **200 OK**, nothing written |
| different manifest | yes | **409 Conflict**, pointing at `PUT` |

**Idempotency is by content digest.** The SHA-256 of the raw submitted bytes is
stored on the service. A replay finds the same digest and returns the stored
service without writing, so a retried POST does not bump `version` or
`updated_at`. That is what stands in for the idempotency key §9 asks for, and it
needs no extra table and no client-supplied key.

The digest is over the **raw bytes**, not the parsed tree. Two documents
differing only in comments or key order are genuinely different files, and a team
that re-commits one should see the digest move.

**POST never overwrites.** Changing a registered service is a `PUT` carrying
`If-Match` with the version the caller read. The 409 says so, and names the exact
URL and version to use.

**Renames are refused.** A manifest whose `metadata.name` differs from the slug it
is registered under is a 409 at `/metadata/name`, not an update.

**Environments are reconciled by name**, updated in place rather than deleted and
recreated, so an environment keeps its id across a manifest edit.

## Alternatives rejected

**One identity: key registration on `(org_id, slug)` too.** Simpler — one unique
constraint, one lookup. Rejected because it makes a rename indistinguishable from
a new service: editing `metadata.name` would register a second row and silently
orphan the first, leaving two catalog entries for one running service and no
signal that it happened.

**POST as upsert.** Fewer round trips, and no 409 for a client to handle. Rejected
because it discards the concurrency control §9 asks for exactly where it matters.
Two people editing a manifest concurrently would both succeed and the later write
would silently win. The 409 is the mechanism that forces a caller to state which
version it read.

**Idempotency via a client-supplied `Idempotency-Key` header.** The conventional
answer, and the right one for an endpoint whose body is not the state being
written. Rejected here because the body *is* the state: a digest of it answers the
same question with no header for a client to forget, no key table, and no
expiry policy. When webhook and observation ingestion arrive — where the body is
an event, not a state — they will need real idempotency keys, and §9 already says
so.

**Allow renames, addressing services by UUID in URLs.** More flexible, and the
honest answer if renames are common. Rejected because it makes every URL opaque
(`/api/v1/services/018f3e3a-…`) to buy a capability nobody has asked for. A
service name is also its metrics prefix and the string people use to page each
other; renaming it is a migration, not an edit.

**Delete and recreate environments on update.** Less code than a diff. Rejected
because environment ids will shortly be what observations hang off. Churning them
on every manifest edit would throw away the history attached to them, and the bug
would only appear once the observer existed — long after this code was written.

## Consequences

**Good.** A retried POST is safe and visibly a replay. A concurrent edit is
caught. Every refusal names the next action, so no 409 is a dead end. The
registration key matches the constraint the database already enforces, so a race
that slips past the application check fails at the database rather than creating a
duplicate.

**The cost, stated plainly.** A client must handle three success-ish outcomes
(201, 200, 409) where an upsert would have one, and must do a `GET` before a `PUT`
to learn the version. For an automated pusher that is an extra round trip on
every change. Renames being refused will be genuinely annoying the first time
someone wants one, and the answer — register the new name, retire the old one —
loses the service's history.

**Also.** Moving a `service.yaml` to a different path in the same repository
registers a *second* service rather than moving the first, because the location is
the identity. Nothing detects that today. It is worth revisiting when the GitHub
integration can see file renames in a diff, and it is recorded in
`docs/roadmap.md`.

**And.** Teams are created on first mention by a manifest, so a typo in
`metadata.owner` creates a team nobody intended. It is visible in the catalog and
fixed by correcting the manifest, but it is a real consequence of discovering
teams rather than administering them.
