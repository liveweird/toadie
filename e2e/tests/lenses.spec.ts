import { expect, login, openFilters, pickLifecycle, pickType, rowOperation, test, uniqueText } from "./helpers";

// The Lenses journey on two throwaway unique-named files: save the current filters as a
// named lens on Files, apply it on Hierarchy, Graph, and Errors (lenses are shared between
// the filterable views), rename it public, and delete it. Cross-user visibility rules are
// pinned server-side (LensTest); this journey is single-actor, and every assert anchors on
// the run's unique names.
test("a saved lens applies the same filters on Hierarchy, Files, Graph, and Errors", async ({ page }) => {
  await login(page);
  const fileA = uniqueText("e2e-lens-a");
  const fileB = uniqueText("e2e-lens-b");
  const lensName = uniqueText("e2e-lens");
  const renamed = uniqueText("e2e-lens-rn");

  // Two components — the lens's name filter will keep A and hide B.
  for (const name of [fileA, fileB]) {
    await page.goto("/files/new");
    await page.getByRole("textbox", { name: "Name", exact: true }).fill(name);
    await pickType(page, "service");
    await pickLifecycle(page, "production");
    await page.getByRole("combobox", { name: "Owner" }).fill("group:default/platform");
    await Promise.all([
      page.waitForResponse(
        (r) => r.url().endsWith("/api/v1/files") && r.request().method() === "POST" && r.ok(),
      ),
      page.getByRole("button", { name: "Create" }).click(),
    ]);
  }

  // Filter the Files list down to A and save that as a (private) lens.
  await page.goto("/files");
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(fileA);
  await expect(page.getByText(fileA, { exact: true })).toBeVisible();
  await expect(page.getByText(fileB, { exact: true })).toHaveCount(0);
  await page.getByRole("button", { name: "Lens actions" }).click();
  await page.getByRole("menuitem", { name: "Save as new lens…" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("Name").fill(lensName);
  await Promise.all([
    page.waitForResponse((r) => r.url().endsWith("/api/v1/lenses") && r.request().method() === "POST" && r.ok()),
    dialog.getByRole("button", { name: "Save" }).click(),
  ]);
  // The fresh lens is selected; diverging filters flip the Modified badge on.
  await expect(page.getByRole("combobox", { name: "Lens", exact: true })).toHaveValue(lensName);
  await page.getByLabel("Name", { exact: true }).fill(fileB);
  await expect(page.getByText("Modified", { exact: true })).toBeVisible();
  await expect(page.getByText(fileB, { exact: true })).toBeVisible();

  // Hierarchy: the same lens applies there (selection is per-view, the lens is shared).
  await page.goto("/");
  await page.getByRole("combobox", { name: "Lens", exact: true }).click();
  await page.getByRole("option", { name: lensName }).click();
  await expect(page.getByText(fileA, { exact: true })).toBeVisible();
  await expect(page.getByText(fileB, { exact: true })).toHaveCount(0);

  // Graph: applying the lens narrows the canvas to A's node.
  await page.goto("/graph");
  await page.getByRole("combobox", { name: "Lens", exact: true }).click();
  await page.getByRole("option", { name: lensName }).click();
  await expect(page.locator(`.react-flow__node[data-id="component:default/${fileA}"]`)).toBeVisible();
  await expect(page.locator(`.react-flow__node[data-id="component:default/${fileB}"]`)).toHaveCount(0);

  // Errors: the lens fills the same filter set (visible in the opened panel).
  await page.goto("/errors");
  await page.getByRole("combobox", { name: "Lens", exact: true }).click();
  await page.getByRole("option", { name: lensName }).click();
  await expect(page.getByText(/files checked/)).toBeVisible();
  await openFilters(page);
  await expect(page.getByLabel("Name", { exact: true })).toHaveValue(fileA);

  // Rename + flip to public: the picker regroups it under Public.
  await page.getByRole("button", { name: "Lens actions" }).click();
  await page.getByRole("menuitem", { name: "Rename / visibility…" }).click();
  await page.getByRole("dialog").getByLabel("Name").fill(renamed);
  await page.getByRole("dialog").getByRole("radio", { name: /Public/ }).click();
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "PUT" && r.ok()),
    page.getByRole("dialog").getByRole("button", { name: "Save" }).click(),
  ]);
  await expect(page.getByRole("combobox", { name: "Lens", exact: true })).toHaveValue(renamed);
  await page.getByRole("combobox", { name: "Lens", exact: true }).click();
  await expect(page.getByRole("option", { name: renamed })).toBeVisible();
  await expect(page.getByText("Public", { exact: true })).toBeVisible();
  await page.keyboard.press("Escape");

  // Delete the lens; the picker clears.
  await page.getByRole("button", { name: "Lens actions" }).click();
  await page.getByRole("menuitem", { name: "Delete" }).click();
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "DELETE" && r.ok()),
    page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click(),
  ]);
  await expect(page.getByRole("combobox", { name: "Lens", exact: true })).toHaveValue("");

  // Cleanup: both throwaway files go.
  for (const name of [fileA, fileB]) {
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
