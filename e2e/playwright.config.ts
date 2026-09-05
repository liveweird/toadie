import { defineConfig, devices } from "@playwright/test";

// Blackbox E2E: drives a real browser against the full stack (SPA + server + Postgres) served
// single-origin at http://localhost:8081 by `docker compose`. The stack is brought up/down by
// global-setup / global-teardown (unless one is already running locally, which is reused).
// 8081, not 8080 — probing 8080 could happily "reuse" a running Lettuce.
export const BASE_URL = process.env.E2E_BASE_URL ?? "http://localhost:8081";

// Self-signed TLS on a local proof target (an OrbStack ingress with an openssl cert): opt in
// with E2E_INSECURE_TLS=1. This module is evaluated in the runner AND every worker, so setting
// the Node flag here also covers the plain `fetch` calls in global-setup / the Mailpit specs
// without touching them; `ignoreHTTPSErrors` below covers the browser contexts and `page.request`.
const INSECURE_TLS = process.env.E2E_INSECURE_TLS === "1";
if (INSECURE_TLS) process.env.NODE_TLS_REJECT_UNAUTHORIZED = "0";

export default defineConfig({
  testDir: "./tests",
  // The serial unit is the FILE (fullyParallel stays false so a file's tests may be
  // order-dependent). Files run in parallel; every spec file must own its server-side
  // state exclusively — see "Parallel execution" in the README before adding a spec.
  fullyParallel: false,
  workers: Number(process.env.E2E_WORKERS ?? 4),
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  reporter: [["list"], ["html", { open: "never" }]],
  timeout: 60_000,
  expect: { timeout: 10_000 },
  globalSetup: "./global-setup.ts",
  globalTeardown: "./global-teardown.ts",
  use: {
    baseURL: BASE_URL,
    ignoreHTTPSErrors: INSECURE_TLS,
    // retain-on-failure, not on-first-retry: retries are 0 locally, so on-first-retry never
    // fires and local failures would produce no trace at all. A trace beats the video it
    // replaces (DOM snapshots, network, console) and costs nothing on a passing run.
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: "off",
  },
  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
    },
  ],
});
