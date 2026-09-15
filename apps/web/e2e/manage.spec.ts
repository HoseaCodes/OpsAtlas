import { expect, test } from "@playwright/test";
import { asOperator } from "./support/session";

const API = process.env.OPSATLAS_API_URL ?? "http://localhost:8080";
const SLUG = "manage-probe";

/**
 * Editing and deleting a service from the console.
 *
 * Both were reachable by `curl` and not by a person: `PUT` existed with no UI,
 * and `DELETE` did not exist at all. CLAUDE.md §10 forbids a button that does
 * nothing; the inverse — an endpoint with no button — is how a catalog ends up
 * with entries nobody can correct.
 *
 * Each test works on its own throwaway service and removes it afterwards, so
 * the suite leaves the catalog as it found it.
 */

function manifest(lifecycle = "active"): string {
  return `apiVersion: opsatlas.ambitiousconcepts.io/v1
kind: Service
metadata:
  name: ${SLUG}
  displayName: Throwaway Probe
  owner: platform
  repository: ambitious-concepts/manage-probe
spec:
  tier: 3
  lifecycle: ${lifecycle}
  runtime: node
  environments:
    - name: production
      url: https://manage-probe.example.com
  health:
    readiness: /health
`;
}

async function removeIfPresent() {
  const current = await fetch(`${API}/api/v1/services/${SLUG}`, { headers: await asOperator() });
  if (current.status === 404) return;
  const etag = current.headers.get("etag");
  if (!etag) throw new Error(`No ETag for ${SLUG}, so cleanup cannot send If-Match`);
  await fetch(`${API}/api/v1/services/${SLUG}`, {
    method: "DELETE",
    headers: await asOperator({ "If-Match": etag }),
  });
}

test.beforeEach(async () => {
  await removeIfPresent();
  const created = await fetch(`${API}/api/v1/services?sourcePath=service.yaml`, {
    method: "POST",
    headers: await asOperator({ "Content-Type": "application/yaml" }),
    body: manifest(),
  });
  if (!created.ok) {
    throw new Error(`Setup could not register ${SLUG}: ${created.status} ${await created.text()}`);
  }
});

test.afterEach(async () => {
  await removeIfPresent();
});

test("the lifecycle is shown, so retiring is visible rather than buried in the manifest", async ({
  page,
}) => {
  await page.goto(`/catalog/${SLUG}`);
  await expect(page.getByRole("heading", { name: "Manage", exact: true })).toBeVisible();
  await expect(page.getByRole("definition").filter({ hasText: /^active$/ })).toBeVisible();
});

test("editing the manifest retires the service, keeping the entry", async ({ page }) => {
  await page.goto(`/catalog/${SLUG}`);

  await page.getByRole("button", { name: "Edit manifest" }).click();
  const editor = page.getByLabel("Replacement manifest — YAML or JSON");
  await expect(editor).toBeVisible();

  // Retiring is the recommended alternative to deleting, and it happens through
  // this same edit: the entry and its history stay.
  await editor.fill(manifest("retired"));

  // Wait for the response rather than for the DOM to settle: if the save is
  // refused, this test should fail saying why, not time out on a locator.
  const [response] = await Promise.all([
    page.waitForResponse(
      (candidate) => candidate.url().includes(`/api/services/${SLUG}`) && candidate.request().method() === "PUT",
    ),
    page.getByRole("button", { name: "Save manifest" }).click(),
  ]);
  expect(
    response.status(),
    `the console refused the edit: ${await response.text().catch(() => "no body")}`,
  ).toBe(200);

  // Reloaded rather than trusting the client router's cache, so this asserts
  // what was stored rather than what one component happened to re-render.
  await page.reload();
  await expect(page.getByRole("definition").filter({ hasText: /^retired$/ })).toBeVisible({
    timeout: 15_000,
  });

  // Still in the catalog. Retiring is not removal.
  await page.goto("/catalog");
  await expect(page.getByRole("link", { name: /Throwaway Probe/ })).toBeVisible();
});

test("an invalid edit is refused with the control plane's own violations", async ({ page }) => {
  await page.goto(`/catalog/${SLUG}`);
  await page.getByRole("button", { name: "Edit manifest" }).click();

  await page.getByLabel("Replacement manifest — YAML or JSON").fill(
    manifest().replace("tier: 3", "tier: 99"),
  );
  await page.getByRole("button", { name: "Save manifest" }).click();

  const alert = page.getByRole("alert");
  await expect(alert).toBeVisible({ timeout: 15_000 });
  // The pointer comes from the control plane, not from a paraphrase here.
  await expect(alert.getByText("/spec/tier")).toBeVisible();
});

test("deleting needs the slug typed, and the button is inert until it is", async ({ page }) => {
  await page.goto(`/catalog/${SLUG}`);
  await page.getByRole("button", { name: "Delete", exact: true }).click();

  const confirm = page.getByRole("button", { name: "Delete this service" });
  await expect(confirm).toBeDisabled();

  await page.getByLabel(`Type ${SLUG} to confirm`).fill("not-the-slug");
  await expect(confirm).toBeDisabled();

  await page.getByLabel(`Type ${SLUG} to confirm`).fill(SLUG);
  await expect(confirm).toBeEnabled();
});

test("deleting removes the service and returns to the catalog", async ({ page }) => {
  await page.goto(`/catalog/${SLUG}`);
  await page.getByRole("button", { name: "Delete", exact: true }).click();
  await page.getByLabel(`Type ${SLUG} to confirm`).fill(SLUG);
  await page.getByRole("button", { name: "Delete this service" }).click();

  await expect(page).toHaveURL(/\/catalog$/, { timeout: 15_000 });
  await expect(page.getByRole("link", { name: /Throwaway Probe/ })).toHaveCount(0);

  // And it is really gone from the API, not merely absent from one page.
  const after = await fetch(`${API}/api/v1/services/${SLUG}`, { headers: await asOperator() });
  expect(after.status).toBe(404);
});

test("the audit log outlives the service it describes", async ({ page }) => {
  await page.goto(`/catalog/${SLUG}`);
  await page.getByRole("button", { name: "Delete", exact: true }).click();
  await page.getByLabel(`Type ${SLUG} to confirm`).fill(SLUG);
  await page.getByRole("button", { name: "Delete this service" }).click();
  await expect(page).toHaveURL(/\/catalog$/, { timeout: 15_000 });

  // This is what makes a hard delete acceptable: audit_event declares no
  // foreign key to service, so what happened stays answerable afterwards.
  const events = await fetch(`${API}/api/v1/audit-events?limit=50`, { headers: await asOperator() });
  expect(events.ok).toBe(true);
  const body = (await events.json()) as { items: { action: string }[] };
  expect(body.items.some((event) => event.action === "service.deleted")).toBe(true);
});
