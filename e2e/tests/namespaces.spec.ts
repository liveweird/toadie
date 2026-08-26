import type { Page } from "@playwright/test";
import { createUserViaUi, expect, login, logoutButton, openFilters, test, uniqueText } from "./helpers";

/**
 * Every namespace input on the editor, in visible (= stored) order. `.all()` never waits,
 * so the first row is awaited explicitly — after a navigation the editor renders async.
 */
async function namespaceValues(page: Page): Promise<string[]> {
  await expect(page.getByRole("textbox", { name: "Namespace 1", exact: true })).toBeVisible();
  const inputs = await page.getByRole("textbox", { name: /^Namespace / }).all();
  return Promise.all(inputs.map((input) => input.inputValue()));
}

/**
 * Save the document and wait for BOTH the PUT and the follow-up re-seed GET (the editor
 * reloads itself so new rows carry their minted ids) — editing again before the re-seed
 * lands would be silently reverted by it. Done = Save drops back to disabled.
 */
async function saveNamespaces(page: Page): Promise<void> {
  const isDict = (r: { url(): string }) => r.url().endsWith("/api/v1/dictionaries/namespaces");
  const put = page.waitForResponse((r) => isDict(r) && r.request().method() === "PUT" && r.ok());
  const reseed = page.waitForResponse((r) => isDict(r) && r.request().method() === "GET" && r.ok());
  await page.getByRole("button", { name: "Save" }).click();
  await put;
  await reseed;
  await expect(page.getByRole("button", { name: "Save" })).toBeDisabled();
}

/** Remove the row currently holding [value] (positions shift after each removal). */
async function removeNamespaceRow(page: Page, value: string): Promise<void> {
  const index = (await namespaceValues(page)).indexOf(value);
  expect(index).toBeGreaterThanOrEqual(0);
  await page.getByRole("button", { name: `Remove namespace ${index + 1}`, exact: true }).click();
}

// The whole dictionary journey on throwaway values (the e2e marker + uniqueness keeps the
// shared volume's entries untouched): validation → append → reorder → the read-only view →
// removal. The document is shared state — this spec only ever appends and removes its own
// unique values, never replacing what the volume already holds.
test("admin curates the ordered namespaces list; a regular user reads it only", async ({
  page,
}) => {
  await login(page);

  // The nav leaf is visible to everyone; the admin lands in the document editor.
  await page.getByRole("link", { name: "Namespaces" }).click();
  await expect(page.getByRole("heading", { name: "Namespaces" })).toBeVisible();
  const save = page.getByRole("button", { name: "Save" });
  await expect(save).toBeDisabled();

  // A grammar violation is flagged inline and never reaches the server.
  const lastEntry = () => page.getByRole("textbox", { name: /^Namespace / }).last();
  await page.getByRole("button", { name: "Add namespace" }).click();
  await lastEntry().fill("Bad_Value");
  await save.click();
  await expect(page.getByText("Must be 1–63 lowercase alphanumeric characters")).toBeVisible();

  // Two unique entries appended and saved; the payload order is the stored order.
  const nsA = uniqueText("e2e-ns-a");
  const nsB = uniqueText("e2e-ns-b");
  await lastEntry().fill(nsA);
  await page.getByRole("button", { name: "Add namespace" }).click();
  await lastEntry().fill(nsB);
  await saveNamespaces(page);
  const afterAdd = await namespaceValues(page);
  expect(afterAdd.indexOf(nsA)).toBeGreaterThanOrEqual(0);
  expect(afterAdd.indexOf(nsA)).toBeLessThan(afterAdd.indexOf(nsB));

  // Reorder with the row's up control, save, and confirm the order survives a fresh load.
  await page
    .getByRole("button", { name: `Move namespace ${afterAdd.indexOf(nsB) + 1} up`, exact: true })
    .click();
  await saveNamespaces(page);
  await page.goto("/namespaces");
  const reloaded = await namespaceValues(page);
  expect(reloaded.indexOf(nsB)).toBeLessThan(reloaded.indexOf(nsA));

  // A regular user gets the same list read-only: numbered rows, no editor controls.
  const throwaway = await createUserViaUi(page, "E2E Ns Reader");
  await login(page, throwaway.email, throwaway.password);
  await page.getByRole("link", { name: "Namespaces" }).click();
  await expect(page.getByRole("heading", { name: "Namespaces" })).toBeVisible();
  await expect(page.getByText(nsB)).toBeVisible();
  await expect(page.getByRole("button", { name: "Add namespace" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Save" })).toHaveCount(0);
  await logoutButton(page).click();

  // Cleanup as the admin: both throwaway entries removed (one save), then the throwaway user.
  await login(page);
  await page.goto("/namespaces");
  await removeNamespaceRow(page, nsA);
  await removeNamespaceRow(page, nsB);
  await saveNamespaces(page);
  expect(await namespaceValues(page)).not.toContain(nsA);

  await page.goto("/users");
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(throwaway.name);
  await page.getByRole("button", { name: `Delete ${throwaway.name}` }).click();
  await Promise.all([
    page.waitForResponse(
      (r) =>
        r.url().endsWith(`/api/v1/users/${throwaway.id}`) &&
        r.request().method() === "DELETE" &&
        r.ok(),
    ),
    page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click(),
  ]);
});
