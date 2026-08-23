import { expect, login, openFilters, test, uniqueText } from "./helpers";

// Catalog-file CRUD through the real UI, on a throwaway unique-named component so parallel
// files and re-runs never collide (the list asserts are always name-filter-anchored).
test("admin creates a component file, edits it, downloads the YAML, and deletes it", async ({
  page,
}) => {
  await login(page);
  // Space-free and grammar-valid ([a-z0-9-]), so it is a legal entity name AND needs no
  // encoding gymnastics in the list-filter assertions.
  const name = uniqueText("e2e-comp");

  // Create: the minimal Component (name/type/lifecycle/owner) plus a title.
  await page.goto("/catalog-files/new");
  await page.getByRole("textbox", { name: "Name", exact: true }).fill(name);
  await page.getByRole("combobox", { name: "Type" }).fill("service");
  await page.getByRole("combobox", { name: "Lifecycle" }).fill("production");
  await page.getByRole("textbox", { name: "Owner" }).fill("group:default/platform");
  await page.getByRole("textbox", { name: "Title", exact: true }).fill("E2E Component");

  // The live preview reflects the document before anything is saved.
  const preview = page.getByLabel("YAML preview");
  await expect(preview).toContainText("kind: Component");
  await expect(preview).toContainText(`name: ${name}`);

  const [created] = await Promise.all([
    page.waitForResponse(
      (r) => r.url().endsWith("/api/v1/catalog-files") && r.request().method() === "POST" && r.ok(),
    ),
    page.getByRole("button", { name: "Create" }).click(),
  ]);
  const fileId: number = (await created.json()).id;

  // The filtered list shows the new file.
  await expect(page).toHaveURL(/\/catalog-files$/);
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(name);
  await expect(page.getByText(name, { exact: true })).toBeVisible();
  await expect(page.getByText("E2E Component", { exact: true })).toBeVisible();

  // Edit: retitle and deprecate. The filter is debounced; the Edit button auto-waits.
  await page.getByRole("link", { name: `Edit ${name}` }).click();
  await expect(page).toHaveURL(new RegExp(`/catalog-files/${fileId}/edit`));
  await page.getByRole("textbox", { name: "Title", exact: true }).fill("E2E Component Renamed");
  await page.getByRole("combobox", { name: "Lifecycle" }).fill("deprecated");
  await Promise.all([
    page.waitForResponse(
      (r) => r.url().endsWith(`/api/v1/catalog-files/${fileId}`) && r.request().method() === "PUT" && r.ok(),
    ),
    page.getByRole("button", { name: "Save" }).click(),
  ]);

  // Reopen the editor — the changes persisted.
  await page.goto(`/catalog-files/${fileId}/edit`);
  await expect(page.getByRole("textbox", { name: "Title", exact: true })).toHaveValue(
    "E2E Component Renamed",
  );
  await expect(page.getByRole("combobox", { name: "Lifecycle" })).toHaveValue("deprecated");

  // Download hands over Backstage's canonical filename.
  await page.goto("/catalog-files");
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(name);
  const [download] = await Promise.all([
    page.waitForEvent("download"),
    page.getByRole("button", { name: `Download ${name}` }).click(),
  ]);
  expect(download.suggestedFilename()).toBe("catalog-info.yaml");

  // Delete from the filtered list, confirming in the modal.
  await page.getByRole("button", { name: `Delete ${name}` }).click();
  await Promise.all([
    page.waitForResponse(
      (r) =>
        r.url().endsWith(`/api/v1/catalog-files/${fileId}`) && r.request().method() === "DELETE" && r.ok(),
    ),
    page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click(),
  ]);

  // Verify against a fresh load — the in-place list can lose a refetch race with a stale
  // in-flight response.
  await page.goto("/catalog-files");
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(name);
  await expect(page.getByText("No catalog files")).toBeVisible();
});
