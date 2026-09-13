import { expect, test } from "@playwright/test";

const API = process.env.OPSATLAS_API_URL ?? "http://localhost:8080";

/**
 * Health, end to end: a manifest is registered, observations are reported as an
 * observer would report them, and the console renders what the control plane
 * made of them.
 *
 * The observations are posted directly rather than by running the Go binary,
 * because what this file is about is the console. That the observer produces
 * these is covered by its own tests and by a live run.
 */
const MANIFEST = `apiVersion: opsatlas.ambitiousconcepts.io/v1
kind: Service
metadata:
  name: probed-api
  displayName: Probed API
  owner: ambitious-concepts
  repository: ambitious-concepts/probed-api
spec:
  tier: 1
  runtime: spring-boot
  environments:
    - name: production
      url: https://probed.example.com
    - name: staging
      url: https://probed.staging.example.com
  health:
    readiness: /readyz
    liveness: /healthz
  observability:
    serviceName: probed-api
  operations:
    slo: {availability: 99.9, window: 30d}
    runbook: docs/runbook.md
  journeys: [Place an order]
  dependencies: []
`;

let productionId = "";
let stagingId = "";

test.beforeAll(async () => {
  const registered = await fetch(`${API}/api/v1/services`, {
    method: "POST",
    headers: { "Content-Type": "application/yaml" },
    body: MANIFEST,
  });
  if (registered.status !== 201 && registered.status !== 200) {
    throw new Error(`setup failed to register: ${registered.status} ${await registered.text()}`);
  }

  const detail = await (await fetch(`${API}/api/v1/services/probed-api`)).json();
  for (const environment of detail.environments) {
    if (environment.name === "production") productionId = environment.id;
    if (environment.name === "staging") stagingId = environment.id;
  }

  // Production healthy, staging down. Three consecutive failures is what the
  // control plane treats as DOWN rather than a blip.
  const now = Date.now();
  const observations = [
    ...[0, 1, 2].map((i) => ({
      environmentId: productionId,
      observedAt: new Date(now - (3 - i) * 30_000).toISOString(),
      outcome: "HEALTHY",
      responseMs: 20 + i,
      statusCode: 200,
      detail: null,
    })),
    ...[0, 1, 2].map((i) => ({
      environmentId: stagingId,
      observedAt: new Date(now - (3 - i) * 30_000).toISOString(),
      outcome: "UNREACHABLE",
      responseMs: null,
      statusCode: null,
      detail: "Nothing answered at the probe address: connection refused.",
    })),
  ];

  const reported = await fetch(`${API}/api/v1/observations`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      idempotencyKey: `e2e-health-${now}`,
      observerId: "e2e",
      observations,
    }),
  });
  if (reported.status !== 202) {
    throw new Error(`setup failed to report: ${reported.status} ${await reported.text()}`);
  }
});

test("the catalog shows the worst environment's health, not an average", async ({ page }) => {
  await page.goto("/catalog");

  const row = page.getByRole("link", { name: /Probed API/ });
  await expect(row).toBeVisible();
  // Production is healthy and staging is down. The service is down: averaging
  // would call it mostly fine, which is true of the environments and false of
  // the service.
  await expect(row.getByText("Down")).toBeVisible();
});

test("the detail page shows per-environment health and why the failing one failed", async ({ page }) => {
  await page.goto("/catalog/probed-api");

  await expect(page.getByRole("cell", { name: "production" })).toBeVisible();
  await expect(page.getByText("Healthy").first()).toBeVisible();
  await expect(page.getByText("Down").first()).toBeVisible();

  // Probe availability, stated as a percentage and labelled as probes rather
  // than as an SLO. Exact, because "0.00%" is a substring of "100.00%" and a
  // loose match would pass while showing only one of them.
  await expect(page.getByRole("cell", { name: "100.00%", exact: true })).toBeVisible();
  await expect(page.getByRole("cell", { name: "0.00%", exact: true })).toBeVisible();
});

test("the page refuses to call probe availability an SLO", async ({ page }) => {
  await page.goto("/catalog/probed-api");

  // The claim the whole measurement rests on: this is the share of probes that
  // succeeded from one vantage point, and a service can serve errors to every
  // real user while its readiness endpoint answers happily.
  await expect(page.getByText(/not an SLO/i)).toBeVisible();
});

test("the 30-day ribbon distinguishes an un-probed day from a bad one", async ({ page }) => {
  await page.goto("/catalog/probed-api");

  const ribbon = page.locator(".ribbon").first();
  await expect(ribbon).toBeVisible();

  // Today has probes; the preceding days have none. They must render
  // differently, or a newly watched service looks broken for a month.
  const values = await ribbon.locator("i").evaluateAll((bars) =>
    bars.map((bar) => bar.getAttribute("data-value")),
  );
  expect(values).toContain("none");
  expect(values.filter((value) => value !== "none").length).toBeGreaterThan(0);
});

test("a service that has never been probed says so rather than looking healthy", async ({ page }) => {
  // legacy-report-runner is registered by the catalog spec and never observed.
  await fetch(`${API}/api/v1/services`, {
    method: "POST",
    headers: { "Content-Type": "application/yaml" },
    body: `apiVersion: opsatlas.ambitiousconcepts.io/v1
kind: Service
metadata:
  name: never-probed
  repository: ambitious-concepts/never-probed
spec:
  tier: 3
  environments:
    - name: production
`,
  });

  await page.goto("/catalog/never-probed");
  // The meter says so, and so does the availability cell - a blank cell would
  // read as a rendering bug rather than as a measurement nobody has taken.
  await expect(page.getByText("Never observed").first()).toBeVisible();
  await expect(page.getByRole("cell", { name: "not probed", exact: true })).toBeVisible();
  await expect(page.getByRole("cell", { name: "never", exact: true })).toBeVisible();
});
