import { createUserViaUi, expect, login, logoutButton, openFilters, test } from "./helpers";

// The ONE synced per-user language (V18): the header switcher saves it server-side, and a
// fresh device restores it at sign-in. Runs ENTIRELY on a throwaway user — seeded accounts
// must stay English, because every login applies the stored language to that session's UI
// and parallel specs assert English labels.
test("a language switch persists across reload and re-login on a fresh device", async ({ page }) => {
  await login(page);
  const throwaway = await createUserViaUi(page, "E2E I18n");
  await logoutButton(page).click();

  // The throwaway signs in through the real form and switches to Polish — the UI follows
  // and the choice saves server-side (the switcher's fire-and-forget PUT).
  await login(page, throwaway.email, throwaway.password);
  await Promise.all([
    page.waitForResponse(
      (r) =>
        r.url().endsWith(`/api/v1/users/${throwaway.id}/language`) &&
        r.request().method() === "PUT" &&
        r.ok(),
    ),
    (async () => {
      await page.getByRole("button", { name: "Language" }).click();
      await page.getByRole("menuitem", { name: "Polski" }).click();
    })(),
  ]);
  await expect(page.getByRole("heading", { name: "Hierarchia" })).toBeVisible();

  // Survives a reload (the device caches the choice).
  await page.reload();
  await expect(page.getByRole("heading", { name: "Hierarchia" })).toBeVisible();

  // The sync proof: wipe ALL device state and sign in afresh — the UI comes up Polish
  // purely from the server-stored value riding the login response.
  await page.evaluate(() => localStorage.clear());
  await page.goto("/login");
  await page.getByRole("textbox", { name: "Email" }).fill(throwaway.email);
  await page.getByRole("textbox", { name: "Password" }).fill(throwaway.password);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("heading", { name: "Hierarchia" })).toBeVisible();

  // Signing back in as the (English-language) seed admin flips the UI back to English the
  // same way — the sync works in both directions. Cleanup: delete the throwaway user.
  await page.getByRole("button", { name: "Wyloguj" }).click();
  await login(page);
  await page.goto("/users");
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(throwaway.name);
  await page.getByRole("button", { name: `Delete ${throwaway.name}` }).click();
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "DELETE" && r.ok()),
    page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click(),
  ]);
});
