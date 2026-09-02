import { expect, login, openFilters, pickNamespace, rowOperation, runNamespace, test, uniqueText } from "./helpers";

// The ⌘K / Ctrl K command palette (v1.19.0): page navigation plus a server-side catalog file
// search by name. Owns exactly one throwaway System in the render run namespace.
test("the command palette jumps to pages and opens a file by name", async ({ page }) => {
  await login(page);
  const ns = runNamespace("render");
  const name = uniqueText("e2e-palette");

  await page.goto("/files/new");
  await page.getByRole("combobox", { name: "Kind" }).click();
  await page.getByRole("option", { name: "System", exact: true }).click();
  await page.getByRole("textbox", { name: "Name", exact: true }).fill(name);
  await pickNamespace(page, ns);
  await page.getByRole("combobox", { name: "Owner" }).fill("group:default/platform");
  await Promise.all([
    page.waitForResponse((r) => r.url().endsWith("/api/v1/files") && r.request().method() === "POST" && r.ok()),
    page.getByRole("button", { name: "Create" }).click(),
  ]);
  await expect(page).toHaveURL(/\/files$/);

  // The shortcut opens the palette; a page name narrows to that page and Enter goes there.
  await page.keyboard.press("Control+k");
  const search = page.getByPlaceholder("Search files or jump to a page…");
  await expect(search).toBeVisible();
  await search.fill("Users");
  await page.keyboard.press("Enter");
  await expect(page.getByRole("heading", { name: "Users" })).toBeVisible();

  // The header trigger opens it too; two characters of a file name search the catalog, and
  // the result opens the file's editor.
  await page.getByRole("button", { name: "Search and jump to…" }).first().click();
  await search.fill(name);
  await page.getByRole("button", { name }).click();
  await expect(page.getByRole("heading", { name: "Edit catalog file" })).toBeVisible();
  await expect(page.getByRole("textbox", { name: "Name", exact: true })).toHaveValue(name);

  // Cleanup.
  await page.goto("/files");
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(name);
  await rowOperation(page, name, "Delete");
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "DELETE" && r.ok()),
    page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click(),
  ]);
});
