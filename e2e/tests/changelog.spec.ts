import { expect, login, test } from "./helpers";

// The changelog is a build-time artifact and the what's-new state is DEVICE-level
// (localStorage) — a fresh Playwright context always starts unseen, so this spec owns no
// server-side state at all. No exact version assertion (a release must not break e2e);
// the newest entry only has to look like a version + date.
test("the what's-new dot leads to the changelog and clears once it is read", async ({ page }) => {
  await login(page);

  // A fresh context has never seen any version — the dot is on the navbar stamp.
  await expect(page.getByTitle("What's new")).toBeVisible();

  // The stamp itself links to the changelog.
  await page.getByTitle("Build version").click();
  await expect(page.getByRole("heading", { name: "Changelog" })).toBeVisible();

  // Reading the page marks the version seen — the dot is gone.
  await expect(page.getByTitle("What's new")).toHaveCount(0);

  // The newest entry renders a version and a date (no exact pin — releases must not break e2e).
  await expect(page.getByText(/^v\d+\.\d+\.\d+$/).first()).toBeVisible();
  await expect(page.getByText(/^\d{4}-\d{2}-\d{2}$/).first()).toBeVisible();

  // The bodies follow the UI language: Polish shows the Polish title and bodies.
  await page.getByRole("button", { name: "Language" }).click();
  await page.getByRole("menuitem", { name: "Polski" }).click();
  await expect(page.getByRole("heading", { name: "Historia zmian" })).toBeVisible();
  await page.getByRole("button", { name: "Język" }).click();
  await page.getByRole("menuitem", { name: "English" }).click();
  await expect(page.getByRole("heading", { name: "Changelog" })).toBeVisible();

  // The dot stays cleared on later navigation.
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Hierarchy" })).toBeVisible();
  await expect(page.getByTitle("What's new")).toHaveCount(0);
});
