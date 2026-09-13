# 0012 — Secret scanning is a grep, not a scanner

- **Status:** Accepted
- **Date:** 2026-09-13
- **Phase:** after 8 — close-out

## Context

`CLAUDE.md` §3 rule 5 says secrets never enter source control. Nothing checked
it. That was tolerable while the repository was local; it stopped being
tolerable when the repository became public — which happened without this
document noticing, and was found while investigating why CI had never run.

A leaked credential is the one mistake in this repository that a later commit
cannot undo. Deleting a secret in a new commit leaves it in the history of a
public repository, and the only real remedy is to rotate it. So the check that
matters is the one that runs before the push, not the report that arrives after.

## Decision

**A shell script of regular expressions, run by `make check-secrets` and as the
first step of CI.**

It looks for credential formats that are unmistakable — GitHub tokens, AWS
access key ids, PEM private keys, Slack tokens, signed JWTs — and for files that
should never be tracked at all: `.env`, `.pem`, `.key`, `id_rsa`. It searches
untracked files as well as tracked ones, so a secret is caught before `git add`
rather than after.

It lives in `scripts/`, which is a deviation from the layout in `CLAUDE.md` §4
and is recorded here for that reason. The directory holds this one file and
should stay that way; a `scripts/` directory is where a repository quietly
accumulates the things nobody owns.

## Alternatives rejected

**gitleaks or trufflehog in CI.** Better detection than this by a wide margin:
entropy analysis, hundreds of maintained rules, and verification that a found
credential is live. Rejected for now because the value here is the local
pre-push run, and that means every contributor installing a binary — or a
`make` target that downloads one, which is a supply-chain dependency acquired to
protect against a supply-chain problem. Worth revisiting the moment there is a
second contributor, and the ceiling of the grep is the reason to revisit.

**GitHub's own secret scanning.** Free on public repositories, needs no code,
and is genuinely good — it also notifies the provider so a leaked token can be
revoked. Rejected as *the* answer rather than as an addition: it runs after the
push, which is after the secret is public. It is on for this repository by
virtue of being public, and it complements this rather than replacing it.

**A pre-commit hook.** The right place for it, and it is not installed by
cloning, so it protects exactly the people who already remembered. `make test`
runs this instead, which is a weaker guarantee honestly stated.

## Consequences

**Good.** A `ghp_…` in an untracked file fails `make test` and fails CI's first
and cheapest job. Both directions were checked by planting a token and a tracked
`.env` and watching the check fail on each.

**It raises the floor; it is not a proof, and the script says so in its own
header.** A password in a config file, a token in a format it does not know, or
anything base64'd past recognition all pass. The failure mode to guard against
is somebody reading a green run as "this repository has no secrets in it" — it
means "none of eight known shapes are present".

**False positives would be worse than the check.** A scanner that cries wolf
gets disabled, and a disabled check is indistinguishable from no check while
looking like one. The patterns are deliberately narrow for that reason, which is
the same trade as the ceiling above: it will miss things before it will shout
about nothing.

**It cannot help with what is already published.** The history was scanned once,
by hand, when this was written: no key files, and none of these shapes in any
commit. That is a statement about a moment, not a guarantee about the future,
and it carries the same ceiling as everything else here.
