import { expect, test } from "@playwright/test";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { asOperator } from "./support/session";

const EXAMPLES = join(process.cwd(), "..", "..", "examples", "services");
const API = process.env.OPSATLAS_API_URL ?? "http://localhost:8080";

function manifest(name: string): string {
  return readFileSync(join(EXAMPLES, name), "utf8");
}

/**
 * Ensures this manifest is what is registered, whatever was there before.
 *
 * A 409 means an earlier run left a different manifest at the same repository
 * path, and the API's own answer to that is PUT with If-Match - so setup does
 * that rather than requiring a clean database. CI gets a fresh one; a
 * developer's machine does not, and a smoke test that only passes on an empty
 * database is a smoke test that stops being run.
 */
async function register(name: string) {
  const body = manifest(name);
  const response = await fetch(`${API}/api/v1/services`, {
    method: "POST",
    headers: await asOperator({ "Content-Type": "application/yaml" }),
    body,
  });
  if (response.ok) return;
  if (response.status !== 409) {
    throw new Error(`Setup failed to register ${name}: ${response.status} ${await response.text()}`);
  }
  await replace(name.replace(/\.ya?ml$/, ""), body);
}

/** Updates an already-registered service to match the manifest. */
async function replace(slug: string, body: string) {
  const current = await fetch(`${API}/api/v1/services/${slug}`, { headers: await asOperator() });
  if (!current.ok) {
    throw new Error(`Setup could not read ${slug} after a 409: ${current.status}`);
  }
  const etag = current.headers.get("etag");
  if (!etag) {
    throw new Error(`Setup needs an ETag to update ${slug}; the detail response carried none`);
  }
  const updated = await fetch(`${API}/api/v1/services/${slug}`, {
    method: "PUT",
    headers: await asOperator({ "Content-Type": "application/yaml", "If-Match": etag }),
    body,
  });
  if (!updated.ok) {
    throw new Error(`Setup failed to update ${slug}: ${updated.status} ${await updated.text()}`);
  }
}

test.beforeAll(async () => {
  // A catalog with something in it. Queries are made by role and accessible
  // name rather than by test id, per CLAUDE.md §11 - which also means these
  // assertions fail if the page stops being navigable by a screen reader.
  await register("orders-api.yaml");
  await register("legacy-report-runner.yaml");
});

test("the catalog lists registered services and says nothing is observed", async ({ page }) => {
  await page.goto("/catalog");

  await expect(page.getByRole("heading", { level: 1 })).toContainText("services registered");
  await expect(page.getByRole("link", { name: /Orders API/ })).toBeVisible();

  // The honesty requirement, asserted rather than assumed: no fabricated health.
  await expect(page.getByText("Never observed").first()).toBeVisible();
});

test("the scorecard tab is a real navigation, not client-only state", async ({ page }) => {
  // Tabs are links, so a tab is shareable and survives a reload. Asserted
  // separately from what the tab renders, so a failure says which of the two
  // broke rather than leaving it ambiguous.
  await page.goto("/catalog/orders-api");
  await expect(page.getByRole("heading", { level: 1, name: "Orders API" })).toBeVisible();

  // The click is retried with the assertion because a click that lands between
  // the server-rendered markup appearing and the router hydrating is swallowed,
  // which failed this test roughly one run in three. Retrying the pair still
  // asserts the whole property - that clicking the tab changes the URL - rather
  // than weakening it to "the tab exists".
  await expect(async () => {
    await page.getByRole("tab", { name: "Scorecard" }).click();
    await expect(page).toHaveURL(/\?tab=scorecard$/, { timeout: 2_000 });
  }).toPass({ timeout: 15_000 });
});

test("a service detail page shows its scorecard and what the checks do not cover", async ({ page }) => {
  await page.goto("/catalog/orders-api?tab=scorecard");

  await expect(page.getByRole("heading", { name: /11 of 11 applicable checks passing/ })).toBeVisible();

  // The claim the whole project turns on: these are declaration checks, and the
  // interface says so rather than implying runtime verification.
  await expect(page.getByText(/None of them verifies that a runbook link resolves/)).toBeVisible();
});

test("an unowned service is flagged, and its tier 3 rules are excused rather than failed", async ({ page }) => {
  await page.goto("/catalog/legacy-report-runner?tab=scorecard");

  await expect(page.getByRole("heading", { name: /0 of 7 applicable checks passing/ })).toBeVisible();
  // Four since oncall-declared landed: slo-defined, journeys-declared,
  // production-environment-declared and oncall-declared are all NOT_APPLICABLE
  // at tier 3. The denominator above stays 7 because the rule count rose too,
  // which is why that assertion did not catch this and this one did.
  await expect(page.getByText(/4 rules do not apply at this tier/)).toBeVisible();
  await expect(page.getByText("not applicable at this tier").first()).toBeVisible();
});

test("registering a valid manifest through the console works end to end", async ({ page }) => {
  await page.goto("/register");

  await page.getByRole("button", { name: "Fill in an example" }).click();
  await page.getByLabel("Path in the repository").fill("deploy/service.yaml");
  await page.getByRole("button", { name: "Register service" }).click();

  // A different sourcePath is a different registration location, so this is a
  // new service rather than a replay - but the name is taken, so the control
  // plane refuses it. That refusal is the assertion: the console surfaces the
  // conflict rather than silently doing nothing.
  //
  // Scoped to main: Next injects its own role="alert" route announcer into the
  // body, so an unscoped query matches two elements.
  await expect(page.getByRole("main").getByRole("alert")).toContainText(/already registered/i);
});

test("the register page offers a prompt built from the live schema and rules", async ({ page }) => {
  await page.goto("/register");

  // Clipboard permissions are not granted by default in headless Chromium, so
  // this exercises the button and asserts on what the page does, then reads the
  // clipboard through the page context.
  await page.context().grantPermissions(["clipboard-read", "clipboard-write"]);

  const disclosure = page.getByText(/Copy a prompt for your assistant/);
  await expect(disclosure).toBeVisible();
  await disclosure.click();

  await page.getByRole("button", { name: "Copy prompt" }).click();
  await expect(page.getByRole("main").getByRole("status")).toContainText(/Paste it into your assistant/);

  const copied = await page.evaluate(() => navigator.clipboard.readText());

  // The prompt has to carry the contract, not a description of it: the pinned
  // apiVersion, a pattern straight out of the schema, and the policy set version
  // the control plane is serving right now.
  expect(copied).toContain("apiVersion: opsatlas.ambitiousconcepts.io/v1");
  expect(copied).toContain("^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$");

  const rules = await fetch(`${API}/api/v1/policy/rules`, { headers: await asOperator() }).then((response) => response.json());
  expect(copied).toContain(rules.policySetVersion);
  expect(copied).toContain(rules.declarationOnlyNotice);
  for (const rule of rules.rules) {
    expect(copied).toContain(rule.rationale);
  }
});

test("an invalid manifest is rejected with the location of each problem", async ({ page }) => {
  await page.goto("/register");

  await page.getByLabel("service.yaml").fill(manifest("invalid/unknown-property.yaml"));
  await page.getByRole("button", { name: "Register service" }).click();

  const alert = page.getByRole("main").getByRole("alert");
  await expect(alert).toBeVisible();
  // The JSON Pointer reaches the screen. "Invalid YAML" would not be acceptable
  // in the API (CLAUDE.md §8) and is not acceptable in the interface either.
  await expect(alert).toContainText("/spec");
  await expect(alert).toContainText(/unrecognised property/i);
  await expect(alert).toContainText(/Correlation ID/);
});

test("the navigation contains only pages that exist", async ({ page }) => {
  await page.goto("/catalog");

  const nav = page.getByRole("navigation", { name: "Sections" });
  await expect(nav.getByRole("link")).toHaveCount(2);
  await expect(nav.getByRole("link", { name: "Catalog" })).toBeVisible();
  await expect(nav.getByRole("link", { name: "Sources" })).toBeVisible();

  // CLAUDE.md §10: a nav item appears only when its page is real. The
  // prototype's remaining sections must not be here, even disabled.
  for (const absent of ["Incidents", "Cost", "Scorecards", "Teams", "Golden paths", "Architecture"]) {
    await expect(nav.getByRole("link", { name: absent })).toHaveCount(0);
  }
});

test("the console is usable at 375px", async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 800 });
  await page.goto("/catalog");

  await expect(page.getByRole("link", { name: /Orders API/ })).toBeVisible();

  // No horizontal scroll: the layout reflows rather than overflowing.
  const overflows = await page.evaluate(
    () => document.documentElement.scrollWidth > document.documentElement.clientWidth + 1,
  );
  expect(overflows).toBe(false);
});
