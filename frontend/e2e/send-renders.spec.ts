import { test, expect } from "@playwright/test";

/** Does the assistant's reply actually appear in the transcript after Send? */
test("the reply is rendered", async ({ page }) => {
  const email = `render${Date.now()}@demo.local`;
  await page.goto("/register");
  await expect(page.getByRole("button", { name: /create account/i })).toBeEnabled({ timeout: 30_000 });
  await page.getByLabel(/full name|name/i).first().fill("R");
  await page.getByLabel(/email/i).first().fill(email);
  await page.getByLabel(/password/i).first().fill("DevTest!2026x");
  await page.getByRole("button", { name: /create account/i }).click();
  await expect(page).not.toHaveURL(/register/, { timeout: 45_000 });

  await page.goto("/customer/assistant");
  const box = page.getByPlaceholder(/describe|problem|starting|connecting|listening/i).first();
  await expect(box).toBeEnabled({ timeout: 30_000 });
  await box.fill("My kitchen tap is leaking");
  // Send stays disabled until the conversation exists — a real person sees the same thing, and
  // waiting for it is what stops the message being silently dropped.
  const send = page.getByRole("button", { name: /^send$/i });
  await expect(send).toBeEnabled({ timeout: 45_000 });
  await send.click();

  // The customer's own message must appear...
  await expect(page.getByText("My kitchen tap is leaking")).toBeVisible({ timeout: 30_000 });
  // ...and an assistant reply after it. Assert on structure, not on wording: the verdict decides
  // what the assistant says, and a short description legitimately gets a follow-up question
  // rather than a repair plan.
  const bubbles = page.locator("main div.rounded-2xl");
  await expect(async () => {
    expect(await bubbles.count()).toBeGreaterThanOrEqual(3);   // greeting + customer + reply
  }).toPass({ timeout: 60_000 });

  const texts = await bubbles.allTextContents();
  console.log("\n=== TRANSCRIPT ===");
  texts.forEach((t, i) => console.log(`  ${i + 1}. ${t.slice(0, 110)}`));
});
