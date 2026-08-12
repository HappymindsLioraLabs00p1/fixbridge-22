import { defineConfig, devices } from "@playwright/test";

/**
 * Browser tests against a running local stack.
 *
 * These exist because every other check in this project is API-level, and the screens were never
 * exercised — which is exactly where the reported failures live. They drive the real UI: typing in
 * real inputs, clicking real buttons, waiting for real responses.
 */
export default defineConfig({
  testDir: "./e2e",
  // The AI round-trip is a real model call behind two services; the default 5s assertion timeout
  // fails on a healthy system and would teach us nothing.
  timeout: 90_000,
  expect: { timeout: 20_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [["list"]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:3000",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    actionTimeout: 20_000,
  },
  projects: [
    // Pixel 5 is Chromium-based; the iPhone profiles need WebKit, which is a separate
    // download and adds nothing here — the app is the same code on both.
    { name: "mobile", use: { ...devices["Pixel 5"] } },
    { name: "desktop", use: { ...devices["Desktop Chrome"] } },
  ],
});
