import { defineConfig, devices } from "@playwright/test";

/**
 * The smoke test drives a real browser against a real console talking to a real
 * control plane and a real PostgreSQL. Nothing is stubbed.
 *
 * CLAUDE.md §11: "You cannot see a browser. Never write 'confirmed it appears in
 * the browser.' Assert through a Playwright smoke test or say it was not
 * verified." This file is the difference between those two.
 *
 * It assumes the control plane is already running (make up && make dev) and
 * starts the console itself.
 */
export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  workers: 1,
  reporter: [["list"]],
  timeout: 30_000,
  use: {
    baseURL: "http://localhost:3100",
    trace: "retain-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: {
    command: "pnpm build && pnpm start --port 3100",
    url: "http://localhost:3100/catalog",
    reuseExistingServer: false,
    timeout: 180_000,
    env: { OPSATLAS_API_URL: process.env.OPSATLAS_API_URL ?? "http://localhost:8080" },
  },
});
