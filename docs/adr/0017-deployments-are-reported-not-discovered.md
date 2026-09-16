# 0017 — Deployments are reported, not discovered

Status: accepted
Date: 2026-09-16

## Context

The catalog knew what a service *declared* and whether its health endpoint
answered. It did not know what was **running**, and that one gap accounted for
more absent functionality than anything else in the project:

- **Drift detection** was deferred in phase 7 for exactly this reason. The
  observer can tell you a probe failed; it cannot tell you the wrong version is
  answering.
- **The v3 prototype's entire Promotion block** — version, commit, in place for,
  last deploy — had nothing behind it. Every field was mocked.
- **Incidents (phase 14)** would have had no "what changed just before this",
  which is most of what makes an incident record worth more than a spreadsheet.

There are three ways a platform can learn what version is running. Each is a
different claim about the world:

1. **Ask the running service.** Truthful about what is actually there, and needs
   a convention for exposing a version that does not exist across runtimes.
2. **Ask the orchestrator or registry.** Truthful about what was scheduled, and
   needs cluster credentials and a per-provider client.
3. **Be told by whoever deployed.** Truthful about what somebody *shipped*, and
   needs nothing but an endpoint.

## Decision

**The deploying pipeline reports the deployment. OpsAtlas records it and does
not verify it.**

`POST /api/v1/services/{slug}/environments/{environment}/deployments` takes a
version, an optional commit SHA, an optional deployer, an optional deploy time
and a **required** idempotency key. `GET /api/v1/services/{slug}/deployments`
returns the current version per environment plus recent history.

The thing that deploys is the only thing that reliably knows a deploy happened,
at the moment it happens. Options 1 and 2 are better data and both need
machinery that does not exist; option 3 needs a table and an endpoint, and is
honest about being a claim rather than an observation.

Four decisions inside that one worth stating:

- **`version` is free text.** A version is a semver, a date stamp, a release tag
  or a branch name depending on whose pipeline is talking. A format this table
  enforced would reject a truthful report from a real deploy.
- **`deployed_at` comes from the reporter, not from the server's clock.** A
  pipeline reporting late must be able to state when the deploy actually
  happened, or "in place for" silently measures how long ago OpsAtlas was told.
- **The idempotency key is required, never generated.** A key this endpoint
  invented would differ on every retry, which is precisely the failure the key
  exists to prevent: a retried notification becoming a second deployment, and
  then a rollback that never happened when the next read takes the wrong row as
  current. It is enforced by a unique constraint rather than a read-then-write,
  which races with itself under exactly the retry storm it must survive.
- **Rows, not counters.** ADR 0009 keeps probe results as rollups because their
  volume is set by an interval OpsAtlas chooses. A deployment happens when
  somebody ships, so the table grows with release cadence and not with anything
  this system controls.

**OpsAtlas reports its own deploys.** `converge.sh` POSTs after a successful
convergence, keyed on version and commit, so the platform's own entry carries
real promotion data rather than an empty column. It is best-effort by design: a
failed report must never fail a convergence that already succeeded, because
turning a reporting hiccup into a red deploy teaches everyone to ignore the exit
code.

## Consequences

**Good.** The Promotion view is real. `git log -- deploy/production/VERSION`
already made deployment history readable for OpsAtlas itself (ADR 0015); this
makes it readable for every service, in the place people look. Phase 14 now has
something to correlate an incident against.

**Bad, and worth stating plainly:**

- **A deployment row is a claim, not an observation.** Nothing verifies that the
  version reported is the version running. A pipeline that reports and then fails
  to deploy leaves the catalog confidently wrong, and OpsAtlas cannot tell.
- **It is empty until pipelines are changed.** Every service reads "not reported"
  until somebody adds a POST to their CI. That is honest and it is also an
  adoption cost, and a half-adopted deployment view is worse than none if anyone
  reads a missing version as "not deployed". The endpoint payload and the console
  both say "nothing told OpsAtlas" rather than "never deployed" for this reason.
- **Still no drift detection.** Drift is declared-versus-*observed*, and this
  supplies only the first half. The second needs option 1 — an additive optional
  `spec.observability.versionEndpoint` and observer support — and a service
  declaring none must then be shown as *not checkable* rather than *no drift*.
  It is written up as its own phase in `docs/roadmap.md`, including the two
  timing problems that will make it cry wolf if they are ignored: a rollout
  looks exactly like drift, and replicas disagree with each other during one.
- **No instance counts, and none possible here.** "Instances 9 / 14" and "5 pods
  failing readiness" are orchestrator facts. A probe reaches one URL through
  whatever sits in front of it and cannot see how many replicas answered. They
  need option 2 — runtime topology, its own phase in `docs/roadmap.md`, where
  the cost is the cluster credentials rather than the API work.
- **A trusted write path widens the blast radius of a leaked service key.** The
  observer's key can already write observations; a deploy key can now write
  deployment history. Neither can read the catalog of another organization, but
  fabricated deploy records are hard to spot after the fact.

## Alternatives rejected

**Polling a container registry.** Tells you an image exists, not that it is
running anywhere, and needs credentials per registry. It answers a question
nobody asked.

**Reading the orchestrator.** The best answer for "what is running", and it is
option 2 with all of its cost: cluster credentials held by a public-repository
project, RBAC, a client per provider, and a workload-to-service mapping that
does not follow from anything in the manifest. Right for a later phase, wrong as
the first deployment concept.

**Inferring a deploy from a health transition.** A service going DOWN then
HEALTHY looks like a restart. It is also what a crash loop, a node drain and a
network partition look like, and inventing a deployment from any of them puts a
fiction in the record people consult during an incident.

**Deploy gates — blocking a pipeline on a scorecard.** A much larger promise than
recording what a pipeline did, and it makes OpsAtlas a hard dependency of
everyone else's release. Record first; gate only if it is asked for.
