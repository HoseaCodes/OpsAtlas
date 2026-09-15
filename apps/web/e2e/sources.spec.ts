import { expect, test } from "@playwright/test";
import { asOperator } from "./support/session";

const API = process.env.OPSATLAS_API_URL ?? "http://localhost:8080";

/**
 * The sources page, against the real control plane.
 *
 * These deliberately watch repositories that will fail to sync, because the
 * failing path is the one worth proving in a browser: a control plane whose
 * upstreams are unavailable is the normal case, and the page has to stay
 * readable and say what went wrong.
 */
test.beforeEach(async () => {
  // Start from a known state so counts are assertable.
  const existing = await (await fetch(`${API}/api/v1/sources?limit=100`, { headers: await asOperator() })).json();
  for (const source of existing.items ?? []) {
    await fetch(`${API}/api/v1/sources/${source.id}`, { method: "DELETE", headers: await asOperator() });
  }
});

test("the page states plainly that OpsAtlas only reads", async ({ page }) => {
  await page.goto("/sources");

  // The claim ADR 0008 is built on has to reach the person deciding whether to
  // point this at their repositories.
  await expect(page.getByText(/only ever reads/)).toBeVisible();
  await expect(page.getByText(/no write access is held or needed/)).toBeVisible();
});

test("an empty state explains what to do, and that public repositories need no credentials", async ({ page }) => {
  await page.goto("/sources");

  await expect(page.getByText("No repositories are watched yet.")).toBeVisible();
  await expect(page.getByText(/needs no credentials/)).toBeVisible();
});

test("a malformed repository is refused with the location of the problem", async ({ page }) => {
  await page.goto("/sources");

  await page.getByLabel("Repository").fill("https://evil.example.com/a/b");
  await page.getByRole("button", { name: "Watch" }).click();

  const alert = page.getByRole("main").getByRole("alert");
  await expect(alert).toBeVisible();
  await expect(alert).toContainText("/repository");
});

test("watching a repository lists it as never synced, not as broken", async ({ page }) => {
  await page.goto("/sources");

  await page.getByLabel("Repository").fill("ambitious-concepts/does-not-exist");
  await page.getByRole("button", { name: "Watch" }).click();

  // A brand new source has no information, which is a different thing from
  // having bad news.
  await expect(page.getByText("Never synced")).toBeVisible();
  await expect(page.getByText("ambitious-concepts/does-not-exist", { exact: false })).toBeVisible();
});

test("a failing sync shows the control plane's own explanation", async ({ page }) => {
  await page.goto("/sources");

  await page.getByLabel("Repository").fill("ambitious-concepts/does-not-exist");
  await page.getByRole("button", { name: "Watch" }).click();
  await expect(page.getByText("Never synced")).toBeVisible();

  await page.getByRole("button", { name: "Sync now" }).click();

  // The reader learns what is wrong without leaving the page, and both
  // timestamps are shown so "how current is this" is answerable.
  await expect(page.getByText(/Could not reach GitHub|Manifest not found|Not authorised|Rate limited/)).toBeVisible();
  await expect(page.getByText("Last succeeded")).toBeVisible();
  await expect(page.getByText("Last attempted")).toBeVisible();
});
