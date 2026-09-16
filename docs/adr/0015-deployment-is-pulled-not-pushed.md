# 0015 — The box pulls its deployment; CI never reaches in

- **Status:** Accepted
- **Date:** 2026-09-15
- **Phase:** 12 — continuous delivery for the deployment ADR 0014 describes

## Context

ADR 0014 put OpsAtlas on one box. Getting a new version onto it was six manual
steps: wait for the image build, SSH in, `git pull`, edit `OPSATLAS_TAG` in a
`.env`, `docker compose pull`, `docker compose up -d`. Every one of them was a
chance to deploy something other than what was intended, and the record of what
had been deployed lived in a gitignored file on a single machine — so "what is
running, and when did that change?" was answerable only by logging in.

The conventional fix is a deploy job in GitHub Actions that connects over SSH
after the images are published. It is what most teams do and it works.

It has a specific cost here, though, and the cost is not hypothetical: **this
repository is public.** A deploy job needs a private key in Actions secrets, and
that key opens a shell on the production box as a user in the `docker` group —
which is root-equivalent, since a container can bind-mount `/`. Anything that can
run a workflow can then reach the box: a compromised maintainer account, a
compromised action in the dependency chain, or a workflow-trigger mistake.
GitHub's runner IP ranges are far too broad to firewall down to.

So the question is not "push or pull" in the abstract. It is whether this
project wants a credential in a public repository's CI that is equivalent to
root on its only server.

## Decision

**The box converges to what `master` declares. Nothing pushes to it.**

- **`deploy/production/VERSION` is the deployed version**, committed. Deploying
  is editing one line and merging it. `git log -- deploy/production/VERSION` is
  the deployment history, and a rollback is a commit rather than an SSH session
  and a memory of what the previous tag was.
- **A systemd timer runs `converge.sh` every minute** as the `opsatlas` user. It
  fetches `master`, hard-resets to it, reads `VERSION`, and runs
  `docker compose pull && up -d` only when something actually differs. A
  convergence with nothing to do is a `git fetch` and two string comparisons.
- **No inbound access is added.** Ports 22, 80 and 443 remain the whole surface,
  and 22 is for people. There is no key in Actions, and no CI job that can reach
  this machine.
- **`latest` is refused** by the script, not merely discouraged. ADR 0014 says a
  running version you cannot name is one you cannot roll back; this is that rule
  with an exit code.
- **A failed pull cannot take the site down.** `up -d` is never reached if
  `pull` fails, so a `VERSION` naming an unpublished tag fails to deploy rather
  than stopping what is already running.
- **CI is the gate.** A tag only exists on a commit somebody chose, and the
  browser smoke test runs the whole stack — issuer, control plane, database,
  console — on a clean runner before any tag is cut. There is no separate
  approval step, and that is a deliberate acceptance: the thing being relied on
  is the test suite, which is stated here so it can be argued with.

## Alternatives rejected

**A deploy job in GitHub Actions over SSH.** Instant instead of up-to-a-minute,
conventional, and the thing most reviewers would expect to see. Rejected for the
credential: a production key in a public repository's secrets, protecting a box
where `docker` group membership is root. Worth revisiting the moment there is a
staging environment to deploy to first, because then the key in CI opens
something that is not production.

**Watchtower, or any agent that follows a moving tag.** It would have removed
the `VERSION` file and the script entirely. It works by watching `latest` or a
branch tag move, which is exactly the "deployment you cannot name" ADR 0014
refuses. Rejected for the same reason `latest` is refused.

**A webhook receiver on the box.** Keeps the box pulling, removes the polling
delay. It also means a listening service whose whole job is to run commands when
something on the internet asks it to, plus a shared secret to verify the caller,
plus another port. More moving parts than a minute of latency is worth.

**Keeping it manual, with one `make deploy` target.** Honest, no new surface, and
it was the smallest possible change. Rejected because it leaves the record of
what is deployed in a gitignored file on one machine, which is the part that was
actually wrong — not the number of keystrokes.

## Consequences

**The bad, stated plainly:**

- **Up to a minute of lag**, plus image pull time, between merging and running.
- **A hard reset runs on that box every minute.** Anything edited there is lost
  without warning. That is the intent — the box is not a place where work
  happens — but it means debugging by editing a file in place does not survive,
  and anyone doing it will be surprised exactly once.
- **A bad commit to `master` deploys itself.** There is no approval gate; CI is
  the only thing between a merge and production. That is a real acceptance, not
  an oversight, and it is only reasonable because the browser smoke test is real.
- **The agent is a thing to operate.** If the timer is masked, stopped, or fails
  silently, the box quietly stops converging and nothing announces it.
  `systemctl list-timers` is the answer, and nothing alerts.
- **It still only reconciles this one box.** This is not fleet management, and
  nothing here should be described as GitOps beyond the narrow sense that the
  desired version lives in git.

**The good:**

- The deployed version is reviewable, diffable and revertable like any other
  change, and visible to anyone reading the repository rather than only to
  whoever can SSH in.
- Rolling back stops being a procedure and becomes `git revert`.
- No credential exists anywhere that lets CI reach production.
