import { expect, login, openFilters, test, uniqueText } from "./helpers";

// The cross-check journey on two throwaway unique-named files. Saves ENFORCE reference
// resolution now, so a dangling reference can only be MADE by deleting its target — the
// journey covers the editor's inline block, the deletion-created finding, and its repair.
// Every assert is anchored on the unique names, so other files' findings can never flake it.
test("an unresolved reference blocks saving; deleting a target creates the finding", async ({
  page,
}) => {
  await login(page);
  const source = uniqueText("e2e-xchk-src");
  const target = uniqueText("e2e-xchk-target");
  const ghost = uniqueText("e2e-xchk-ghost");

  // The target component first — references must resolve at save time.
  await page.goto("/catalog-files/new");
  await page.getByRole("textbox", { name: "Name", exact: true }).fill(target);
  await page.getByRole("combobox", { name: "Type" }).fill("service");
  await page.getByRole("combobox", { name: "Lifecycle" }).fill("production");
  await page.getByRole("combobox", { name: "Owner" }).fill("group:default/platform");
  await Promise.all([
    page.waitForResponse(
      (r) => r.url().endsWith("/api/v1/catalog-files") && r.request().method() === "POST" && r.ok(),
    ),
    page.getByRole("button", { name: "Create" }).click(),
  ]);

  // The source: a dangling dependsOn is flagged live AND blocks the submit client-side.
  await page.goto("/catalog-files/new");
  await page.getByRole("textbox", { name: "Name", exact: true }).fill(source);
  await page.getByRole("combobox", { name: "Type" }).fill("service");
  await page.getByRole("combobox", { name: "Lifecycle" }).fill("production");
  await page.getByRole("combobox", { name: "Owner" }).fill("group:default/platform");
  await page.getByRole("combobox", { name: "Depends on" }).fill(`component:${ghost}`);
  await page.keyboard.press("Enter");
  // Scoped to the alert — the TagsInput pill carries the same text.
  await expect(
    page.getByLabel("References that will block saving").getByText(`component:${ghost}`, { exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Create" }).click();
  await expect(page.getByText(`"component:${ghost}" does not resolve to a stored entity`)).toBeVisible();

  // Point the reference at the real target instead — the panel clears and the save lands.
  // (Backspace in the empty TagsInput removes the last pill.)
  await page.getByRole("combobox", { name: "Depends on" }).click();
  await page.keyboard.press("Backspace");
  await page.getByRole("combobox", { name: "Depends on" }).fill(`component:${target}`);
  await page.keyboard.press("Enter");
  await expect(page.getByText("All references resolve.")).toBeVisible();
  await Promise.all([
    page.waitForResponse(
      (r) => r.url().endsWith("/api/v1/catalog-files") && r.request().method() === "POST" && r.ok(),
    ),
    page.getByRole("button", { name: "Create" }).click(),
  ]);

  // Deleting the target is allowed — and creates the dangling reference.
  await page.goto("/catalog-files");
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(target);
  await page.getByRole("button", { name: `Delete ${target}` }).click();
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "DELETE" && r.ok()),
    page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click(),
  ]);

  // The workspace report shows the MISSING finding, linking back to the source file.
  await page.goto("/cross-check");
  await expect(page.getByText(`component:${target}`, { exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: `Edit ${source}` }).first()).toBeVisible();

  // Recreate the target — the finding disappears from a fresh report.
  await page.goto("/catalog-files/new");
  await page.getByRole("textbox", { name: "Name", exact: true }).fill(target);
  await page.getByRole("combobox", { name: "Type" }).fill("service");
  await page.getByRole("combobox", { name: "Lifecycle" }).fill("production");
  await page.getByRole("combobox", { name: "Owner" }).fill("group:default/platform");
  await Promise.all([
    page.waitForResponse(
      (r) => r.url().endsWith("/api/v1/catalog-files") && r.request().method() === "POST" && r.ok(),
    ),
    page.getByRole("button", { name: "Create" }).click(),
  ]);

  await page.goto("/cross-check");
  // The summary renders once the report has loaded; the unique ref must now be absent.
  await expect(page.getByText(/files checked/)).toBeVisible();
  await expect(page.getByText(`component:${target}`, { exact: true })).toHaveCount(0);

  // Cleanup: the source first (the target is still referenced — deletable anyway, but
  // removing the referrer first leaves no dangling residue behind).
  for (const name of [source, target]) {
    await page.goto("/catalog-files");
    await openFilters(page);
    await page.getByLabel("Name", { exact: true }).fill(name);
    await page.getByRole("button", { name: `Delete ${name}` }).click();
    await Promise.all([
      page.waitForResponse((r) => r.request().method() === "DELETE" && r.ok()),
      page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click(),
    ]);
  }
});
