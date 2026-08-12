import { test, expect, Page } from "@playwright/test";

/**
 * The journeys a real person takes, driven through the real UI.
 *
 * Every other test in this project asserts against the API. These assert what someone actually
 * sees — which is where the reported failures are, and the one layer nothing has ever covered.
 *
 * Each test registers its own account rather than sharing a fixture: a shared account accumulates
 * jobs and conversations, and a test that passes only because of state left by an earlier one is
 * worse than no test.
 */

const PASSWORD = "DevTest!2026x";

function uniqueEmail(prefix: string) {
  return `${prefix}${Date.now()}${Math.floor(Math.random() * 1000)}@demo.local`;
}

/** Registers through the real form and lands wherever the app sends a new customer. */
async function registerCustomer(page: Page, prefix = "e2e") {
  const email = uniqueEmail(prefix);
  await page.goto("/register");
  // Wait for React to take over. Before that the submit button is a plain HTML control and a
  // click performs a native GET, which is what a real impatient user hits on a slow phone.
  await expect(page.getByRole("button", { name: /create account/i })).toBeEnabled({ timeout: 30_000 });
  await page.getByLabel(/full name|name/i).first().fill("E2E Tester");
  await page.getByLabel(/email/i).first().fill(email);
  await page.getByLabel(/password/i).first().fill(PASSWORD);
  await page.getByRole("button", { name: /create|sign up|register/i }).first().click();
  // Signup logs the user straight in, so leaving /register is the success signal.
  await expect(page).not.toHaveURL(/\/register/, { timeout: 45_000 });
  return email;
}

test.describe("Signing up and in", () => {
  test("a new account can be created and lands signed in", async ({ page }) => {
    await registerCustomer(page, "signup");
    // Something that only appears to an authenticated user.
    await expect(
      page.getByRole("link", { name: /account|dashboard|sign out/i }).first()
        .or(page.getByText(/describe your problem|what needs fixing/i).first()),
    ).toBeVisible();
  });

  test("an existing account can sign in again", async ({ page }) => {
    const email = await registerCustomer(page, "relogin");
    await page.goto("/login");
    await expect(page.getByRole("button", { name: /sign in/i }).last()).toBeEnabled({ timeout: 30_000 });
    await page.getByLabel(/email/i).first().fill(email);
    await page.getByLabel(/password/i).first().fill(PASSWORD);
    await page.getByRole("button", { name: /sign in/i }).first().click();
    await expect(page).not.toHaveURL(/\/login/, { timeout: 45_000 });
  });

  test("a wrong password is refused with a visible message", async ({ page }) => {
    const email = await registerCustomer(page, "badpw");
    await page.goto("/login");
    await expect(page.getByRole("button", { name: /sign in/i }).last()).toBeEnabled({ timeout: 30_000 });
    await page.getByLabel(/email/i).first().fill(email);
    await page.getByLabel(/password/i).first().fill("definitely-not-the-password");
    await page.getByRole("button", { name: /sign in/i }).first().click();
    // The failure must be shown, not swallowed — a silent no-op is the worst version of this.
    await expect(page.getByText(/invalid|incorrect|wrong|failed|try again/i).first()).toBeVisible();
    await expect(page).toHaveURL(/\/login/);
  });

  test("registering the same email twice is refused rather than hanging", async ({ page }) => {
    const email = await registerCustomer(page, "dupe");
    await page.goto("/register");
    await page.getByLabel(/full name|name/i).first().fill("Duplicate");
    await page.getByLabel(/email/i).first().fill(email);
    await page.getByLabel(/password/i).first().fill(PASSWORD);
    await page.getByRole("button", { name: /create|sign up|register/i }).first().click();
    await expect(
      page.getByText(/already|exists|taken|in use/i).first(),
    ).toBeVisible({ timeout: 45_000 });
  });
});

test.describe("The AI assistant", () => {
  test("a safe problem produces a repair plan", async ({ page }) => {
    await registerCustomer(page, "aisafe");
    await page.goto("/customer/assistant");
    const box = page.getByPlaceholder(/describe|problem/i).first();
    await expect(box).toBeVisible({ timeout: 45_000 });
    // Enabled only once React owns the composer — before that, typing goes nowhere and Send is
    // inert, which is exactly the "I typed and nothing happened" failure.
    await expect(box).toBeEnabled({ timeout: 30_000 });
    await box.fill("the cabinet door hinge is loose and rattling, screws backing out, three days");
    await page.getByRole("button", { name: /^send$/i }).first().click();
    await expect(page.getByText(/steps|guide|talk you through/i).first())
      .toBeVisible({ timeout: 75_000 });
  });

  test("a gas report refuses to give any repair steps", async ({ page }) => {
    await registerCustomer(page, "aigas");
    await page.goto("/customer/assistant");
    const box = page.getByPlaceholder(/describe|problem/i).first();
    await expect(box).toBeVisible({ timeout: 45_000 });
    // Enabled only once React owns the composer — before that, typing goes nowhere and Send is
    // inert, which is exactly the "I typed and nothing happened" failure.
    await expect(box).toBeEnabled({ timeout: 30_000 });
    await box.fill("I can smell gas near the boiler in my kitchen");
    await page.getByRole("button", { name: /^send$/i }).first().click();

    // The escalation banner must appear...
    await expect(page.getByText(/stop|immediate|professional|emergency/i).first())
      .toBeVisible({ timeout: 75_000 });
    // ...and no repair plan may be offered. This is the safety guarantee, at the UI layer.
    await expect(page.getByText(/your repair plan/i)).toHaveCount(0);
  });

  test("the composer is hidden once the problem is an emergency", async ({ page }) => {
    await registerCustomer(page, "aicomposer");
    await page.goto("/customer/assistant");
    const box = page.getByPlaceholder(/describe|problem/i).first();
    await expect(box).toBeVisible({ timeout: 45_000 });
    // Enabled only once React owns the composer — before that, typing goes nowhere and Send is
    // inert, which is exactly the "I typed and nothing happened" failure.
    await expect(box).toBeEnabled({ timeout: 30_000 });
    await box.fill("I can smell gas near the boiler");
    await page.getByRole("button", { name: /^send$/i }).first().click();
    await expect(page.getByText(/stop|immediate|professional/i).first())
      .toBeVisible({ timeout: 75_000 });
    // Continuing to chat after an emergency verdict invites exactly the wrong behaviour.
    await expect(page.getByRole("button", { name: /^send$/i })).toHaveCount(0);
  });

  test("only one conversation is opened on load", async ({ page }) => {
    await registerCustomer(page, "aionce");
    let started = 0;
    page.on("request", (r) => {
      if (r.method() === "POST" && /\/api\/repair-chat$/.test(r.url())) started++;
    });
    await page.goto("/customer/assistant");
    await expect(page.getByPlaceholder(/describe|problem/i).first())
      .toBeVisible({ timeout: 45_000 });
    await page.waitForTimeout(3000);
    // React's development double-render opened two conversations once; the second silently
    // replaced the first and the transcript disagreed with the greeting.
    expect(started).toBeLessThanOrEqual(1);
  });
});

test.describe("Pages load for a signed-in customer", () => {
  for (const path of ["/customer", "/customer/report", "/account", "/notifications"]) {
    test(`${path} renders without an error state`, async ({ page }) => {
      await registerCustomer(page, "pages");
      const errors: string[] = [];
      page.on("pageerror", (e) => errors.push(e.message));
      const response = await page.goto(path);
      expect(response?.status(), `${path} returned a bad status`).toBeLessThan(400);
      await expect(page.locator("body")).not.toContainText(/application error|something went wrong/i);
      expect(errors, `${path} threw: ${errors.join("; ")}`).toHaveLength(0);
    });
  }
});

test.describe("Guests", () => {
  test("the landing page loads", async ({ page }) => {
    const response = await page.goto("/");
    expect(response?.status()).toBeLessThan(400);
    await expect(page.getByRole("link", { name: /sign in/i }).first()).toBeVisible();
  });

  test("a protected page sends a guest to sign in", async ({ page }) => {
    await page.goto("/customer/assistant");
    await expect(page).toHaveURL(/login/, { timeout: 30_000 });
  });
});

test.describe("Hydration safety", () => {
  /**
   * The bug this file found: a submit button clicked before React attaches performs a native GET,
   * reloading to "?" and silently discarding everything typed. It looked like "sign-up is slow and
   * sometimes does nothing" — which is precisely how it was reported.
   */
  for (const path of ["/register", "/login", "/forgot-password"]) {
    test(`${path} cannot be submitted before React attaches`, async ({ page }) => {
      await page.goto(path);
      const submit = page.locator('form button[type="submit"]').first();
      await expect(submit).toBeVisible();
      // Once enabled, React owns the form and preventDefault will run.
      await expect(submit).toBeEnabled({ timeout: 30_000 });
      await submit.click();
      // A native submit appends "?" — its absence proves the handler ran instead.
      await expect(page).not.toHaveURL(/\?$/);
    });
  }
});
