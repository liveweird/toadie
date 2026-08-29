import { createUserViaUi, expect, login, logoutButton, openFilters, rowOperation, test, uniqueText } from "./helpers";

// The tag-category journey on a throwaway category (unique name and tags per attempt, so
// retries never collide): create → validation → edit → the read-only view → applying a tag
// through the editor's grouped picker → cleanup. The tag-category registry is shared
// run-state and THIS SPEC IS ITS ONLY IN-RUN WRITER — it only ever creates and deletes its
// own unique category, never touching entries the local volume already holds.
test("admin curates the tag categories; a regular user reads them; the editor enforces them", async ({
  page,
}) => {
  await login(page);
  const category = uniqueText("e2e-tagcat");
  // Tag grammar is lowercase-only; uniqueText output is already [a-z0-9-].
  const tagA = uniqueText("e2e-tag-a");
  const tagB = uniqueText("e2e-tag-b");

  // The nav leaf is visible to everyone; the admin gets the New-category action.
  await page.getByRole("link", { name: "Tags" }).click();
  await expect(page.getByRole("heading", { name: "Tags" })).toBeVisible();

  // An empty modal submission is blocked client-side with the three field errors.
  await page.getByRole("button", { name: "New category" }).click();
  const dialog = () => page.getByRole("dialog");
  await dialog().getByRole("button", { name: "Save" }).click();
  await expect(dialog().getByText("Add at least one tag")).toBeVisible();
  await expect(dialog().getByText("Pick at least one kind")).toBeVisible();

  // Create the throwaway category: one tag, Component only.
  await dialog().getByRole("textbox", { name: "Category name" }).fill(category);
  const tagsInput = dialog().getByRole("combobox", { name: "Tags" });
  await tagsInput.fill(tagA);
  await page.keyboard.press("Enter");
  // Mantine MultiSelect wraps its combobox input in a div that intercepts pointer events —
  // force targets the input itself (its click handler opens the dropdown).
  await dialog().getByRole("combobox", { name: "Applies to kinds" }).click({ force: true });
  await page.getByRole("option", { name: "Component", exact: true }).click();
  await Promise.all([
    page.waitForResponse(
      (r) => r.url().endsWith("/api/v1/tag-categories") && r.request().method() === "POST" && r.ok(),
    ),
    dialog().getByRole("button", { name: "Save" }).click(),
  ]);
  const row = page.getByRole("row").filter({ hasText: category });
  await expect(row.getByText(tagA, { exact: true })).toBeVisible();
  await expect(row.getByText("Component", { exact: true })).toBeVisible();

  // Edit: a second tag lands after a save.
  await page.getByRole("button", { name: `Edit ${category}` }).click();
  await dialog().getByRole("combobox", { name: "Tags" }).fill(tagB);
  await page.keyboard.press("Enter");
  await Promise.all([
    page.waitForResponse(
      (r) => /\/api\/v1\/tag-categories\/\d+$/.test(r.url()) && r.request().method() === "PUT" && r.ok(),
    ),
    dialog().getByRole("button", { name: "Save" }).click(),
  ]);
  await expect(row.getByText(tagB, { exact: true })).toBeVisible();

  // A regular user gets the same table read-only: no create, edit, or delete affordances.
  const throwaway = await createUserViaUi(page, "E2E Tag Reader");
  await login(page, throwaway.email, throwaway.password);
  await page.getByRole("link", { name: "Tags" }).click();
  await expect(page.getByRole("heading", { name: "Tags" })).toBeVisible();
  await expect(page.getByText(category)).toBeVisible();
  await expect(page.getByRole("button", { name: "New category" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: `Edit ${category}` })).toHaveCount(0);
  await logoutButton(page).click();

  // Back as the admin: the editor's tags field is the grouped registry picker — the
  // category's tags offered under its name for a Component document.
  await login(page);
  const name = uniqueText("e2e-tag-comp");
  await page.goto("/catalog-files/new");
  await page.getByRole("textbox", { name: "Name", exact: true }).fill(name);
  await page.getByRole("combobox", { name: "Type" }).fill("service");
  await page.getByRole("combobox", { name: "Lifecycle" }).fill("production");
  await page.getByRole("combobox", { name: "Owner" }).fill("group:default/platform");
  const tagsPicker = page.getByRole("combobox", { name: "Tags", exact: true });
  await tagsPicker.click({ force: true });
  await tagsPicker.fill(tagA);
  await page.getByRole("option", { name: tagA, exact: true }).click();
  await expect(page.getByLabel("YAML preview")).toContainText(`- ${tagA}`);
  const [created] = await Promise.all([
    page.waitForResponse(
      (r) => r.url().endsWith("/api/v1/catalog-files") && r.request().method() === "POST" && r.ok(),
    ),
    page.getByRole("button", { name: "Create" }).click(),
  ]);
  const fileId: number = (await created.json()).id;

  // Cleanup: the file, the category, and the throwaway user.
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(name);
  await rowOperation(page, name, "Delete");
  await Promise.all([
    page.waitForResponse(
      (r) =>
        r.url().endsWith(`/api/v1/catalog-files/${fileId}`) && r.request().method() === "DELETE" && r.ok(),
    ),
    page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click(),
  ]);

  await page.goto("/tags");
  await page.getByRole("button", { name: `Delete ${category}` }).click();
  await Promise.all([
    page.waitForResponse(
      (r) => /\/api\/v1\/tag-categories\/\d+$/.test(r.url()) && r.request().method() === "DELETE" && r.ok(),
    ),
    page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click(),
  ]);
  await expect(page.getByText(category)).toHaveCount(0);

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
