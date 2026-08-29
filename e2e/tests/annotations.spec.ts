import { createUserViaUi, expect, login, logoutButton, openFilters, pickLifecycle, pickType, rowOperation, test, uniqueText } from "./helpers";

// The annotation-key-registry journey on a throwaway key (unique per attempt, so retries
// never collide): create → validation → edit → the read-only view → applying the annotation
// in the catalog editor (key from the registry Select, free-text value) → cleanup. The
// registry is shared run-state and THIS SPEC IS ITS ONLY IN-RUN WRITER — it only ever
// creates and deletes its own unique key, never touching entries the local volume holds.
test("admin curates the annotation keys; a regular user reads them; the editor enforces them", async ({
  page,
}) => {
  await login(page);
  const key = uniqueText("e2e-ann");

  // The nav leaf is visible to everyone; the admin gets the New-key action.
  await page.getByRole("link", { name: "Annotations" }).click();
  await expect(page.getByRole("heading", { name: "Annotations" })).toBeVisible();

  // An empty modal submission is blocked client-side with both field errors.
  await page.getByRole("button", { name: "New annotation key" }).click();
  const dialog = () => page.getByRole("dialog");
  await dialog().getByRole("button", { name: "Save" }).click();
  await expect(dialog().getByText(/Must be an optional lowercase-domain prefix/)).toBeVisible();
  await expect(dialog().getByText("Pick at least one kind")).toBeVisible();

  // Create the throwaway key: Component only.
  await dialog().getByRole("textbox", { name: "Key" }).fill(key);
  // Mantine MultiSelect wraps its combobox input in a div that intercepts pointer events —
  // force targets the input itself (its click handler opens the dropdown).
  await dialog().getByRole("combobox", { name: "Applies to kinds" }).click({ force: true });
  await page.getByRole("option", { name: "Component", exact: true }).click();
  // This modal is short — the open kinds dropdown overlaps Save; Escape closes it first.
  await page.keyboard.press("Escape");
  await Promise.all([
    page.waitForResponse(
      (r) => r.url().endsWith("/api/v1/annotation-keys") && r.request().method() === "POST" && r.ok(),
    ),
    dialog().getByRole("button", { name: "Save" }).click(),
  ]);
  const row = page.getByRole("row").filter({ hasText: key });
  await expect(row.getByText("Component", { exact: true })).toBeVisible();

  // Edit: a second kind lands after a save.
  await page.getByRole("button", { name: `Edit ${key}` }).click();
  await dialog().getByRole("combobox", { name: "Applies to kinds" }).click({ force: true });
  await page.getByRole("option", { name: "API", exact: true }).click();
  await page.keyboard.press("Escape");
  await Promise.all([
    page.waitForResponse(
      (r) => /\/api\/v1\/annotation-keys\/\d+$/.test(r.url()) && r.request().method() === "PUT" && r.ok(),
    ),
    dialog().getByRole("button", { name: "Save" }).click(),
  ]);
  await expect(row.getByText("API", { exact: true })).toBeVisible();

  // A regular user gets the same table read-only: no create, edit, or delete affordances.
  const throwaway = await createUserViaUi(page, "E2E Ann Reader");
  await login(page, throwaway.email, throwaway.password);
  await page.getByRole("link", { name: "Annotations" }).click();
  await expect(page.getByRole("heading", { name: "Annotations" })).toBeVisible();
  await expect(page.getByText(key)).toBeVisible();
  await expect(page.getByRole("button", { name: "New annotation key" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: `Edit ${key}` })).toHaveCount(0);
  await logoutButton(page).click();

  // Back as the admin: the editor's annotation KEY is the registry Select (only registered
  // keys for the kind are offered) while the VALUE stays free text.
  await login(page);
  const name = uniqueText("e2e-ann-comp");
  await page.goto("/catalog-files/new");
  await page.getByRole("textbox", { name: "Name", exact: true }).fill(name);
  await pickType(page, "service");
  await pickLifecycle(page, "production");
  await page.getByRole("combobox", { name: "Owner" }).fill("group:default/platform");
  await page.getByRole("button", { name: "Add annotation" }).click();
  const keySelect = page.getByRole("combobox", { name: "Annotations Key 1" });
  await keySelect.click();
  await keySelect.fill(key);
  await page.getByRole("option", { name: key, exact: true }).click();
  await page.getByLabel("Annotations Value 1").fill("any free text: works/here too");
  await expect(page.getByLabel("YAML preview")).toContainText(key);
  const [created] = await Promise.all([
    page.waitForResponse(
      (r) => r.url().endsWith("/api/v1/catalog-files") && r.request().method() === "POST" && r.ok(),
    ),
    page.getByRole("button", { name: "Create" }).click(),
  ]);
  const fileId: number = (await created.json()).id;

  // Cleanup: the file, the key, and the throwaway user.
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

  await page.goto("/annotations");
  await page.getByRole("button", { name: `Delete ${key}` }).click();
  await Promise.all([
    page.waitForResponse(
      (r) => /\/api\/v1\/annotation-keys\/\d+$/.test(r.url()) && r.request().method() === "DELETE" && r.ok(),
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
