import AxeBuilder from "@axe-core/playwright";
import { expect, login, openFilters, pickNamespace, rowOperation, runNamespace, test, uniqueText } from "./helpers";

// The quick-view drawer (v1.21.0): a file's summary/findings/YAML beside the Files list,
// addressed by ?file=<id>. Owns exactly one throwaway System in the render run namespace.
test("the quick-view drawer opens from a row, survives a reload, and hands over to the editor", async ({ page }) => {
  await login(page);
  const ns = runNamespace("render");
  const name = uniqueText("e2e-qv");

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

  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(name);
  await rowOperation(page, name, "Quick view");
  const drawer = page.getByRole("dialog");
  await expect(drawer.getByText(name, { exact: true })).toBeVisible();
  await expect(drawer.getByLabel("YAML preview")).toContainText(`name: ${name}`);
  await expect(page).toHaveURL(/\?.*file=\d+/);
  await expect(drawer.getByRole("button", { name: "Sync from source" })).toBeDisabled();

  // The drawer is part of the address: a reload brings it back.
  await page.reload();
  await expect(page.getByRole("dialog").getByText(name, { exact: true })).toBeVisible();

  // The open drawer is accessible — the accessibility spec's tags and its documented
  // colour-contrast waiver, scoped to the dialog (the overlay dims the page behind it).
  const scan = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
    .disableRules(["color-contrast"])
    .include('[role="dialog"]')
    .analyze();
  expect(scan.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(" ")).join(", ")}`)).toEqual([]);

  // Edit hands over to the editor; Back returns with the drawer open; close clears ?file.
  await page.getByRole("dialog").getByRole("link", { name: "Edit" }).click();
  await expect(page.getByRole("heading", { name: "Edit catalog file" })).toBeVisible();
  await page.goBack();
  await expect(page.getByRole("dialog").getByText(name, { exact: true })).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(page).not.toHaveURL(/file=/);

  // Cleanup.
  await rowOperation(page, name, "Delete");
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "DELETE" && r.ok()),
    page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click(),
  ]);
});
