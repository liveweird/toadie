import { expect, login, openFilters, pickLifecycle, pickType, rowOperation, test, uniqueText } from "./helpers";

// The Errors-report journey on two throwaway unique-named files. Saves are strict by DEFAULT
// but waivable: a soft rejection opens the Save-anyway modal, and a waived save lands the
// finding on the Errors report. The journey covers the modal's cancel and confirm
// paths, the report finding a waived save creates, its repair in the editor, and the
// deletion-created finding. Every assert is anchored on the unique names, so other files'
// findings can never flake it.
test("an unresolved reference asks for confirmation; saving anyway lands it on the Errors report", async ({
  page,
}) => {
  await login(page);
  const source = uniqueText("e2e-xchk-src");
  const target = uniqueText("e2e-xchk-target");
  const ghost = uniqueText("e2e-xchk-ghost");

  // The target component first — the repaired reference will point at it.
  await page.goto("/files/new");
  await page.getByRole("textbox", { name: "Name", exact: true }).fill(target);
  await pickType(page, "service");
  await pickLifecycle(page, "production");
  await page.getByRole("combobox", { name: "Owner" }).fill("group:default/platform");
  await Promise.all([
    page.waitForResponse(
      (r) => r.url().endsWith("/api/v1/files") && r.request().method() === "POST" && r.ok(),
    ),
    page.getByRole("button", { name: "Create" }).click(),
  ]);

  // The source: first a SELF-reference — flagged live, and the strict save opens the
  // Save-anyway modal (an entity may never reference itself, saved or not). Cancel keeps
  // the document unsaved.
  await page.goto("/files/new");
  await page.getByRole("textbox", { name: "Name", exact: true }).fill(source);
  await pickType(page, "service");
  await pickLifecycle(page, "production");
  await page.getByRole("combobox", { name: "Owner" }).fill("group:default/platform");
  await page.getByRole("combobox", { name: "Depends on" }).fill(`component:${source}`);
  await page.keyboard.press("Enter");
  await expect(
    page
      .getByLabel("Findings — saving will ask for confirmation")
      .getByText(`component:${source}`, { exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Create" }).click();
  const modal = page.getByRole("dialog");
  await expect(modal.getByText("Save with findings?")).toBeVisible();
  await expect(modal.getByText(`component:${source}`, { exact: true })).toBeVisible();
  await modal.getByRole("button", { name: "Cancel" }).click();
  await expect(page.getByRole("dialog")).toBeHidden();

  // Then a dangling dependsOn — this time SAVE ANYWAY: the waived save stores the file.
  await page.getByRole("combobox", { name: "Depends on" }).click();
  await page.keyboard.press("Backspace");
  await page.getByRole("combobox", { name: "Depends on" }).fill(`component:${ghost}`);
  await page.keyboard.press("Enter");
  // Scoped to the alert — the TagsInput pill carries the same text.
  await expect(
    page
      .getByLabel("Findings — saving will ask for confirmation")
      .getByText(`component:${ghost}`, { exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Create" }).click();
  await expect(page.getByRole("dialog").getByText(`component:${ghost}`, { exact: true })).toBeVisible();
  await Promise.all([
    page.waitForResponse(
      (r) => r.url().includes("allowInvalid=true") && r.request().method() === "POST" && r.ok(),
    ),
    page.getByRole("dialog").getByRole("button", { name: "Save anyway" }).click(),
  ]);
  await expect(page).toHaveURL(/\/files$/);

  // The workspace report shows the waived MISSING finding, linking back to the source file.
  await page.goto("/errors");
  await expect(page.getByText(`component:${ghost}`, { exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: `Edit ${source}` }).first()).toBeVisible();

  // Repair in the editor: point the reference at the real target — the panel clears and
  // the save goes through strict (no modal).
  await page.getByRole("link", { name: `Edit ${source}` }).first().click();
  await expect(page.getByRole("textbox", { name: "Name", exact: true })).toHaveValue(source);
  await page.getByRole("combobox", { name: "Depends on" }).click();
  await page.keyboard.press("Backspace");
  await page.getByRole("combobox", { name: "Depends on" }).fill(`component:${target}`);
  await page.keyboard.press("Enter");
  await expect(page.getByText("No findings — the document passes every check.")).toBeVisible();
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "PUT" && r.ok()),
    page.getByRole("button", { name: "Save" }).click(),
  ]);

  // Deleting the target is allowed — and creates the dangling reference.
  await page.goto("/files");
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(target);
  await rowOperation(page, target, "Delete");
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "DELETE" && r.ok()),
    page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click(),
  ]);

  // The workspace report shows the deletion-created MISSING finding.
  await page.goto("/errors");
  await expect(page.getByText(`component:${target}`, { exact: true })).toBeVisible();

  // The error-type pills filter client-side: References off hides the finding, back on
  // restores it (the Chip's checkbox input is visually hidden — click its label).
  await page.getByText("References", { exact: true }).click();
  await expect(page.getByText(`component:${target}`, { exact: true })).toHaveCount(0);
  await page.getByText("References", { exact: true }).click();
  await expect(page.getByText(`component:${target}`, { exact: true })).toBeVisible();

  // Recreate the target — the finding disappears from a fresh report.
  await page.goto("/files/new");
  await page.getByRole("textbox", { name: "Name", exact: true }).fill(target);
  await pickType(page, "service");
  await pickLifecycle(page, "production");
  await page.getByRole("combobox", { name: "Owner" }).fill("group:default/platform");
  await Promise.all([
    page.waitForResponse(
      (r) => r.url().endsWith("/api/v1/files") && r.request().method() === "POST" && r.ok(),
    ),
    page.getByRole("button", { name: "Create" }).click(),
  ]);

  await page.goto("/errors");
  // The summary renders once the report has loaded; the unique ref must now be absent.
  await expect(page.getByText(/files checked/i)).toBeVisible();
  await expect(page.getByText(`component:${target}`, { exact: true })).toHaveCount(0);

  // Cleanup: the source first (the target is still referenced — deletable anyway, but
  // removing the referrer first leaves no dangling residue behind).
  for (const name of [source, target]) {
    await page.goto("/files");
    await openFilters(page);
    await page.getByLabel("Name", { exact: true }).fill(name);
    await rowOperation(page, name, "Delete");
    await Promise.all([
      page.waitForResponse((r) => r.request().method() === "DELETE" && r.ok()),
      page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click(),
    ]);
  }
});
