import { expect, test } from "@playwright/test";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { asOperator } from "./support/session";

const EXAMPLES = join(process.cwd(), "..", "..", "examples", "services");
const API = process.env.OPSATLAS_API_URL ?? "http://localhost:8080";

/**
 * The dashboard link, in a real browser.
 *
 * `spec.observability.dashboard` was in the schema from the start, validated,
 * parsed, and then dropped: no column, no rule, nothing rendered. Declaring one
 * was accepted and silently did nothing, which is the sort of thing CLAUDE.md
 * §10 rules out — a capability implied and not delivered.
 *
 * `ServiceDetail` already carried the whole manifest, so the fix was rendering
 * rather than plumbing. These tests are what stops it being dropped again.
 */

/** Registers a manifest, or updates it in place if a prior run left a different one. */
async function ensure(name: string) {
  const body = readFileSync(join(EXAMPLES, name), "utf8");
  const created = await fetch(`${API}/api/v1/services`, {
    method: "POST",
    headers: await asOperator({ "Content-Type": "application/yaml" }),
    body,
  });
  if (created.ok) return;
  if (created.status !== 409) {
    throw new Error(`Setup failed to register ${name}: ${created.status} ${await created.text()}`);
  }
  const slug = name.replace(/\.ya?ml$/, "");
  const current = await fetch(`${API}/api/v1/services/${slug}`, { headers: await asOperator() });
  if (!current.ok) throw new Error(`Setup could not read ${slug}: ${current.status}`);
  const etag = current.headers.get("etag");
  if (!etag) throw new Error(`Setup got no ETag for ${slug}, so it cannot send If-Match`);
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
  await ensure("orders-api.yaml");
  await ensure("billing-worker.yaml");
});

test("a declared dashboard is a link a person can follow", async ({ page }) => {
  await page.goto("/catalog/orders-api");

  const link = page.getByRole("link", { name: "https://grafana.example.com/d/orders-api/orders-api" });
  await expect(link).toBeVisible();
  await expect(link).toHaveAttribute("href", "https://grafana.example.com/d/orders-api/orders-api");

  // A manifest is another repository's document: the opened page must not be
  // able to reach back into this window, and must not learn where we came from.
  const rel = (await link.getAttribute("rel")) ?? "";
  expect(rel).toContain("noopener");
  expect(rel).toContain("noreferrer");
});

test("the telemetry name is shown beside it", async ({ page }) => {
  await page.goto("/catalog/orders-api");
  const observability = page.getByRole("heading", { name: "Observability" });
  await expect(observability).toBeVisible();
  await expect(page.getByText("orders-api", { exact: true }).first()).toBeVisible();
});

test("an undeclared dashboard says so rather than rendering an empty row", async ({ page }) => {
  // billing-worker declares no dashboard. §10: absence is shown as absence, and
  // is never defaulted into something that reads like a reading.
  await page.goto("/catalog/billing-worker");
  await expect(page.getByRole("heading", { name: "Observability" })).toBeVisible();
  await expect(page.getByText(/none declared/).first()).toBeVisible();
  await expect(
    page.getByRole("link", { name: /grafana\.example\.com/ }),
  ).toHaveCount(0);
});

test("the page refuses to claim it verified any of it", async ({ page }) => {
  // The scorecard scores declarations, and so does this section. Nothing here
  // checks that the dashboard resolves or that telemetry arrives.
  await page.goto("/catalog/orders-api");
  await expect(page.getByText(/not something OpsAtlas verified/)).toBeVisible();
});
