# 0006 — The v3 HTML prototype is not committed

- **Status:** Accepted
- **Date:** 2026-09-12
- **Phase:** Slice one

## Context

`service-operations-platform-v3.html` is a 125 KB, 1,665-line self-contained
prototype. It is the visual and product reference for OpsAtlas, and `CLAUDE.md`
§10 directs that it be read once, its design decisions extracted into
`docs/design/tokens.md`, and worked from thereafter.

It also contains its own seeded mock dataset. Every service in that dataset is
insurance-domain: `claims-intake-api`, `quote-engine`, `policy-docs-worker`,
`agent-portal`, `document-upload-api`, under an organization called `acme`. The
incidents, teams and activity feed are written in the same domain.

`CLAUDE.md` §10 states the rule those names break, in its own words: example
services are neutral, "never insurance-specific names, never anything that reads
as derived from an employer's systems."

`CLAUDE.md` §1 states who reads this repository: "the code is read by people
deciding whether to hire."

## Decision

The prototype is **not committed.** It is listed in `.gitignore` with a comment
explaining why and pointing at this ADR.

Everything of value in it — the colour and type tokens, the shape-not-hue status
encodings, the layout grid, the breakpoints, the information architecture, the
required states — is extracted into `docs/design/tokens.md`, which is committed
and is the reference the rebuild works from.

`examples/services/` replaces its seed data with neutral fixtures that are also
real test inputs.

## Alternatives rejected

**Commit it under `docs/design/prototype.html` with a header comment marking it
superseded.** Preserves the full reference and the project's own history, and is
honest about the file's status. Rejected because a header comment does not travel:
a reader opening the rendered page sees `claims-intake-api` and `acme`, not the
comment. The repository would then contain, in its most visually striking file,
exactly what its own standing rules say to avoid.

**Rewrite the prototype's seed data to neutral names and commit it.** Keeps the
reference and fixes the naming. Rejected because the prototype is explicitly not
going to be ported (`CLAUDE.md` §2), so the rewritten file would be a 125 KB
artefact that is maintained by nobody and diverges from the real console the day
`apps/web` ships. Two UIs claiming to be the product is worse than one.

**Commit it as-is and note the naming in the README.** Rejected: a disclaimer
explaining why the repository violates its own rule is weaker than not violating
it.

## Consequences

**Good.** Nothing in the repository contradicts its own standing rules. The design
reference is a 200-line Markdown note that can be read in two minutes and reviewed
in a diff, rather than a 125 KB file that must be rendered to be understood. There
is exactly one thing in the repository claiming to be the OpsAtlas interface.

**The cost, stated plainly.** The prototype is genuinely good, and it is real
evidence of design work that the extracted note only describes. A reader cannot
see it. The greyscale status system in particular is more convincing rendered than
written down, and `docs/design/tokens.md` is an ASCII diagram where the original
was a working artefact.

**Also.** The file still exists on the author's machine and is ignored, not
deleted — so it can be recovered, renamed, re-seeded and committed later if that
trade is judged wrong. Reversing this decision costs one line in `.gitignore`.

**And.** If the extracted note is ever wrong or incomplete, there is no committed
source to check it against. `docs/design/tokens.md` is now the authority on a
design it does not contain, which puts unusual weight on that note being accurate.
