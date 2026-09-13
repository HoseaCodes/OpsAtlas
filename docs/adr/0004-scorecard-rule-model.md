# 0004 — Scorecard: typed check beans, versioned rule sets, per-check rows

- **Status:** Accepted
- **Date:** 2026-09-12
- **Phase:** Slice one

## Context

`CLAUDE.md` §8 requires that registration computes an initial scorecard and writes
an audit event, both in the same transaction as the service row. §5 places policy
evaluation in the control plane and nowhere else — "if a React component is
deciding whether something passes policy, that logic is in the wrong place."

The design question is what a rule *is*, because slice one can evaluate only what
a manifest declares, while later phases will evaluate observations, CI results and
image scans. Getting the rule interface wrong means rewriting every rule when the
observer lands.

## Decision

```java
// governance.api
public interface PolicyCheck {
    String id();                                  // stable, stored on results
    String title();                               // human-facing
    Applicability appliesTo(ServiceContext ctx);  // REQUIRED | NOT_APPLICABLE
    CheckOutcome evaluate(ServiceContext ctx);    // PASS | FAIL, plus an actionable reason
}
```

Checks are Spring beans, collected into a `PolicyCatalog`. Three decisions come
with this:

**`ServiceContext` is the extension point.** In slice one it carries the validated
manifest and the persisted service row. When the observer and integrations land it
gains observation and CI facts — and no existing rule's signature changes. That is
the whole extensibility mechanism, and it is deliberately the only one.

**`NOT_APPLICABLE` is a first-class outcome.** A tier 3 internal service is not
*failing* for having no SLO; the rule does not apply to it. The score is
`passed / applicable`, and the denominator is **stored**, not recomputed. Without
this, tier 3 services look permanently broken and the fleet number is meaningless.

**`policy_set_version` is stored on every evaluation.** Raising the bar fleet-wide
does not retroactively rewrite last month's results. A compliance trend is only
honest if a past score still means what it meant when it was computed.

**Per-check rows, not a JSON blob.** `policy_result_check` has one row per check,
so the scorecard matrix is a query rather than N document parses, and "which
services fail the runbook check" is an index scan.

### Slice-one check set

Ten checks, all derivable from the manifest alone. Each one can genuinely both
pass and fail on a realistic manifest — a check that cannot fail is padding.

| id | Applies | Fails when |
|---|---|---|
| `owner-declared` | always | `metadata.owner` absent |
| `runbook-linked` | always | `spec.operations.runbook` absent |
| `slo-defined` | tier 1–2 | `spec.operations.slo` absent |
| `readiness-probe-declared` | always | `spec.health.readiness` absent |
| `liveness-probe-declared` | always | `spec.health.liveness` absent |
| `observability-service-name` | always | `spec.observability.serviceName` absent |
| `environment-urls-declared` | always | any environment has no `url` |
| `dependencies-declared` | always | `spec.dependencies` absent (empty list passes) |
| `journeys-declared` | tier 1–2 | `spec.journeys` absent or empty |
| `production-environment-declared` | tier 1–2 | no environment named `production` |

`dependencies-declared` distinguishes **absent** from **empty**: "not stated" and
"stated: none" are different answers, and only one of them is a gap.

## Alternatives rejected

**An expression or policy engine — CEL, SpEL, or OPA/Rego.** Rules become data,
the bar moves without a deploy, and non-engineers could in principle author
policy. Rejected on two grounds. For ten checks the machinery costs more than it
saves. More importantly, introducing an expression evaluator that reads
repository-supplied content sits uncomfortably close to §3 rule 4 — even where the
expression itself is trusted, the evaluation context is not, and that is a
boundary worth not blurring.

**A JSON blob of results on the service row.** One column, no joins, trivially
written. Rejected because every fleet-level question — the scorecard matrix, "who
fails this check", the compliance trend — becomes a full scan and a parse.

**Recomputing the score on read.** Always current, no storage. Rejected because
the historical trend then reflects today's rules applied to today's manifests,
which cannot show whether the platform improved anything.

## Consequences

**Good.** Adding a check is one file and one test. The interface does not change
when richer facts arrive. Historical scores stay meaningful. The matrix view is a
single query.

**The cost, stated plainly.** Changing policy requires a code change, a test, a
review and a deploy — there is no runtime configuration and no way for a platform
lead to raise the bar without an engineer. For a fleet this size that is the right
trade, but it is a real constraint and it will chafe the first time a check needs
a per-team exception.

**Policy exceptions are not in slice one.** The dated, auto-expiring exception is
the best governance idea in the prototype, and it is deliberately absent: an
exception needs an approver, an approver needs identity, and identity is stubbed
(see ADR 0003). Shipping exceptions now would mean an approval anyone can grant,
which is worse than not having them. Recorded in `docs/roadmap.md`.

**The slice-one scorecard scores the manifest, not the running system.** Seven of
the prototype's checks — traces arriving, image scanned, dependency audit, test
coverage, infrastructure in Terraform — need integrations that do not exist. The
console says "declaration checks" and states that runtime verification arrives
with the observer. Calling these "production readiness" would be the exact false
claim §3 rule 1 forbids.
