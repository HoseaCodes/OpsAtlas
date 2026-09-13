# 0005 — OpenAPI is generated from the code, committed, and drift-checked in CI

- **Status:** Accepted, amended 2026-09-12 (see *Amendment*)
- **Date:** 2026-09-12
- **Phase:** Slice one

## Context

`CLAUDE.md` §9 requires the OpenAPI document to be generated from the code rather
than hand-maintained. §5 requires the frontend to use a TypeScript client
generated from that document, and forbids hand-writing API types the generator
could produce.

Those two rules leave one thing open: where the generated artefacts live, and what
stops them going stale. "Generated" without an enforcement mechanism decays into
"generated once, last March".

## Decision

1. springdoc generates the document from the controllers.
2. `make openapi` writes `packages/contracts/openapi/control-plane.json`, and that
   file is **committed**.
3. `packages/contracts` runs `openapi-typescript` over it into
   `src/generated/api.ts`, **also committed**.
4. `src/client.ts` is a small hand-written typed `fetch` wrapper over those
   generated types. §5 forbids hand-writing *types*, not a fetch call.
5. **CI regenerates the document and fails on `git diff --exit-code`.**

Point 5 is the decision. The rest is plumbing. Drift is prevented by a check that
fails the build, not by a convention in a contributing guide.

## Alternatives rejected

**Generate at build time, commit nothing.** Drift becomes structurally impossible,
which is the strongest form of this guarantee. Rejected because every frontend
build would then require a JVM, a booted backend and a running database.
`apps/web` stops being independently buildable, `pnpm dev` acquires a Postgres
dependency, and CI gets substantially slower. The cost falls on every build to
prevent a problem a single CI check catches.

**A fully generated client — `@hey-api/openapi-ts` or `orval`.** More out of the
box: hooks, runtime validation, request builders. Rejected because it adds a
runtime dependency and a large body of generated call-site code to review, for a
slice-one surface of five endpoints. A ~70-line wrapper over generated types is
less to own and easier to read.

**Hand-maintained OpenAPI YAML as the source of truth, with server stubs
generated from it.** A legitimate contract-first approach. Rejected because §9
explicitly specifies code-first, and because a hand-maintained document drifts
from the implementation in the direction that is hardest to detect — the document
stays plausible while the server changes underneath it.

## Consequences

**Good.** The frontend builds without a backend. The API surface is reviewable in
a diff — a breaking change to an endpoint shows up as a changed line in a
committed JSON file, in the same pull request that caused it. Drift fails the
build rather than surfacing as a runtime type error.

**The cost, stated plainly.** Two generated files are committed, so every API
change produces diff noise in files nobody edits by hand, and a contributor who
forgets to regenerate gets a CI failure rather than a helpful local error. A
pre-commit hook would soften that; it is not in slice one.

**A real wrinkle.** The springdoc Gradle plugin boots the application to produce
the document, so `make openapi` requires a running PostgreSQL. That makes it the
one Make target that is not database-free, and it means the CI drift check must
start the compose database before it can run. Deriving the document from a MockMvc
test slice would avoid this, at the cost of a document one step further from what
the running server actually serves. The dependency on a live boot is accepted
deliberately: the document should describe the server, not a test harness.


---

## Amendment — 2026-09-12, during phase 4

**What changed.** The springdoc Gradle plugin was replaced by a Gradle test task,
`generateOpenApiDocument`, which boots the same application on a random port and
fetches `/v3/api-docs` over a real socket.

**Why this keeps the decision rather than reversing it.** The stated reason for
preferring the plugin over a MockMvc slice was that the document should describe
"the server, not a test harness" — that the application should actually boot. It
still does: the same Spring context, the same controllers, a real HTTP server on a
real port. What was dropped is the plugin's process management, which added a
failure mode without adding fidelity.

**What this costs.** The generator lives in the test source set, where a reader
may reasonably expect assertions about behaviour rather than a file being
written. Two things mitigate it: the task is excluded from `test` and run only by
`make openapi`, so the suite never writes into the working tree; and the
generator asserts that all five endpoints appear before writing, because a
document describing nothing would still be valid JSON and would still commit
cleanly.

**A second amendment: the contract now states which fields are required.**
springdoc emitted no `required` array at all, so every field in the generated
TypeScript was optional. That is a contract defect, not a frontend inconvenience:
a consumer then has to defend against absences that cannot happen, and the ones
that genuinely can happen stop standing out. `OpenApiConfiguration` inverts the
default — required unless explicitly `@Schema(nullable = true)` — so optionality
is now a deliberate statement about the domain rather than a default nobody
chose. The cost is that a genuinely nullable field which nobody annotates will be
described as required, and the lie will be discovered by a consumer rather than
by the build.
