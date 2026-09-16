import { expect, test } from "@playwright/test";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { asOperator } from "./support/session";

const EXAMPLES = join(process.cwd(), "..", "..", "examples", "services");
const API = process.env.OPSATLAS_API_URL ?? "http://localhost:8080";

/**
 * What version is running where.
 *
 * Phase 13. The properties worth driving in a browser are the honesty ones: an
 * environment nothing has reported must read as "not reported" rather than as a
 * dash that looks like a measurement, and two environments on different versions
 * must read as an in-flight promotion rather than as drift - which this system
 * still cannot detect and must not imply (ADR 0017).
 */

async function ensure(name: string) {
  const body = readFileSync(join(EXAMPLES, name), "utf8");
  const created = await fetch(`${API}/api/v1/services`, {
    method: "POST",
    headers: await asOperator({ "Content-Type": "application/yaml" }),
    body,
  });
  if (created.ok || created.status === 409) return;
  throw new Error(`Setup failed to register ${name}: ${created.status} ${await created.text()}`);
}

async function report(slug: string, environment: string, deployment: Record<string, string>) {
  const response = await fetch(
    `${API}/api/v1/services/${slug}/environments/${environment}/deployments`,
    {
      method: "POST",
      headers: await asOperator({ "Content-Type": "application/json" }),
      body: JSON.stringify(deployment),
    },
  );
  if (!response.ok) {
    throw new Error(`Setup failed to report a deployment: ${response.status} ${await response.text()}`);
  }
}

test.beforeAll(async () => {
  await ensure("orders-api.yaml");
  await ensure("legacy-report-runner.yaml");

  // production behind staging, which is the promotion case the prototype shows.
  await report("orders-api", "production", {
    version: "1.4.2",
    commitSha: "9ab3f07",
    deployedBy: "m.velez",
    idempotencyKey: "e2e-prod-1.4.2",
  });
  await report("orders-api", "staging", {
    version: "1.5.0-rc1",
    commitSha: "c70d219",
    deployedBy: "m.velez",
    idempotencyKey: "e2e-staging-1.5.0-rc1",
  });
});

test("the environments table shows the version, commit and how long it has been there", async ({ page }) => {
  await page.goto("/catalog/orders-api");

  const environments = page.getByRole("table").filter({ hasText: "In place for" }).first();
  await expect(environments.getByText("1.4.2", { exact: true })).toBeVisible();
  await expect(environments.getByText("9ab3f07", { exact: true })).toBeVisible();
  await expect(environments.getByText("1.5.0-rc1", { exact: true })).toBeVisible();
  await expect(environments.getByText("c70d219", { exact: true })).toBeVisible();
});

test("the last deploy says when, where and by whom", async ({ page }) => {
  await page.goto("/catalog/orders-api");

  await expect(page.getByRole("heading", { name: "Deployments" })).toBeVisible();
  await expect(page.getByText(/Last deploy/)).toBeVisible();
  await expect(page.getByText("m.velez", { exact: true }).first()).toBeVisible();
});

test("environments on different versions read as a promotion, never as drift", async ({ page }) => {
  // The distinction matters: OpsAtlas cannot detect drift at all, because that
  // needs an observed version and nothing exposes one. Calling this drift would
  // claim a capability that does not exist.
  await page.goto("/catalog/orders-api");

  await expect(page.getByText(/normal for an in-flight change/i)).toBeVisible();
  await expect(page.getByText(/it is not drift/)).toBeVisible();
});

test("the page never claims to have verified a reported version", async ({ page }) => {
  await page.goto("/catalog/orders-api");
  await expect(page.getByText(/reported by whoever deployed/)).toBeVisible();
});

test("a service with nothing reported says so rather than showing a dash", async ({ page }) => {
  // "Not reported" and "not deployed" are different facts. legacy-report-runner
  // has had no deployment reported, and the page must not imply the second.
  await page.goto("/catalog/legacy-report-runner");

  await expect(page.getByText("not reported").first()).toBeVisible();
  await expect(page.getByText(/means nothing told OpsAtlas/)).toBeVisible();
});

test("a replayed report does not become a second deployment", async ({ page }) => {
  // Driven through the API rather than the UI because there is no deploy button
  // and should not be: OpsAtlas records deploys, it does not perform them.
  const before = await fetch(`${API}/api/v1/services/orders-api/deployments`, {
    headers: await asOperator(),
  }).then((r) => r.json());

  await report("orders-api", "production", {
    version: "1.4.2",
    commitSha: "9ab3f07",
    deployedBy: "m.velez",
    idempotencyKey: "e2e-prod-1.4.2",
  });

  const after = await fetch(`${API}/api/v1/services/orders-api/deployments`, {
    headers: await asOperator(),
  }).then((r) => r.json());

  expect(after.history.length).toBe(before.history.length);
  await page.goto("/catalog/orders-api");
  await expect(page.getByRole("heading", { name: "Deployments" })).toBeVisible();
});
