import { test, expect } from "@playwright/test";

/**
 * Instrumented reproduction of "Send does nothing".
 *
 * Captures what the browser actually does — console output, every request to the chat API and its
 * status — rather than inferring from the server side. Two previous root-cause theories were wrong
 * because they were reasoned from API measurements that never touched this code path.
 */
test("capture what happens when Send is pressed", async ({ page }) => {
  const console_: string[] = [];
  const failures: string[] = [];
  const calls: string[] = [];

  page.on("console", (m) => console_.push(`${m.type()}: ${m.text()}`.slice(0, 200)));
  page.on("pageerror", (e) => failures.push(`PAGEERROR: ${e.message}`.slice(0, 200)));
  page.on("requestfailed", (r) =>
    failures.push(`REQFAILED: ${r.method()} ${new URL(r.url()).pathname} — ${r.failure()?.errorText}`));
  page.on("response", async (r) => {
    const p = new URL(r.url()).pathname;
    if (p.includes("/api/")) {
      let body = "";
      try { body = (await r.text()).slice(0, 160); } catch { /* stream consumed */ }
      calls.push(`${r.request().method()} ${p} → ${r.status()} ${body}`);
    }
  });

  const email = `diag${Date.now()}@demo.local`;
  await page.goto("/register");
  await expect(page.getByRole("button", { name: /create account/i })).toBeEnabled({ timeout: 30_000 });
  await page.getByLabel(/full name|name/i).first().fill("Diag");
  await page.getByLabel(/email/i).first().fill(email);
  await page.getByLabel(/password/i).first().fill("DevTest!2026x");
  await page.getByRole("button", { name: /create account/i }).click();
  await expect(page).not.toHaveURL(/register/, { timeout: 45_000 });

  calls.length = 0;                                   // only care about the chat screen
  await page.goto("/customer/assistant");
  await page.waitForTimeout(8000);                    // let the conversation open

  console.log("\n=== AFTER PAGE LOAD ===");
  calls.forEach((c) => console.log("  " + c));

  const box = page.getByPlaceholder(/describe|problem|starting|connecting|listening/i).first();
  const send = page.getByRole("button", { name: /^send$/i });
  console.log("\n=== COMPOSER STATE ===");
  console.log("  input enabled :", await box.isEnabled().catch(() => "n/a"));
  console.log("  send  visible :", await send.isVisible().catch(() => "n/a"));
  console.log("  send  enabled :", await send.isEnabled().catch(() => "n/a"));

  await box.fill("My kitchen tap is leaking");
  console.log("  input value   :", await box.inputValue());
  console.log("  send enabled after typing:", await send.isEnabled().catch(() => "n/a"));

  calls.length = 0;
  await send.click({ force: true }).catch((e) => console.log("  CLICK THREW:", e.message.slice(0, 120)));
  await page.waitForTimeout(12000);

  console.log("\n=== AFTER CLICKING SEND ===");
  console.log("  requests:", calls.length === 0 ? "NONE — no request was made" : "");
  calls.forEach((c) => console.log("  " + c));
  console.log("  input still holds:", JSON.stringify(await box.inputValue().catch(() => "n/a")));
  console.log("\n=== CONSOLE ===");
  console_.slice(-12).forEach((c) => console.log("  " + c));
  console.log("\n=== FAILURES ===");
  failures.length ? failures.forEach((f) => console.log("  " + f)) : console.log("  none");
});
