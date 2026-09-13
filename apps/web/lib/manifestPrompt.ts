import type { PolicyRules } from "@opsatlas/contracts";
import { EXAMPLE_MANIFEST } from "./exampleManifest";

/**
 * Builds the prompt a person copies from /register, pastes into whichever
 * assistant they use, and answers with a service.yaml they can paste back.
 *
 * The prompt is generated from the versioned JSON Schema and from the policy
 * rules the control plane serves - never from a description of them written out
 * here. A hand-written copy of the contract is a copy that drifts, and the
 * failure mode is quiet: the prompt keeps teaching last month's shape and the
 * manifests it produces start getting rejected for reasons the prompt caused.
 *
 * The schema is the single copy that ADR 0002 requires, and the rules come from
 * GET /api/v1/policy/rules, so the control plane remains the only thing that
 * states what policy is - CLAUDE.md section 5.
 */

/** The subset of JSON Schema this summariser understands. */
export interface JsonSchemaNode {
  type?: string;
  const?: unknown;
  enum?: unknown[];
  default?: unknown;
  pattern?: string;
  format?: string;
  description?: string;
  minimum?: number;
  maximum?: number;
  minLength?: number;
  maxLength?: number;
  minItems?: number;
  maxItems?: number;
  required?: string[];
  additionalProperties?: boolean;
  properties?: Record<string, JsonSchemaNode>;
  items?: JsonSchemaNode;
  oneOf?: JsonSchemaNode[];
  $ref?: string;
  $defs?: Record<string, JsonSchemaNode>;
}

export function buildManifestPrompt(schema: JsonSchemaNode, policy: PolicyRules | null): string {
  const sections = [
    task(),
    requiredShape(schema),
    fieldReference(schema),
    policySection(policy),
    example(),
    yourService(),
  ];
  return sections.join("\n\n");
}

function task(): string {
  return [
    "Write a service.yaml manifest for OpsAtlas, a service catalog that registers services from this file.",
    "",
    "Rules for your answer:",
    "",
    "- Output one YAML document and nothing else. No code fence, no commentary before or after.",
    "- Only include a field the information at the end of this prompt actually supports. Omit what you",
    "  do not know instead of inventing it: a health path that does not exist or a guessed production",
    "  URL is worse than an absent field, because the catalog will be believed.",
    "- After the document, on lines beginning with #, you may list the fields you left out and what you",
    "  would need in order to fill them in.",
  ].join("\n");
}

function requiredShape(schema: JsonSchemaNode): string {
  const lines = ["## The shape"];
  for (const [name, node] of Object.entries(schema.properties ?? {})) {
    if (node.const !== undefined) {
      // The schema says why apiVersion is fixed; kind simply is. Taking the
      // reason from the node rather than writing one here keeps the prompt from
      // asserting something the contract does not.
      const reason = firstSentence(node.description) ?? "Exactly this.";
      lines.push(`${name}: ${String(node.const)}    # ${reason}`);
    }
  }
  const top = Object.keys(schema.properties ?? {});
  if (top.length > 0) {
    lines.push("");
    lines.push(`The top level accepts only these keys: ${top.join(", ")}.`);
  }
  if (schema.required && schema.required.length > 0) {
    lines.push(`All of these are required: ${schema.required.join(", ")}.`);
  }
  return lines.join("\n");
}

/**
 * Walks the schema and states each field's constraints in words a model can
 * follow, keeping the regular expressions verbatim because they are the precise
 * form of the rule the validator will actually apply.
 */
function fieldReference(schema: JsonSchemaNode): string {
  const lines = ["## The fields"];
  for (const name of ["metadata", "spec"]) {
    const node = resolve(schema, schema.properties?.[name]);
    if (!node) continue;
    lines.push("");
    lines.push(`${name}${describeObjectHeader(node)}`);
    lines.push(...describeProperties(schema, node, "  "));
  }
  return lines.join("\n");
}

function describeObjectHeader(node: JsonSchemaNode): string {
  const notes: string[] = [];
  if (node.required && node.required.length > 0) {
    notes.push(`required: ${node.required.join(", ")}`);
  }
  if (node.additionalProperties === false) {
    notes.push("no other keys are accepted");
  }
  return notes.length > 0 ? ` (${notes.join("; ")})` : "";
}

function describeProperties(root: JsonSchemaNode, node: JsonSchemaNode, indent: string): string[] {
  const lines: string[] = [];
  for (const [name, raw] of Object.entries(node.properties ?? {})) {
    const property = resolve(root, raw);
    if (!property) continue;

    const isRequired = (node.required ?? []).includes(name);
    const constraints = describeConstraints(property);

    // Nested objects and arrays of objects get their own indented block, so a
    // nested shape is stated rather than left to be guessed from the example.
    const isList = property.type === "array";
    const nested = isList ? resolve(root, property.items) : property;
    const nestedIsObject = Boolean(nested?.properties);

    // An object's own constraints belong on its line; an array's describe each
    // item, so they get announced as such rather than read as the list's.
    const inlineHeader = nestedIsObject && !isList ? describeObjectHeader(nested!) : "";
    lines.push(`${indent}${name}${isRequired ? " (required)" : ""}${constraints ? " — " + constraints : ""}${inlineHeader}`);

    if (nested && nestedIsObject) {
      if (isList) {
        lines.push(`${indent}  each item is an object${describeObjectHeader(nested)}`);
      }
      lines.push(...describeProperties(root, nested, indent + "  "));
    } else if (nested && nested.oneOf) {
      lines.push(...describeOneOf(root, nested, indent + "  "));
    }
  }
  return lines;
}

function describeOneOf(root: JsonSchemaNode, node: JsonSchemaNode, indent: string): string[] {
  const lines: string[] = [];
  for (const branch of node.oneOf ?? []) {
    const resolved = resolve(root, branch);
    if (!resolved) continue;
    if (resolved.properties) {
      lines.push(`${indent}or an object${describeObjectHeader(resolved)}:`);
      lines.push(...describeProperties(root, resolved, indent + "  "));
    } else {
      lines.push(`${indent}either ${describeConstraints(resolved)}`);
    }
  }
  return lines;
}

function describeConstraints(node: JsonSchemaNode): string {
  const parts: string[] = [];

  if (node.const !== undefined) parts.push(`exactly ${JSON.stringify(node.const)}`);
  if (node.enum) parts.push(`one of ${node.enum.map((value) => JSON.stringify(value)).join(", ")}`);
  if (node.type === "array") {
    const bounds = countBounds(node.minItems, node.maxItems);
    parts.push(`a list${bounds ? ` of ${bounds}` : ""}`);
  } else if (node.type && !node.enum && node.const === undefined) {
    parts.push(node.type);
  }

  if (node.type === "integer" || node.type === "number") {
    if (node.minimum !== undefined && node.maximum !== undefined) {
      parts.push(`from ${node.minimum} to ${node.maximum}`);
    } else if (node.minimum !== undefined) {
      parts.push(`at least ${node.minimum}`);
    } else if (node.maximum !== undefined) {
      parts.push(`at most ${node.maximum}`);
    }
  }

  if (node.pattern) parts.push(`matching ${node.pattern}`);
  if (node.maxLength !== undefined) parts.push(`at most ${node.maxLength} characters`);
  if (node.default !== undefined) parts.push(`defaults to ${JSON.stringify(node.default)}`);

  // The description carries what the constraints cannot - what a tier number
  // means, why a field is optional - so it is the last and longest part.
  if (node.description) parts.push(node.description);

  return parts.join(", ");
}

/** The first sentence of a description, for places that need a short reason. */
function firstSentence(description: string | undefined): string | undefined {
  if (!description) return undefined;
  const match = /^.*?[.!?](\s|$)/.exec(description);
  return (match ? match[0] : description).trim();
}

function countBounds(min: number | undefined, max: number | undefined): string {
  if (min !== undefined && max !== undefined) return `${min} to ${max} items`;
  if (min !== undefined) return `at least ${min} item${min === 1 ? "" : "s"}`;
  if (max !== undefined) return `at most ${max} items`;
  return "";
}

function resolve(root: JsonSchemaNode, node: JsonSchemaNode | undefined): JsonSchemaNode | undefined {
  if (!node) return undefined;
  if (!node.$ref) return node;
  const name = node.$ref.replace("#/$defs/", "");
  const target = root.$defs?.[name];
  // A $ref this summariser cannot resolve must not silently vanish from the
  // prompt; returning the unresolved node keeps whatever it does state.
  return target ? { ...target, description: node.description ?? target.description } : node;
}

function policySection(policy: PolicyRules | null): string {
  if (!policy) {
    return [
      "## What the scorecard checks",
      "",
      "The rule list could not be read from the control plane, so this prompt covers the schema only.",
      "A manifest built from it will validate; it may still score lower than it could, because the",
      "fields that policy rewards are optional to the schema.",
    ].join("\n");
  }

  const lines = [
    "## What the scorecard checks",
    "",
    `Policy set ${policy.policySetVersion}. Every field these rules look for is optional to the schema`,
    "and scored anyway, so include the ones that genuinely apply — that is the difference between a",
    "manifest that validates and one that scores well.",
    "",
  ];
  for (const rule of policy.rules) {
    lines.push(`- ${rule.title}: ${rule.rationale}`);
  }
  lines.push("");
  lines.push(policy.declarationOnlyNotice);
  return lines.join("\n");
}

function example(): string {
  return ["## A complete example", "", EXAMPLE_MANIFEST.trimEnd()].join("\n");
}

function yourService(): string {
  return [
    "## The service to describe",
    "",
    "Replace this section with what you know about the service, then write the manifest:",
    "",
    "- What it does, and the repository it lives in as owner/name.",
    "- Each environment and its URL.",
    "- How it is operated: who owns it, its availability target, where the runbook lives.",
    "- What it depends on, and the customer journeys that break when it is down.",
  ].join("\n");
}
