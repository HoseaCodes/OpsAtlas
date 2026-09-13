#!/usr/bin/env node
/**
 * Checks every fixture in examples/services against the versioned service.yaml
 * schema, and asserts that every fixture in examples/services/invalid fails in
 * exactly the way examples/services/invalid/expected.json says it should.
 *
 * Two things are being verified here, not one:
 *   1. the valid manifests really are valid, and
 *   2. the invalid ones produce an actionable, stable JSON Pointer - which is
 *      what CLAUDE.md section 8 requires and what "Invalid YAML" fails to do.
 *
 * This runs without a JVM, a database or a network, so it is the first thing
 * in the build that can fail honestly.
 */
import { readFileSync, readdirSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import { parse as parseYaml } from "yaml";

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(here, "..", "..", "..");
const schemaPath = join(here, "..", "schemas", "service.v1.schema.json");
const validDir = join(repoRoot, "examples", "services");
const invalidDir = join(validDir, "invalid");

const schema = JSON.parse(readFileSync(schemaPath, "utf8"));
const ajv = new Ajv2020({ allErrors: true, strict: true, allowUnionTypes: true });
addFormats(ajv);
const validate = ajv.compile(schema);

/**
 * Semantic rules that JSON Schema cannot express. Kept deliberately small.
 * The control plane's ManifestNormalizer reimplements these in Java; the
 * expectations in expected.json are what keep the two honest about each other.
 */
function semanticViolations(doc) {
  const out = [];
  const envs = doc?.spec?.environments;
  if (Array.isArray(envs)) {
    const seen = new Map();
    envs.forEach((env, i) => {
      const name = env?.name;
      if (typeof name !== "string") return;
      if (seen.has(name)) {
        out.push({
          pointer: `/spec/environments/${i}/name`,
          keyword: "duplicate",
          message: `environment "${name}" is declared twice; the first is at /spec/environments/${seen.get(name)}/name`,
        });
      } else {
        seen.set(name, i);
      }
    });
  }
  return out;
}

const schemaViolations = () =>
  (validate.errors ?? []).map((e) => ({
    pointer: e.instancePath === "" ? "/" : e.instancePath,
    keyword: e.keyword,
    message:
      e.keyword === "additionalProperties"
        ? `unknown property "${e.params.additionalProperty}"`
        : e.keyword === "required"
          ? `missing required property "${e.params.missingProperty}"`
          : e.message,
  }));

let failures = 0;
const fail = (file, msg) => {
  failures++;
  console.error(`  FAIL ${file}\n        ${msg}`);
};
const pass = (file, msg) => console.log(`  ok   ${file}  (${msg})`);

// -- Valid fixtures --------------------------------------------------------
console.log("\nexamples/services -- must validate");
const validFiles = readdirSync(validDir).filter((f) => f.endsWith(".yaml")).sort();
if (validFiles.length === 0) fail("examples/services", "no fixtures found");

for (const file of validFiles) {
  let doc;
  try {
    doc = parseYaml(readFileSync(join(validDir, file), "utf8"));
  } catch (err) {
    fail(file, `YAML did not parse: ${err.message}`);
    continue;
  }
  if (!validate(doc)) {
    fail(file, schemaViolations().map((v) => `${v.pointer}: ${v.message}`).join("\n        "));
    continue;
  }
  const semantic = semanticViolations(doc);
  if (semantic.length > 0) {
    fail(file, semantic.map((v) => `${v.pointer}: ${v.message}`).join("\n        "));
    continue;
  }
  pass(file, `tier ${doc.spec.tier}, ${doc.spec.environments.length} environment(s)`);
}

// -- Invalid fixtures ------------------------------------------------------
console.log("\nexamples/services/invalid -- must fail, at the stated pointer");
const expected = JSON.parse(readFileSync(join(invalidDir, "expected.json"), "utf8")).fixtures;
const invalidFiles = readdirSync(invalidDir).filter((f) => f.endsWith(".yaml")).sort();

for (const file of invalidFiles) {
  const spec = expected[file];
  if (!spec) {
    fail(file, "no entry in expected.json -- every invalid fixture needs a stated expectation");
    continue;
  }

  let doc;
  try {
    doc = parseYaml(readFileSync(join(invalidDir, file), "utf8"));
  } catch (err) {
    fail(file, `YAML did not parse: ${err.message}`);
    continue;
  }

  const schemaOk = validate(doc);
  const actual = schemaOk ? semanticViolations(doc) : schemaViolations();
  const actualStage = schemaOk ? "semantic" : "schema";

  if (schemaOk && actual.length === 0) {
    fail(file, "expected a violation, but the manifest validated cleanly");
    continue;
  }
  if (actualStage !== spec.stage) {
    fail(file, `expected to fail at the ${spec.stage} stage, failed at ${actualStage}`);
    continue;
  }

  const missing = spec.violations.filter(
    (want) => !actual.some((got) => got.pointer === want.pointer && got.keyword === want.keyword),
  );
  if (missing.length > 0) {
    fail(
      file,
      `expected ${missing.map((m) => `${m.pointer} (${m.keyword})`).join(", ")}\n        ` +
        `got      ${actual.map((a) => `${a.pointer} (${a.keyword})`).join(", ") || "nothing"}`,
    );
    continue;
  }
  pass(file, `${spec.stage}: ${spec.violations.map((v) => v.pointer).join(", ")}`);
}

// -- Fixtures named in expected.json but absent from disk -------------------
for (const file of Object.keys(expected)) {
  if (!invalidFiles.includes(file)) fail(file, "expected.json names a fixture that does not exist");
}

const checked = validFiles.length + invalidFiles.length;
if (failures > 0) {
  console.error(`\n${failures} of ${checked} fixtures did not behave as specified.\n`);
  process.exit(1);
}
console.log(`\n${checked} fixtures checked, all as specified.\n`);
