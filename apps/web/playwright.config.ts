import { defineConfig, devices } from "@playwright/test";

/**
 * The smoke test drives a real browser against a real console talking to a real
 * control plane and a real PostgreSQL. Nothing is stubbed.
 *
 * CLAUDE.md §11: "You cannot see a browser. Never write 'confirmed it appears in
 * the browser.' Assert through a Playwright smoke test or say it was not
 * verified." This file is the difference between those two.
 *
 * It assumes the whole stack is already running - the identity provider, the
 * control plane and PostgreSQL (make up && make first-user && make dev) - and
 * starts the console itself.
 *
 * Since ADR 0013 the console admits nobody without a session, so a `setup`
 * project signs in once and every other spec reuses that state. Signing in per
 * test would add a form submission and a token exchange to all of them for no
 * extra coverage.
 */
export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  workers: 1,
  reporter: [["list"]],
  timeout: 30_000,
  use: {
    // Overridable so the same specs can be pointed at the containerised console
    // (`make up-app`, port 3000) as well as the one this config starts.
    baseURL: process.env.PLAYWRIGHT_BASE_URL ?? "http://localhost:3100",
    trace: "retain-on-failure",
  },
  projects: [
    { name: "setup", testMatch: /auth\.setup\.ts/ },
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"], storageState: "e2e/.auth/session.json" },
      dependencies: ["setup"],
      // signin.spec.ts is about not being signed in yet, so it runs without the
      // saved state and manages its own.
      testIgnore: /signin\.spec\.ts/,
    },
    {
      name: "chromium-anonymous",
      testMatch: /signin\.spec\.ts/,
      use: { ...devices["Desktop Chrome"] },
    },
  ],
  // Skipped entirely when PLAYWRIGHT_BASE_URL points somewhere else: starting a
  // second console to test a running one would test the wrong process.
  webServer: process.env.PLAYWRIGHT_BASE_URL ? undefined : {
    command: "pnpm build && pnpm start --port 3100",
    url: "http://localhost:3100/catalog",
    reuseExistingServer: false,
    timeout: 180_000,
    env: {
      OPSATLAS_API_URL: process.env.OPSATLAS_API_URL ?? "http://localhost:8080",
      STORM_GATE_URL: process.env.STORM_GATE_URL ?? "http://localhost:8090",
    },
  },
});
