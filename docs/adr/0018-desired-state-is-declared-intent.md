# 0018 — Desired state is declared intent, and it waits for drift

Status: accepted
Date: 2026-09-16

## Context

`DesiredState` has been in this project's domain model since the founding
prompt. It is in `CLAUDE.md` §7's entity tree, in §6's control plane, in §5's
description of what the observer does, and in §9, where it is the *example* of
where optimistic locking matters most. The founding API sketch named an endpoint
for it: `PUT /api/v1/services/{serviceId}/desired-state`.

It has never been implemented. Searching this repository for the string finds it
in `docs/roadmap.md` and nowhere else — no table, no entity, no endpoint, no
column. The documentation has been describing an entity that does not exist for
the life of the project, which is the failure `CLAUDE.md` §3 rule 1 exists to
prevent, applied to a design document rather than to a claim about behaviour.

Two things have changed that make this worth deciding rather than carrying:

- **ADR 0017 shipped reported deployments.** A pipeline POSTs what it shipped and
  OpsAtlas records it without verifying it.
- **Phase 15 designed observed versions.** The observer would GET a declared
  version endpoint and record what is actually answering.

So the question is no longer "when do we build desired state". It is whether
"desired" names a fact the system does not already hold.

**It does, and the three are genuinely different claims about the world:**

1. **Desired** — what somebody says *should* be running. An intent. Nobody has
   ever said it here.
2. **Reported** — what a pipeline says it shipped (ADR 0017). Truthful about an
   action somebody took, and unverified.
3. **Observed** — what is actually answering a probe (phase 15). Truthful about
   one replica at one moment from one vantage point.

The gaps between them are different incidents:

- **Desired ≠ reported** means *the deploy never ran*. A release was cut, a
  pipeline failed or was never triggered, and nothing shipped. Neither ADR 0017
  nor phase 15 can see this — both only ever compare things that happened.
- **Reported ≠ observed** means *the deploy did not take*, or it rolled back, or
  a replica is stale. Phase 15 covers exactly this.

Collapsing the two loses the first, and the first is the one nobody notices,
because a pipeline that never ran produces no alert, no failed probe and no
audit event.

## Decision

**Desired state stays in the domain model as declared intent, is not built now,
and ships with phase 15 or not at all.**

Concretely:

- **It is written intent, never derived.** A `PUT` on an environment, carrying
  the version that should be running, with `If-Match` — which is what §9 always
  said, and the endpoint it named is still the right shape.
- **It ships with drift detection, not before.** Desired state with nothing to
  compare it against is one more field for somebody to forget to update. A stale
  desired version is worse than an absent one: it renders as permanent drift,
  which trains everyone to ignore the drift signal — the same failure mode phase
  15 already guards against with its grace window and phase 19 guards against
  with silences.
- **Until then, the documentation says it does not exist.** `CLAUDE.md` §5, §6,
  §7 and §9 are amended in the same commit as this ADR to mark it, and
  `Incident`, as not built. A domain model that does not distinguish what exists
  from what is planned is a map with no legend.
- **Drift, when it lands, is three-way.** `CLAUDE.md` §5 has said since the
  beginning that the observer detects "desired-versus-observed" drift. Phase 15
  as written compares *reported* versus observed. Both are right about one edge
  of a triangle, and the amendment says so rather than picking one.

## Alternatives rejected

**Strike it from the model entirely**, and define drift as reported-versus-
observed. This was the recommendation until phase 15 was read closely. It is
simpler, it removes an unbuilt entity from the documentation rather than
annotating it, and it costs nothing today. It was rejected because it silently
discards the *deploy never ran* case, and that case is invisible by nature —
there is no failing probe and no error to notice. A model that cannot express it
will not grow the ability later; it will just never ask the question.

**Derive it from the manifest** — a `spec.environments[].version` field that
monitored repositories declare. Rejected on sight. A version committed in a
monitored repository goes stale the moment anybody deploys without editing it,
which is every normal deploy. Every service would read as permanently drifted,
and the first week of that teaches everybody the column is noise. It also makes
a deploy require a pull request into the service's own repository, which is a
workflow decision OpsAtlas has no business imposing (ADR 0008).

**Treat the last reported deployment as the desired state.** The same as
striking it, with an extra step that makes the collapse look deliberate. It
would also make "desired ≠ reported" definitionally impossible while leaving the
field on the page, which is worse than not having the field.

## Consequences

**The bad, stated plainly:**

- **The domain model now carries two entities that are not built** —
  `DesiredState` and `Incident` — and needs an annotation to say so. An
  annotated model is one more thing to keep current, and this ADR exists because
  an unannotated one went stale for the life of the project.
- **Phase 15 gets bigger.** Drift detection now has to land a three-way
  comparison and a UI that distinguishes three gaps, not two. That is a real
  argument for the rejected alternative and it should be re-read before 15
  starts.
- **§9 loses its best example.** "Optimistic locking matters wherever concurrent
  updates are plausible — desired state above all" was a good illustration
  because desired state is the field two pipelines would race on. The example
  that exists instead — `PUT /api/v1/services/{slug}` with `If-Match` — is real
  and less vivid.
- **If phase 15 never happens, this never happens**, and the model carries a
  permanent "not built" marker. That is honest and it is also a smell; if 15 is
  still unscheduled a year from now, revisit this and strike it.

**The good:**

- Nothing is built on speculation, which is `CLAUDE.md` §3 rule 3 applied to a
  table rather than to a directory.
- The three-way distinction is written down while the reasoning is fresh, rather
  than rediscovered — probably incorrectly — when phase 15 starts.
- The documentation stops describing a system that was never built, which is the
  part that had to happen regardless of which way this decision went.
