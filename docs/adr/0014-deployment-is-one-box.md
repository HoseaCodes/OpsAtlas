# 0014 — Deployment is one box running compose, not an orchestrator

- **Status:** Accepted
- **Date:** 2026-09-14
- **Phase:** 12, partially — the deployment, not the infrastructure-as-code

## Context

Everything about OpsAtlas ran on a laptop. Phase 10 packaged the control plane
and the console as containers and `make up-app` ran them, which proved the
images work and proved nothing about running them anywhere.

That was a real limit on both of this project's jobs. A control plane on
`localhost` can only probe `localhost`, so the monitoring is a demonstration
rather than a use; and the evidence a reader can check is a repository they have
to build rather than a system they can open.

The roadmap's phase 12 is "Terraform, Kubernetes, Helm, k6", waiting on "a
deployed target worth measuring". That condition is circular as written — the
target is what phase 12 was supposed to produce — and the circularity is what
kept it closed. Splitting it is the way out: deploy first with the simplest
thing that genuinely runs, and let the infrastructure-as-code describe something
that exists.

The shape of what has to run was decided by ADR 0013 and is not small. Five
containers: the control plane, the console, PostgreSQL, the identity provider
and its MongoDB, plus the observer, which phase 10 never packaged and without
which nothing probes. They are coupled — the control plane will not start
without its database, and nothing authenticates unless the issuer's public
hostname matches the `iss` in the tokens it signs.

## Decision

**One virtual machine, running `deploy/production/docker-compose.yml` behind
Caddy, with images pulled from GHCR by tag.**

Concretely:

- **Compose, not Kubernetes.** The topology is already expressed as a compose
  file that is exercised locally on every `make up-app`. A cluster for five
  coupled containers on one host adds a control plane to operate in order to run
  a control plane.
- **Caddy terminates TLS**, obtaining and renewing Let's Encrypt certificates
  itself. Chosen over nginx for exactly one property: nothing in the config
  names a certificate, a renewal timer or a reload hook, so there is nothing to
  forget to renew. TLS is not optional here — the console keeps the signed-in
  user's token in a cookie and forwards it.
- **Three hostnames**, one for the console, one for the API and one for the
  issuer. The issuer's is load-bearing rather than cosmetic: it is stamped into
  every token's `iss` and compared by the control plane, so changing it
  invalidates every token in circulation.
- **Images are pulled, never built on the box**, by a tag that is a commit SHA
  or a release tag. `latest` is not a deployable identifier — a running version
  you cannot name is one you cannot roll back — so `.github/workflows/publish.yml`
  never produces one.
- **Nothing is published except 80 and 443.** The local compose file publishes
  PostgreSQL on 5432 and MongoDB on 27017, the second with no authentication at
  all. That is correct on a laptop and is an open database on a public address.
- **No credential has a default.** Every secret is `${VAR:?message}`, so a
  missing one stops the stack naming itself rather than starting with the
  development value — `ACCESS_TOKEN_SECRET` in particular, whose local fallback
  is a string committed to a public repository.
- **The telemetry stack is not deployed.** Collector, Tempo, Prometheus and
  Grafana are four more containers and most of a small box's memory. They stay a
  local profile until the box has room or the deployment has a question they
  answer.

## Alternatives rejected

**Fly.io**, which was the recommendation until the domain question was settled.
Its advantage was `*.fly.dev` hostnames with managed certificates, which matters
enormously with no domain and not at all with one. Against it: roughly three to
five times the monthly cost for always-on machines, a compose file that has to
be decomposed into per-app configuration so local and deployed topologies drift
apart, and no managed MongoDB for the issuer.

**Kubernetes, via a managed cluster.** The honest version of the argument for it
is that it is what the roles this project is evidence for actually run. The
honest version of the argument against is that five containers that must start
in order on one host is the case where an orchestrator's scheduling, service
discovery and rollout machinery all cost something and return nothing. Deferred,
not rejected — and it is a better exercise against a deployment that exists.

**Terraform for the box itself.** One VM, one firewall and three DNS records is
under an hour of clicking and a week of learning a provider's resource model.
The box is worth describing in code once its shape has stopped changing, which
it has not.

**Building images on the server.** Removes the registry and the workflow. Also
means a JDK, Gradle and a pnpm store on the production host, a `git pull` as the
deployment mechanism, and a compile competing for memory with the thing serving
traffic. The Dockerfiles already exist to avoid exactly this.

**A managed database instead of PostgreSQL in compose.** The right call at the
point where losing the box means losing the data, and it is a change of one
environment variable when that point arrives. Today it doubles the bill to
protect a catalog that can be re-registered from the manifests it was built
from.

## Consequences

**The bad, stated plainly:**

- **It is one box, so it is a single point of failure.** No redundancy, no
  failover, no zero-downtime deploy: `docker compose up -d` stops and starts
  containers, and the console is down for the seconds that takes. Nothing about
  this deployment should be described as highly available.
- **Backups are not solved by this ADR.** The PostgreSQL volume is the system of
  record and nothing copies it off the host yet. Until something does, "the data
  survives" is untrue and must not be claimed.
- **The host is now a thing to patch.** Unattended upgrades and image bumps are
  an ongoing obligation that a laptop did not carry.
- **A public address is a real attack surface.** The mitigations are real —
  every `/api/v1` endpoint requires a verified token, an authenticated but
  unprovisioned caller gets 403, no database port is exposed — but this
  application has still never been security-tested or load-tested, and deploying
  it does not change that sentence in the README.
- **`OPSATLAS_SWAGGER_UI` stays off here.** It is a page for exploring an API,
  and this API is on the internet.

**The good:**

- The observer runs continuously against real endpoints, which is the first time
  the health data means anything.
- The unauthenticated GitHub polling limit of 60 requests an hour is now shared
  by everything on that IP, making `OPSATLAS_GITHUB_TOKEN` matter in practice
  rather than in theory.
- Phase 12's infrastructure-as-code now has something to describe, and a
  before-and-after to be measured against.
