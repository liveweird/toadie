import { expect, login, openFilters, test, uniqueText } from "./helpers";

// The cross-check journey on two throwaway unique-named files. Every assert is anchored on
// the unique names, so other files' findings (residue included) can never flake it.
test("a dangling reference is flagged, then resolves once the target file exists", async ({
  page,
}) => {
  await login(page);
  const source = uniqueText("e2e-xchk-src");
  const ghost = uniqueText("e2e-xchk-ghost");

  // Create the source file depending on a component that doesn't exist yet.
  await page.goto("/catalog-files/new");
  await page.getByRole("textbox", { name: "Name", exact: true }).fill(source);
  await page.getByRole("combobox", { name: "Type" }).fill("service");
  await page.getByRole("combobox", { name: "Lifecycle" }).fill("production");
  await page.getByRole("textbox", { name: "Owner" }).fill("group:default/platform");
  await page.getByRole("combobox", { name: "Depends on" }).fill(`component:${ghost}`);
  await page.keyboard.press("Enter");

  // The live editor panel already flags the missing target (and the owner as unverifiable).
  // Scoped to the alert — the TagsInput pill carries the same text.
  await expect(
    page.getByLabel("Unresolved references").getByText(`component:${ghost}`, { exact: true }),
  ).toBeVisible();
  await Promise.all([
    page.waitForResponse(
      (r) => r.url().endsWith("/api/v1/catalog-files") && r.request().method() === "POST" && r.ok(),
    ),
    page.getByRole("button", { name: "Create" }).click(),
  ]);

  // The workspace report shows the MISSING finding, linking back to the source file.
  await page.goto("/cross-check");
  const findingRef = page.getByText(`component:${ghost}`, { exact: true });
  await expect(findingRef).toBeVisible();
  await expect(page.getByRole("link", { name: `Edit ${source}` }).first()).toBeVisible();

  // Create the missing target — the finding disappears from a fresh report.
  await page.goto("/catalog-files/new");
  await page.getByRole("textbox", { name: "Name", exact: true }).fill(ghost);
  await page.getByRole("combobox", { name: "Type" }).fill("service");
  await page.getByRole("combobox", { name: "Lifecycle" }).fill("production");
  await page.getByRole("textbox", { name: "Owner" }).fill("group:default/platform");
  await Promise.all([
    page.waitForResponse(
      (r) => r.url().endsWith("/api/v1/catalog-files") && r.request().method() === "POST" && r.ok(),
    ),
    page.getByRole("button", { name: "Create" }).click(),
  ]);

  await page.goto("/cross-check");
  // The summary renders once the report has loaded; the unique ref must now be absent.
  await expect(page.getByText(/files checked/)).toBeVisible();
  await expect(page.getByText(`component:${ghost}`, { exact: true })).toHaveCount(0);

  // Cleanup: delete both throwaway files from the filtered list.
  for (const name of [source, ghost]) {
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
