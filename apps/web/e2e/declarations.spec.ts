import { expect, test } from "@playwright/test";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { asOperator } from "./support/session";

const EXAMPLES = join(process.cwd(), "..", "..", "examples", "services");
const API = process.env.OPSATLAS_API_URL ?? "http://localhost:8080";

/**
 * The five manifest fields that were validated, stored and rendered nowhere.
 *
 * `spec.operations.runbook`, `spec.operations.slo`, `spec.dependencies` and
 * `spec.journeys` moved a scorecard check and nothing else.
 * `spec.operations.contact` moved nothing at all: a team could declare a chat
 * channel and no part of this system would ever mention it again.
 *
 * This is the same failure `observability.spec.ts` was written for, one field
 * later. These tests exist so the third time is caught by the build.
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
  // orders-api declares all five. customer-portal is the only fixture whose
  // runbook is an https URL rather than a repository path. legacy-report-runner
  // declares none of them, which is what makes the absence assertions mean
  // something. docs-portal is the only one that states an empty dependency
  // list, which is a different answer from declaring none.
  await ensure("orders-api.yaml");
  await ensure("customer-portal.yaml");
  await ensure("legacy-report-runner.yaml");
  await ensure("docs-portal.yaml");
});

test("a declared contact is shown", async ({ page }) => {
  // The field that reached no reader at all before this. If it stops being
  // rendered, this is the test that says so.
  await page.goto("/catalog/orders-api");
  await expect(page.getByRole("heading", { name: "Operations", exact: true })).toBeVisible();
  await expect(page.getByText("#orders-eng", { exact: true })).toBeVisible();
});

test("a repository-relative runbook is shown as a path, not invented into a link", async ({ page }) => {
  await page.goto("/catalog/orders-api");

  await expect(page.getByText("docs/runbook.md", { exact: true })).toBeVisible();
  // metadata.repository is owner/name with no host. A link here would mean
  // guessing github.com, which the manifest never said.
  await expect(page.getByRole("link", { name: /runbook\.md/ })).toHaveCount(0);
  await expect(page.getByText(/in ambitious-concepts\/orders-api/)).toBeVisible();
});

test("an https runbook is a link a person can follow", async ({ page }) => {
  await page.goto("/catalog/customer-portal");

  const link = page.getByRole("link", { name: "https://example.com/runbooks/customer-portal" });
  await expect(link).toBeVisible();
  await expect(link).toHaveAttribute("href", "https://example.com/runbooks/customer-portal");

  // Somebody else's document decided this URL, so the opened page must not
  // reach back into this window or learn where the reader came from.
  const rel = (await link.getAttribute("rel")) ?? "";
  expect(rel).toContain("noopener");
  expect(rel).toContain("noreferrer");
});

test("a declared SLO target is shown, and is not claimed to be measured", async ({ page }) => {
  await page.goto("/catalog/orders-api");

  await expect(page.getByText("99.9% over 30d")).toBeVisible();
  // The ribbon on the same page is probe availability from one vantage point.
  // Putting a target next to it without saying that invites exactly the reading
  // CLAUDE.md forbids.
  await expect(page.getByText(/Nothing here measures against the target/)).toBeVisible();
});

test("declared dependencies are listed with their kinds", async ({ page }) => {
  await page.goto("/catalog/orders-api");

  await expect(page.getByRole("heading", { name: "Depends on" })).toBeVisible();
  const list = page.getByRole("list").filter({ hasText: "pricing-engine" }).first();
  await expect(list.getByRole("listitem")).toHaveCount(3);
  await expect(list.getByText("pricing-engine", { exact: true })).toBeVisible();
  await expect(list.getByText("(datastore)")).toBeVisible();
  await expect(list.getByText("(external)")).toBeVisible();
});

test("the dependency list does not claim to be a blast radius", async ({ page }) => {
  // Forward edges only. Nothing computes which registered services call this
  // one, and the prototype's "2 registered services call this one" would be a
  // capability that does not exist.
  await page.goto("/catalog/orders-api");
  await expect(page.getByText(/this is not a blast radius/)).toBeVisible();
});

test("declared journeys are listed", async ({ page }) => {
  await page.goto("/catalog/orders-api");
  await expect(page.getByRole("heading", { name: "Journeys" })).toBeVisible();
  await expect(page.getByText("Place an order", { exact: true })).toBeVisible();
  await expect(page.getByText("Track an order", { exact: true })).toBeVisible();
});

test("an absent dependency key reads as unanswered, not as nothing", async ({ page }) => {
  // The distinction the schema exists to keep. "Not stated" is a question
  // nobody answered; it must not render the same as "this calls nothing".
  await page.goto("/catalog/legacy-report-runner");
  await expect(page.getByRole("heading", { name: "Depends on" })).toBeVisible();
  await expect(page.getByText(/Not stated/)).toBeVisible();
  await expect(page.getByText(/a question nobody answered/)).toBeVisible();
});

test("every undeclared field says so rather than rendering an empty row", async ({ page }) => {
  // legacy-report-runner declares no runbook, contact, SLO or journeys. §10:
  // absence is shown as absence and is never defaulted into a reading.
  await page.goto("/catalog/legacy-report-runner");

  for (const field of [
    "spec.operations.runbook",
    "spec.operations.contact",
    "spec.operations.slo",
    "spec.journeys",
  ]) {
    await expect(page.getByText(field, { exact: true })).toBeVisible();
  }
});

test("an empty dependency list reads as an answer, not as an absence", async ({ page }) => {
  // The other half of the distinction above, and the reason docs-portal exists.
  // Until it did, this branch was covered by a unit test and by nothing a
  // browser ever rendered.
  await page.goto("/catalog/docs-portal");

  await expect(page.getByRole("heading", { name: "Depends on" })).toBeVisible();
  await expect(page.getByText("Stated: this service calls nothing.")).toBeVisible();
  await expect(page.getByText(/a question nobody answered/)).toHaveCount(0);
});

test("a service declaring everything has no 'none declared' anywhere on it", async ({ page }) => {
  // docs-portal declares every field the overview renders. If any of them stops
  // being read, this page starts admitting an absence it does not have.
  await page.goto("/catalog/docs-portal");

  await expect(page.getByText("#docs-eng", { exact: true })).toBeVisible();
  await expect(page.getByText("99.5% over 30d")).toBeVisible();
  await expect(page.getByText("Read the documentation", { exact: true })).toBeVisible();
  await expect(
    page.getByRole("link", { name: "https://example.com/runbooks/docs-portal" }),
  ).toBeVisible();
  await expect(
    page.getByRole("link", { name: "https://grafana.example.com/d/docs-portal/docs-portal" }),
  ).toBeVisible();

  await expect(page.getByText(/none declared/)).toHaveCount(0);
});

test("the tier is in the ownership block, not only in the header", async ({ page }) => {
  await page.goto("/catalog/orders-api");
  const ownership = page.getByRole("heading", { name: "Ownership and source" });
  await expect(ownership).toBeVisible();
  // The em dash scopes this to the Ownership row; the header above the tabs
  // prints the same label in parentheses.
  await expect(page.getByText("— customer-facing, paged")).toBeVisible();
});

test("the declared health check paths are shown", async ({ page }) => {
  // readinessPath and livenessPath were in the API response and rendered
  // nowhere, which is the same failure as the manifest fields above.
  await page.goto("/catalog/orders-api");

  await expect(page.getByRole("heading", { name: "Health checks" })).toBeVisible();
  await expect(page.getByText("/actuator/health/readiness", { exact: true })).toBeVisible();
  await expect(page.getByText("/actuator/health/liveness", { exact: true })).toBeVisible();
});

test("the health section refuses to invent an interval or a replica count", async ({ page }) => {
  // The prototype showed "10s / 2s · 3 consecutive failures to evict" and
  // "Passing 9 of 14". The first is the observer's own configuration and the
  // second needs an orchestrator. Neither is this service's declaration.
  await page.goto("/catalog/orders-api");
  await expect(page.getByText(/cannot see how many replicas answered/)).toBeVisible();
});

test("a declared rotation is a followable link, and coverage is spelled out", async ({ page }) => {
  await page.goto("/catalog/orders-api");

  const link = page.getByRole("link", { name: "https://pagerduty.example.com/schedules/PORDERS" });
  await expect(link).toBeVisible();
  await expect(page.getByText("24x7 — paged around the clock")).toBeVisible();
  await expect(page.getByText("platform-leads", { exact: true })).toBeVisible();
});

test("the page never claims to know who is on call", async ({ page }) => {
  // The prototype's "On call now — t.nguyen". OpsAtlas has no paging provider
  // to ask, and a stale name is worse than no name (ADR 0016).
  await page.goto("/catalog/orders-api");
  await expect(page.getByText(/not a claim about who is on call now/)).toBeVisible();
  await expect(page.getByText(/On call now/)).toHaveCount(0);
});

test("the operations links footer lists only links that go somewhere", async ({ page }) => {
  await page.goto("/catalog/orders-api");

  const footer = page.getByRole("heading", { name: "Operations links" });
  await expect(footer).toBeVisible();

  const list = page.getByRole("list").filter({ hasText: "Grafana dashboard" }).first();
  await expect(list.getByRole("link", { name: "Grafana dashboard" })).toBeVisible();
  await expect(list.getByRole("link", { name: "On-call rotation" })).toBeVisible();

  // orders-api's runbook is a repository path, so it cannot become a link and
  // is deliberately not in this list — it is shown as a path further up.
  await expect(list.getByRole("link", { name: "Runbook" })).toHaveCount(0);

  // Never rendered, because there is nothing behind them.
  for (const dead of ["Traces", "Logs", "API spec"]) {
    await expect(list.getByRole("link", { name: dead })).toHaveCount(0);
  }
});

test("a service with an https runbook gets it in the links footer too", async ({ page }) => {
  await page.goto("/catalog/customer-portal");
  const list = page.getByRole("list").filter({ hasText: "Runbook" }).first();
  await expect(list.getByRole("link", { name: "Runbook" })).toBeVisible();
});

test("a service declaring no rotation says so rather than leaving the row blank", async ({ page }) => {
  await page.goto("/catalog/legacy-report-runner");
  await expect(page.getByText("spec.operations.oncall.rotation", { exact: true })).toBeVisible();
});
