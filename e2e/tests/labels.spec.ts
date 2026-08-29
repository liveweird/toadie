import { createUserViaUi, expect, login, logoutButton, openFilters, rowOperation, test, uniqueText } from "./helpers";

// The label-registry journey on a throwaway key (unique per attempt, so retries never
// collide): create → validation → edit → the read-only view → applying the label in the
// catalog editor's registry-constrained pickers → cleanup. The registry is shared run-state
// and THIS SPEC IS ITS ONLY IN-RUN WRITER — it only ever creates and deletes its own unique
// key, never touching entries the local volume already holds.
test("admin curates the label registry; a regular user reads it; the editor enforces it", async ({
  page,
}) => {
  await login(page);
  const key = uniqueText("e2e-lbl");

  // The nav leaf is visible to everyone; the admin gets the New-label action.
  await page.getByRole("link", { name: "Labels" }).click();
  await expect(page.getByRole("heading", { name: "Labels" })).toBeVisible();

  // An empty modal submission is blocked client-side with the three field errors.
  await page.getByRole("button", { name: "New label" }).click();
  const dialog = () => page.getByRole("dialog");
  await dialog().getByRole("button", { name: "Save" }).click();
  await expect(dialog().getByText("Add at least one allowed value")).toBeVisible();
  await expect(dialog().getByText("Pick at least one kind")).toBeVisible();

  // Create the throwaway label: two values, Component only.
  await dialog().getByRole("textbox", { name: "Key" }).fill(key);
  const valuesInput = dialog().getByRole("combobox", { name: "Allowed values" });
  await valuesInput.fill("backend");
  await page.keyboard.press("Enter");
  await valuesInput.fill("frontend");
  await page.keyboard.press("Enter");
  // Mantine MultiSelect wraps its combobox input in a div that intercepts pointer events —
  // force targets the input itself (its click handler opens the dropdown).
  await dialog().getByRole("combobox", { name: "Applies to kinds" }).click({ force: true });
  await page.getByRole("option", { name: "Component", exact: true }).click();
  await Promise.all([
    page.waitForResponse(
      (r) => r.url().endsWith("/api/v1/labels") && r.request().method() === "POST" && r.ok(),
    ),
    dialog().getByRole("button", { name: "Save" }).click(),
  ]);
  const row = page.getByRole("row").filter({ hasText: key });
  await expect(row.getByText("backend", { exact: true })).toBeVisible();
  await expect(row.getByText("Component", { exact: true })).toBeVisible();

  // Edit: a third allowed value lands after a save.
  await page.getByRole("button", { name: `Edit ${key}` }).click();
  await dialog().getByRole("combobox", { name: "Allowed values" }).fill("edge");
  await page.keyboard.press("Enter");
  await Promise.all([
    page.waitForResponse((r) => /\/api\/v1\/labels\/\d+$/.test(r.url()) && r.request().method() === "PUT" && r.ok()),
    dialog().getByRole("button", { name: "Save" }).click(),
  ]);
  await expect(row.getByText("edge", { exact: true })).toBeVisible();

  // A regular user gets the same table read-only: no create, edit, or delete affordances.
  const throwaway = await createUserViaUi(page, "E2E Lbl Reader");
  await login(page, throwaway.email, throwaway.password);
  await page.getByRole("link", { name: "Labels" }).click();
  await expect(page.getByRole("heading", { name: "Labels" })).toBeVisible();
  await expect(page.getByText(key)).toBeVisible();
  await expect(page.getByRole("button", { name: "New label" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: `Edit ${key}` })).toHaveCount(0);
  await logoutButton(page).click();

  // Back as the admin: the editor's labels section is registry-constrained pickers — the
  // key offered for a Component document, the value from the label's closed list.
  await login(page);
  const name = uniqueText("e2e-lbl-comp");
  await page.goto("/catalog-files/new");
  await page.getByRole("textbox", { name: "Name", exact: true }).fill(name);
  await page.getByRole("combobox", { name: "Type" }).fill("service");
  await page.getByRole("combobox", { name: "Lifecycle" }).fill("production");
  await page.getByRole("combobox", { name: "Owner" }).fill("group:default/platform");
  await page.getByRole("button", { name: "Add label" }).click();
  const keySelect = page.getByRole("combobox", { name: "Labels Key 1" });
  await keySelect.click();
  await keySelect.fill(key);
  await page.getByRole("option", { name: key, exact: true }).click();
  await page.getByRole("combobox", { name: "Labels Value 1" }).click();
  await page.getByRole("option", { name: "backend", exact: true }).click();
  await expect(page.getByLabel("YAML preview")).toContainText(`${key}: backend`);
  const [created] = await Promise.all([
    page.waitForResponse(
      (r) => r.url().endsWith("/api/v1/catalog-files") && r.request().method() === "POST" && r.ok(),
    ),
    page.getByRole("button", { name: "Create" }).click(),
  ]);
  const fileId: number = (await created.json()).id;

  // Cleanup: the file, the label, and the throwaway user.
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

  await page.goto("/labels");
  await page.getByRole("button", { name: `Delete ${key}` }).click();
  await Promise.all([
    page.waitForResponse(
      (r) => /\/api\/v1\/labels\/\d+$/.test(r.url()) && r.request().method() === "DELETE" && r.ok(),
    ),
    page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click(),
  ]);
  await expect(page.getByText(key)).toHaveCount(0);

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
