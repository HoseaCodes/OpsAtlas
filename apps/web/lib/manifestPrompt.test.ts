import { describe, expect, it } from "vitest";
import type { PolicyRules } from "@opsatlas/contracts";
import realSchema from "@opsatlas/contracts/schema";
import { buildManifestPrompt, type JsonSchemaNode } from "./manifestPrompt";

const schema = realSchema as JsonSchemaNode;

const policy: PolicyRules = {
  policySetVersion: "2026-09-12.1",
  rules: [
    { id: "owner-declared", title: "Owner", rationale: "An unowned service has nobody to page.", declarationOnly: true },
    { id: "slo-defined", title: "SLO", rationale: "An availability target is what an error budget is measured against.", declarationOnly: true },
  ],
  declarationOnlyNotice: "These checks read what a service.yaml declares.",
};

describe("the manifest prompt", () => {
  it("pins the two fields that select the schema version", () => {
    const prompt = buildManifestPrompt(schema, policy);
    // A model that guesses either of these produces a document the control
    // plane rejects outright rather than one it can locate a problem in.
    expect(prompt).toContain("opsatlas.ambitiousconcepts.io/v1");
    expect(prompt).toContain("kind: Service");
  });

  it("states every field the schema defines, so growth cannot go unmentioned", () => {
    const prompt = buildManifestPrompt(schema, policy);

    // Walking the real schema rather than listing field names here is what
    // makes this test notice a field added to the contract later. A summariser
    // that cannot express a new construct fails instead of quietly dropping it.
    const named: string[] = [];
    const collect = (node: JsonSchemaNode | undefined) => {
      if (!node) return;
      for (const [name, child] of Object.entries(node.properties ?? {})) {
        named.push(name);
        collect(child);
        collect(child.items);
        (child.oneOf ?? []).forEach(collect);
      }
    };
    collect(schema);
    for (const definition of Object.values(schema.$defs ?? {})) {
      collect(definition);
      (definition.oneOf ?? []).forEach(collect);
    }

    expect(named.length).toBeGreaterThan(10);
    for (const field of new Set(named)) {
      expect(prompt, `the prompt never mentions the "${field}" field`).toContain(field);
    }
  });

  it("carries the schema's own constraints rather than a description of them", () => {
    const prompt = buildManifestPrompt(schema, policy);
    const metadata = schema.$defs?.metadata;
    const namePattern = metadata?.properties?.name?.pattern;
    const repositoryPattern = metadata?.properties?.repository?.pattern;

    expect(namePattern).toBeTruthy();
    expect(prompt).toContain(namePattern!);
    expect(prompt).toContain(repositoryPattern!);
  });

  it("explains what the tier numbers mean, in the schema's own words", () => {
    // Tier is the one field where the wrong number changes which rules apply at
    // all, so the meaning has to travel with the prompt. It comes from the
    // schema's description rather than a second phrasing kept here.
    const prompt = buildManifestPrompt(schema, policy);
    const tierDescription = schema.$defs?.spec?.properties?.tier?.description;

    expect(tierDescription).toMatch(/1 = /);
    expect(prompt).toContain(tierDescription!);
  });

  it("lists the policy rules and says they only read declarations", () => {
    const prompt = buildManifestPrompt(schema, policy);
    expect(prompt).toContain("2026-09-12.1");
    expect(prompt).toContain("An unowned service has nobody to page.");
    expect(prompt).toContain("These checks read what a service.yaml declares.");
  });

  it("tells the assistant to omit what it does not know", () => {
    // The whole point: a catalog is believed, so an invented URL is worse than
    // an absent one.
    const prompt = buildManifestPrompt(schema, policy);
    expect(prompt).toMatch(/omit what you\s+do not know/i);
  });

  it("still produces a usable prompt when the rules cannot be read", () => {
    const prompt = buildManifestPrompt(schema, null);
    expect(prompt).toContain("opsatlas.ambitiousconcepts.io/v1");
    expect(prompt).toContain("covers the schema only");
    // It must not claim a policy set version it never received.
    expect(prompt).not.toContain("2026-09-12.1");
  });

  it("asks for one YAML document and no prose around it", () => {
    const prompt = buildManifestPrompt(schema, policy);
    expect(prompt).toContain("No code fence");
  });
});
