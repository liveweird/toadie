import { createUserViaUi, expect, login, logoutButton, openFilters, pickType, rowOperation, test, uniqueText } from "./helpers";

// The type-dictionary journey. The dictionaries are per-kind SINGLETONS seeded by V15, so
// unlike tags there is no throwaway row to create: this spec APPENDS one unique type to the
// Domain dictionary (the kind no other spec touches) and restores the list at the end. The
// registry is shared run-state and THIS SPEC IS ITS ONLY IN-RUN WRITER — it never removes
// values it did not add.
test("admin curates the type dictionaries; a regular user reads them; the editor enforces them", async ({
  page,
}) => {
  await login(page);
  // Type grammar is single-word; uniqueText output is already [a-z0-9-].
  const extraType = uniqueText("e2e-type");

  // The nav leaf is visible to everyone; the admin gets the New-dictionary action, and the
  // V15 seed rows are already listed (Component with its well-known values).
  await page.getByRole("link", { name: "Types" }).click();
  await expect(page.getByRole("heading", { name: "Types" })).toBeVisible();
  const componentRow = page.getByRole("row").filter({ has: page.getByText("Component", { exact: true }) });
  await expect(componentRow.getByText("service", { exact: true })).toBeVisible();

  // An empty modal submission is blocked client-side with both field errors.
  await page.getByRole("button", { name: "New dictionary" }).click();
  const dialog = () => page.getByRole("dialog");
  await dialog().getByRole("button", { name: "Save" }).click();
  await expect(dialog().getByText("Pick a type-bearing kind")).toBeVisible();
  await expect(dialog().getByText("Add at least one type")).toBeVisible();
  await dialog().getByRole("button", { name: "Cancel" }).click();

  // Edit the Domain dictionary: the unique type lands after a save.
  const domainRow = page.getByRole("row").filter({ has: page.getByText("Domain", { exact: true }) });
  await page.getByRole("button", { name: "Edit Domain" }).click();
  await dialog().getByRole("combobox", { name: "Allowed types" }).fill(extraType);
  await page.keyboard.press("Enter");
  await Promise.all([
    page.waitForResponse(
      (r) => /\/api\/v1\/entity-types\/\d+$/.test(r.url()) && r.request().method() === "PUT" && r.ok(),
    ),
    dialog().getByRole("button", { name: "Save" }).click(),
  ]);
  await expect(domainRow.getByText(extraType, { exact: true })).toBeVisible();

  // A regular user gets the same table read-only: no create, edit, or delete affordances.
  const throwaway = await createUserViaUi(page, "E2E Type Reader");
  await login(page, throwaway.email, throwaway.password);
  await page.getByRole("link", { name: "Types" }).click();
  await expect(page.getByRole("heading", { name: "Types" })).toBeVisible();
  await expect(page.getByText(extraType)).toBeVisible();
  await expect(page.getByRole("button", { name: "New dictionary" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Edit Domain" })).toHaveCount(0);
  await logoutButton(page).click();

  // Back as the admin: the editor's Type field is the registry Select — the appended value
  // is offered for a Domain document and the save passes the strict server check.
  await login(page);
  const name = uniqueText("e2e-type-domain");
  await page.goto("/files/new");
  await page.getByRole("combobox", { name: "Kind" }).click();
  await page.getByRole("option", { name: "Domain" }).click();
  await page.getByRole("textbox", { name: "Name", exact: true }).fill(name);
  await pickType(page, extraType);
  await page.getByRole("combobox", { name: "Owner" }).fill("group:default/platform");
  const [created] = await Promise.all([
    page.waitForResponse(
      (r) => r.url().endsWith("/api/v1/files") && r.request().method() === "POST" && r.ok(),
    ),
    page.getByRole("button", { name: "Create" }).click(),
  ]);
  const fileId: number = (await created.json()).id;

  // Cleanup: the file, the appended type (Backspace removes the last TagsInput value —
  // ours), and the throwaway user.
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

  await page.goto("/types");
  await page.getByRole("button", { name: "Edit Domain" }).click();
  await dialog().getByRole("combobox", { name: "Allowed types" }).click();
  await page.keyboard.press("Backspace");
  await expect(dialog().getByText(extraType)).toHaveCount(0);
  await Promise.all([
    page.waitForResponse(
      (r) => /\/api\/v1\/entity-types\/\d+$/.test(r.url()) && r.request().method() === "PUT" && r.ok(),
    ),
    dialog().getByRole("button", { name: "Save" }).click(),
  ]);
  await expect(domainRow.getByText(extraType, { exact: true })).toHaveCount(0);

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
