import type { Page } from "@playwright/test";
import { createUserViaUi, deleteUserRow, expect, login, signOut, test, uniqueText } from "./helpers";

/**
 * Every hierarchy input on the editor, in visible (= stored) order. `.all()` never waits,
 * so the first row is awaited explicitly — after a navigation the editor renders async.
 */
async function hierarchyValues(page: Page): Promise<string[]> {
  await expect(page.getByRole("textbox", { name: "Hierarchy 1", exact: true })).toBeVisible();
  const inputs = await page.getByRole("textbox", { name: /^Hierarchy / }).all();
  return Promise.all(inputs.map((input) => input.inputValue()));
}

/**
 * Save the document and wait for BOTH the PUT and the follow-up re-seed GET (the editor
 * reloads itself so new rows carry their minted ids). Done = Save drops back to disabled.
 */
async function saveHierarchies(page: Page): Promise<void> {
  const isDict = (r: { url(): string }) => r.url().endsWith("/api/v1/dictionaries/hierarchies");
  const put = page.waitForResponse((r) => isDict(r) && r.request().method() === "PUT" && r.ok());
  const reseed = page.waitForResponse((r) => isDict(r) && r.request().method() === "GET" && r.ok());
  await page.getByRole("button", { name: "Save" }).click();
  await put;
  await reseed;
  await expect(page.getByRole("button", { name: "Save" })).toBeDisabled();
}

/** Remove the row currently holding [value] (positions shift after each removal). */
async function removeHierarchyRow(page: Page, value: string): Promise<void> {
  const index = (await hierarchyValues(page)).indexOf(value);
  expect(index).toBeGreaterThanOrEqual(0);
  await page.getByRole("button", { name: `Remove hierarchy ${index + 1}`, exact: true }).click();
}

// The hierarchies-dictionary journey on one throwaway value (the e2e marker + uniqueness
// keeps the seeded `composition` entry untouched — the Entity hierarchy/graph pickers ride
// it): validation → append → the read-only view → removal. The document is shared state and
// THIS SPEC IS ITS ONLY IN-RUN WRITER — it only ever appends and removes its own unique value.
// (The blueprint editor's per-hierarchy relation pickers are the entity-hierarchy spec's
// business once they exist; this dictionary has no catalog-editor consumer.)
test("admin curates the global hierarchies list; a regular user reads it", async ({ page }) => {
  await login(page);
  const extra = uniqueText("e2e-hier");

  // The nav leaf is visible to everyone; the admin lands in the document editor with the
  // seeded values present and no default radios (hierarchies have no default concept).
  await page.getByRole("link", { name: "Hierarchies" }).click();
  await expect(page.getByRole("heading", { name: "Hierarchies" })).toBeVisible();
  const save = page.getByRole("button", { name: "Save" });
  await expect(save).toBeDisabled();
  expect(await hierarchyValues(page)).toContain("composition");
  await expect(page.getByRole("radio")).toHaveCount(0);

  // A grammar violation is flagged inline and never reaches the server.
  const lastEntry = () => page.getByRole("textbox", { name: /^Hierarchy / }).last();
  await page.getByRole("button", { name: "Add hierarchy" }).click();
  await lastEntry().fill("Bad_Value");
  await save.click();
  await expect(page.getByText("Must be 1–63 lowercase alphanumeric characters")).toBeVisible();

  // The unique entry appended and saved.
  await lastEntry().fill(extra);
  await saveHierarchies(page);
  expect(await hierarchyValues(page)).toContain(extra);

  // A regular user gets the same list read-only: numbered rows, no editor controls.
  const throwaway = await createUserViaUi(page, "E2E Hier Reader");
  await login(page, throwaway.email, throwaway.password);
  await page.getByRole("link", { name: "Hierarchies" }).click();
  await expect(page.getByRole("heading", { name: "Hierarchies" })).toBeVisible();
  await expect(page.getByText(extra)).toBeVisible();
  await expect(page.getByRole("button", { name: "Add hierarchy" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Save" })).toHaveCount(0);
  await signOut(page);

  // Back as the admin: remove the appended value and the throwaway user.
  await login(page);
  await page.goto("/hierarchies");
  await removeHierarchyRow(page, extra);
  await saveHierarchies(page);
  expect(await hierarchyValues(page)).not.toContain(extra);

  await deleteUserRow(page, throwaway.name);
});
