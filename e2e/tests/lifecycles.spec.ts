import type { Page } from "@playwright/test";
import {
  createUserViaUi,
  expect,
  login,
  logoutButton,
  openFilters,
  pickLifecycle,
  pickType,
  rowOperation,
  test,
  uniqueText,
} from "./helpers";

/**
 * Every lifecycle input on the editor, in visible (= stored) order. `.all()` never waits,
 * so the first row is awaited explicitly — after a navigation the editor renders async.
 */
async function lifecycleValues(page: Page): Promise<string[]> {
  await expect(page.getByRole("textbox", { name: "Lifecycle 1", exact: true })).toBeVisible();
  const inputs = await page.getByRole("textbox", { name: /^Lifecycle / }).all();
  return Promise.all(inputs.map((input) => input.inputValue()));
}

/**
 * Save the document and wait for BOTH the PUT and the follow-up re-seed GET (the editor
 * reloads itself so new rows carry their minted ids). Done = Save drops back to disabled.
 */
async function saveLifecycles(page: Page): Promise<void> {
  const isDict = (r: { url(): string }) => r.url().endsWith("/api/v1/dictionaries/lifecycles");
  const put = page.waitForResponse((r) => isDict(r) && r.request().method() === "PUT" && r.ok());
  const reseed = page.waitForResponse((r) => isDict(r) && r.request().method() === "GET" && r.ok());
  await page.getByRole("button", { name: "Save" }).click();
  await put;
  await reseed;
  await expect(page.getByRole("button", { name: "Save" })).toBeDisabled();
}

/** Remove the row currently holding [value] (positions shift after each removal). */
async function removeLifecycleRow(page: Page, value: string): Promise<void> {
  const index = (await lifecycleValues(page)).indexOf(value);
  expect(index).toBeGreaterThanOrEqual(0);
  await page.getByRole("button", { name: `Remove lifecycle ${index + 1}`, exact: true }).click();
}

// The lifecycles-dictionary journey on one throwaway value (the e2e marker + uniqueness
// keeps the seeded well-known entries untouched — every spec's pickLifecycle rides those):
// validation → append → the read-only view → the editor's registry Select on a new file →
// removal. The document is shared state and THIS SPEC IS ITS ONLY IN-RUN WRITER — it only
// ever appends and removes its own unique value.
test("admin curates the global lifecycles list; a regular user reads it; the editor enforces it", async ({
  page,
}) => {
  await login(page);
  const extra = uniqueText("e2e-lc");

  // The nav leaf is visible to everyone; the admin lands in the document editor with the
  // seeded values present and no default radios (lifecycles have no default concept).
  await page.getByRole("link", { name: "Lifecycles" }).click();
  await expect(page.getByRole("heading", { name: "Lifecycles" })).toBeVisible();
  const save = page.getByRole("button", { name: "Save" });
  await expect(save).toBeDisabled();
  expect(await lifecycleValues(page)).toContain("production");
  await expect(page.getByRole("radio")).toHaveCount(0);

  // A grammar violation is flagged inline and never reaches the server.
  const lastEntry = () => page.getByRole("textbox", { name: /^Lifecycle / }).last();
  await page.getByRole("button", { name: "Add lifecycle" }).click();
  await lastEntry().fill("Bad_Value");
  await save.click();
  await expect(page.getByText("Must be 1–63 lowercase alphanumeric characters")).toBeVisible();

  // The unique entry appended and saved.
  await lastEntry().fill(extra);
  await saveLifecycles(page);
  expect(await lifecycleValues(page)).toContain(extra);

  // A regular user gets the same list read-only: numbered rows, no editor controls.
  const throwaway = await createUserViaUi(page, "E2E Lc Reader");
  await login(page, throwaway.email, throwaway.password);
  await page.getByRole("link", { name: "Lifecycles" }).click();
  await expect(page.getByRole("heading", { name: "Lifecycles" })).toBeVisible();
  await expect(page.getByText(extra)).toBeVisible();
  await expect(page.getByRole("button", { name: "Add lifecycle" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Save" })).toHaveCount(0);
  await logoutButton(page).click();

  // Back as the admin: the editor's Lifecycle field is the registry Select — the appended
  // value is offered for a new Component and the save passes the strict server check.
  await login(page);
  const name = uniqueText("e2e-lc-comp");
  await page.goto("/files/new");
  await page.getByRole("textbox", { name: "Name", exact: true }).fill(name);
  await pickType(page, "service");
  await pickLifecycle(page, extra);
  await page.getByRole("combobox", { name: "Owner" }).fill("group:default/platform");
  const [created] = await Promise.all([
    page.waitForResponse(
      (r) => r.url().endsWith("/api/v1/files") && r.request().method() === "POST" && r.ok(),
    ),
    page.getByRole("button", { name: "Create" }).click(),
  ]);
  const fileId: number = (await created.json()).id;

  // Cleanup: the file, the appended lifecycle, and the throwaway user.
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(name);
  await rowOperation(page, name, "Delete");
  await Promise.all([
    page.waitForResponse(
      (r) =>
        r.url().endsWith(`/api/v1/files/${fileId}`) && r.request().method() === "DELETE" && r.ok(),
    ),
    page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click(),
  ]);

  await page.goto("/lifecycles");
  await removeLifecycleRow(page, extra);
  await saveLifecycles(page);
  expect(await lifecycleValues(page)).not.toContain(extra);

  await page.goto("/users");
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(throwaway.name);
  await page.getByRole("button", { name: `Delete ${throwaway.name}` }).click();
  await Promise.all([
    page.waitForResponse(
      (r) =>
        r.url().endsWith(`/api/v1/users/${throwaway.id}`) && r.request().method() === "DELETE" && r.ok(),
    ),
    page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click(),
  ]);
});
