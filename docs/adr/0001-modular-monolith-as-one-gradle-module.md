# 0001 — Modular monolith as one Gradle module with test-enforced boundaries

- **Status:** Accepted
- **Date:** 2026-09-12
- **Phase:** Slice one

## Context

`CLAUDE.md` §5 fixes the control plane as a modular monolith with six modules:
`catalog`, `integrations`, `governance`, `operations`, `identity`, `shared`.
Modules must talk through public interfaces in their own package, with no
reaching into another module's internals or repositories, and `shared` depends
on nothing.

Slice one populates four of those six. `integrations` and `operations` belong to
later phases and, under §3 rule 3, must not exist yet as empty packages.

The question is how the boundary is *enforced*, not whether it exists.

## Decision

A single Gradle project, `:apps:control-plane`, with boundaries expressed as
package structure and enforced by a test.

Each module exposes exactly two package layers:

```
com.ambitiousconcepts.opsatlas.<module>.api       interfaces and DTO records
com.ambitiousconcepts.opsatlas.<module>.internal  entities, repositories, impls
com.ambitiousconcepts.opsatlas.<module>.web       controllers (where the module has an API surface)
```

`ArchitectureTest` asserts, and fails the build on:

1. No class outside `<module>` imports anything from `<module>.internal`.
2. `shared` imports no sibling module.
3. No `@Entity` type is referenced from outside its own module.
4. No package exists for a module that is not in the current phase.

Slice one creates `shared`, `identity`, `catalog`, `governance` only.

## Alternatives rejected

**A Gradle subproject per module.** Boundaries become compile-time errors rather
than test failures, which is strictly stronger enforcement. Rejected because
slice one populates four modules, and the cost is six `build.gradle.kts` files,
cross-project test fixtures, and a slower build — paid now, for a guarantee we
can get at test time.

**Spring Modulith.** Verified boundaries, generated module documentation, and an
event publication registry that would be useful when the outbox phase arrives.
Rejected for slice one because it is a dependency outside §5's fixed stack, and
its module model is close enough to what ArchUnit gives us that adopting it now
would be buying the later phase's tool early.

**Convention only, no enforcement.** Rejected outright. An unenforced boundary is
a comment.

## Consequences

**Good.** One build file, one jar, one test task. Refactoring across module lines
is cheap while the domain is still moving. Promoting to Gradle subprojects later
is mechanical, because the package structure already matches.

**The cost, stated plainly.** The boundary is a convention checked by a test, not
by the compiler. A bad import compiles cleanly and is caught only when the test
suite runs — so a developer running a single test class can be several commits
deep in a violation before the build tells them. The enforcement is only as good
as the discipline of running `make test`, and CI is the real backstop.

**Also.** Adding `integrations` or `operations` later means updating the
allowed-module list in `ArchitectureTest`, which is deliberate: the list is the
record of which phase the codebase is actually in.

## New dependency

`com.tngtech.archunit:archunit-junit5`, test scope only. Justification per §5:
it is the mechanism that makes the module boundary real rather than aspirational.
Without it this ADR is a wish.
