# 0002 — service.yaml ingestion: parse to plain data, validate, then bind

- **Status:** Accepted
- **Date:** 2026-09-12
- **Phase:** Slice one

## Context

`CLAUDE.md` §3 rule 4 is the hardest constraint in the project: content from
monitored repositories is never executed. No eval, no shell out, no templating,
no deserialization into arbitrary types. §8 adds that parsing must use a safe
loader with no custom tags, no unbounded anchor expansion, and size-capped input,
and that validation errors must be actionable and structured — a JSON Pointer
path, what was expected, what was found. "Invalid YAML" is explicitly not an
acceptable error message.

These are two different requirements. The first is about what a malicious
manifest can make the process do. The second is about what a careless manifest
tells its author.

## Decision

A four-stage pipeline in `catalog.internal.ingest`, where no stage can run before
the one above it has succeeded.

**1. Cap.** Reject input over 64 KiB before a parser sees a byte.

**2. Parse — snakeyaml-engine.** The YAML 1.2 `Load` API, configured with:

```java
LoadSettings.builder()
    .setAllowDuplicateKeys(false)
    .setAllowRecursiveKeys(false)
    .setMaxAliasesForCollections(0)   // no anchor expansion at all
    .setCodePointLimit(65536)
    .build();
```

This API returns only `Map`, `List`, `String`, `Number`, `Boolean` and `null`.
It has no constructor-based deserialization and no tag resolution to Java types,
so rule 4 is satisfied **structurally** — not by remembering to set a flag.

**3. Validate — JSON Schema.** The parsed tree is converted to a `JsonNode` and
validated against the schema selected by the exact `apiVersion` string. An
unrecognised `apiVersion` is a violation at pointer `/apiVersion`, never a
fallback to the nearest known version.

**4. Bind.** Only now is the validated tree mapped onto `ManifestV1` Java records
with Jackson. The shape has already been proven, so binding cannot surprise us.

**Semantic checks follow schema validation.** Rules JSON Schema cannot express —
uniqueness of environment names by their `name` property — run in the normalizer,
which is also what lets the error point at `/spec/environments/1/name` rather
than at the whole array.

**One copy of the schema.** `packages/contracts/schemas/service.v1.schema.json`
is the only copy; Gradle `processResources` copies it into the control-plane jar.
Frontend and backend validate against identical bytes.

**Violation shape.** Every violation carries `{ pointer, keyword, expected, found,
message }`, and the mapper has a fallback message for every keyword it can emit,
so no code path can produce a bare "invalid".

## Alternatives rejected

**Jackson `YAMLFactory` + `ObjectMapper`.** One dependency instead of two, and far
less code: read straight into the record. Rejected because it binds directly to
Java types, which puts the default-typing and polymorphic-binding family of
problems inside the exact code path §3 rule 4 is about. Parser limits — alias
expansion, duplicate keys, code-point count — are also less directly controllable.
Safety here would depend on configuration staying correct forever.

**SnakeYAML 1.x with `SafeConstructor`.** Widely used and adequate. Rejected
because safety is a constructor argument someone can change, whereas
snakeyaml-engine's `Load` API has no unsafe mode to accidentally select.

**Validating after binding, with Bean Validation only.** Much less code. Rejected
because error paths become Java field names rather than JSON Pointers into the
author's own file, which fails §8's actionability requirement outright.

## Consequences

**Good.** The unsafe path does not exist rather than being disabled. Errors point
into the user's document at a stable pointer, which is what makes
`examples/services/invalid/expected.json` a meaningful test fixture for both the
Node checker and the Java test.

**The cost, stated plainly.** The manifest shape is now expressed twice — once in
JSON Schema, once in Java records — and the two can drift. A field added to the
schema and forgotten in the record is silently dropped on binding. The mitigation
is a round-trip test asserting no field present in a fixture is lost between
parse and persist, but that test only covers fields the fixtures actually use.

**Also.** Two extra dependencies, and a slightly longer path from bytes to object
than a one-line `readValue`. Contributors will be tempted to shortcut it; the
pipeline is a single class with a single public method to make the shortcut
awkward.

## New dependencies

- `org.snakeyaml:snakeyaml-engine` — a YAML 1.2 loader whose safe API is the only
  API. This is the dependency that satisfies §3 rule 4 by construction.
- `com.networknt:json-schema-validator` — 2020-12 validation that reports instance
  pointers, which is what makes the error messages actionable per §8.
