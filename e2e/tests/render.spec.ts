import { expect, login, openFilters, test, uniqueText } from "./helpers";

// The render-graph journey in a throwaway unique NAMESPACE, so the namespace filter isolates
// this spec's nodes completely from other files (and residue) in the shared database.
test("the graph renders stored, missing, and external nodes for a namespace", async ({ page }) => {
  await login(page);
  const ns = uniqueText("e2e-rns");
  const a = uniqueText("e2e-rnode-a");
  const b = uniqueText("e2e-rnode-b");
  const ghost = uniqueText("e2e-rnode-ghost");

  // B first (the resolvable target), then A depending on B and on a missing component.
  for (const [name, dependsOn] of [
    [b, []],
    [a, [`component:${ns}/${b}`, `component:${ns}/${ghost}`]],
  ] as [string, string[]][]) {
    await page.goto("/catalog-files/new");
    await page.getByRole("textbox", { name: "Name", exact: true }).fill(name);
    await page.getByRole("textbox", { name: "Namespace" }).fill(ns);
    await page.getByRole("combobox", { name: "Type" }).fill("service");
    await page.getByRole("combobox", { name: "Lifecycle" }).fill("production");
    await page.getByRole("combobox", { name: "Owner" }).fill("group:default/platform");
    for (const ref of dependsOn) {
      await page.getByRole("combobox", { name: "Depends on" }).fill(ref);
      await page.keyboard.press("Enter");
    }
    await Promise.all([
      page.waitForResponse(
        (r) => r.url().endsWith("/api/v1/catalog-files") && r.request().method() === "POST" && r.ok(),
      ),
      page.getByRole("button", { name: "Create" }).click(),
    ]);
  }

  // Render the namespace: A and B are stored nodes, the ghost a missing one, the owner external.
  await page.goto("/render");
  await page.getByLabel("Namespace", { exact: true }).fill(ns);
  await expect(page.getByText(a, { exact: true })).toBeVisible();
  await expect(page.getByText(b, { exact: true })).toBeVisible();
  await expect(page.getByText(ghost, { exact: true })).toBeVisible();
  await expect(page.getByText("platform", { exact: true })).toBeVisible();

  // Disabling the Owner relation prunes the external owner node; the rest stay.
  // (The Chip's checkbox input is visually hidden — click its label.)
  await page.getByText("Owner", { exact: true }).click();
  await expect(page.getByText("platform", { exact: true })).toHaveCount(0);
  await expect(page.getByText(ghost, { exact: true })).toBeVisible();

  // Cleanup: delete both throwaway files.
  for (const name of [a, b]) {
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
