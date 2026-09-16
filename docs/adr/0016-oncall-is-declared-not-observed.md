# 0016 — On-call is a declared escalation path, not live rotation state

Status: accepted
Date: 2026-09-15

## Context

The service detail page answers "what is this, who owns it, is it up". It does
not answer the question actually asked at 03:00: **who do I wake up?**

`metadata.owner` names a team, and `spec.operations.contact` names a chat
channel. Neither is an escalation path. A team slug is who is accountable in the
morning; a chat channel at 03:00 reaches nobody. A service can be fully owned,
fully observable, score well on every existing rule, and still have no rotation
behind it — and nothing in the catalog would say so.

The v3 prototype rendered this as **"On call now — t.nguyen · follow-the-sun, 7
engineers"**. Three facts, and they are not the same kind of fact:

1. **`t.nguyen`** — who is on call *at this moment*. Live state, owned by a
   paging provider.
2. **`follow-the-sun`** — how the rotation is structured. A property of the
   rotation.
3. **`7 engineers`** — the roster size. A property of the team.

## Decision

**OpsAtlas records where the rotation lives and what it covers. It does not
claim to know who is on call.**

`spec.operations.oncall` is added to the v1 schema — additive and optional, so
every manifest written before it still validates and `apiVersion` does not move:

```yaml
operations:
  oncall:
    rotation: https://pagerduty.example.com/schedules/PORDERS
    coverage: 24x7            # or business-hours, or best-effort
    escalation: platform-leads
```

A new scorecard rule, `oncall-declared`, is REQUIRED for tier 1 and 2 and NOT
APPLICABLE at tier 3 — nobody has promised to be woken for an internal tool, and
failing it there would make the fleet number a lie, the same reasoning as
`slo-defined`.

The rule is **stricter at tier 1**: a declared rotation covering only business
hours passes at tier 2 and fails at tier 1, because a tier 1 service is paged
around the clock by definition. Either the rotation is not one, or the tier is
wrong, and both are worth failing over. `coverage` exists to make that
judgement possible; without it the rule could only ask whether a URL was
present, which a rotation that is asleep would satisfy.

`PolicyCatalog.VERSION` moves to `2026-09-15.1`. The rule set changed, so every
stored score's denominator changed with it.

### What is deliberately not built

- **Who is on call now.** It needs a read-only integration with PagerDuty,
  Opsgenie or similar. A name this system could not refresh would go stale into
  the exact page somebody reads during an incident, and a stale name is worse
  than no name: it sends a page to someone who handed over three weeks ago.
- **Roster size.** "7 engineers" in a manifest is a number nobody updates when
  the eighth joins or the third leaves. It rots by default, and nothing could
  verify it.
- **`follow-the-sun` as a declared value.** It describes *how* 24x7 is achieved,
  which changes nothing about what a reader or a rule can do. `coverage` carries
  the signal; the handoff model is detail the schedule page already shows.

## Consequences

**Good.** The scorecard can now fail a tier 1 service with no way to page
anyone, which is arguably the most serious production-readiness gap it has been
blind to — more than a missing dashboard link. The console shows the rotation as
a followable link in the Operations block and in the links footer. Nothing
claims a live fact it cannot refresh.

**Bad, and worth stating plainly:**

- **This is still only a declaration.** A rotation URL pointing at an empty
  schedule passes. The rule proves somebody wrote a link down, not that anyone
  answers. Verifying it needs the provider integration this ADR defers.
- **Every service's score moved.** Five of the seven example manifests now fail a
  rule they could not have declared, and any real manifest in the wild does too.
  That is the correct behaviour for a new rule, and it means the fleet number
  drops on upgrade for reasons unrelated to anything getting worse.
- **`coverage` is self-reported and unfalsifiable.** A team can write `24x7`
  over a rotation with one name in it. The check cannot tell.
- **It adds a third contact-shaped field** — `contact`, `oncall.rotation`,
  `oncall.escalation` — and the difference between them has to be read to be
  understood. The schema descriptions say which is which, and `contact`'s was
  amended to point at `oncall`.

## Alternatives rejected

**A paging-provider integration now.** The only way to answer "who is on call"
truthfully. Rejected for this phase, not forever: it needs an account, a token
with a schedule-read scope, per-organization credential storage, and a polling
budget — and `integrations` currently reaches exactly one external system,
read-only and unauthenticated (ADR 0008). It is the right phase 15.

**A team and roster model in the control plane.** Model people, rotations and
handoffs in `identity`, and compute who is on now. Rejected: it reimplements
PagerDuty badly, needs timezone and holiday handling to be anything but wrong,
and makes OpsAtlas the system of record for something every organization already
has a system of record for.

**A free-text `oncall` string.** Cheapest, and unqueryable. `coverage` as an enum
is what lets a rule distinguish a tier 1 gap from a tier 2 fact; a string would
have made `oncall-declared` a presence check and nothing more.
