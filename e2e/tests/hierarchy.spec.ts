import { expect, login, openFilters, pickLifecycle, pickNamespace, pickType, rowOperation, runNamespace, test, uniqueText } from "./helpers";

// The Hierarchy journey in this run's throwaway namespace (parallel-safe): seed a
// System ⊃ Component ⊃ subcomponent chain through the editor, verify the nesting and
// collapse behavior at /, exercise the row Operations (Download, Delete), and watch a
// deleted parent turn into a MISSING placeholder. Owns only its own e2e-hier-* files.
test("the hierarchy nests the containment chain and carries the file operations", async ({
  page,
}) => {
  await login(page);
  const ns = runNamespace("hierarchy");
  const sys = uniqueText("e2e-hier-sys");
  const core = uniqueText("e2e-hier-core");
  const worker = uniqueText("e2e-hier-worker");

  const create = async () =>
    Promise.all([
      page.waitForResponse(
        (r) => r.url().endsWith("/api/v1/files") && r.request().method() === "POST" && r.ok(),
      ),
      page.getByRole("button", { name: "Create" }).click(),
    ]);

  // The System (type is optional for Systems — left blank).
  await page.goto("/files/new");
  await page.getByRole("combobox", { name: "Kind" }).click();
  await page.getByRole("option", { name: "System", exact: true }).click();
  await page.getByRole("textbox", { name: "Name", exact: true }).fill(sys);
  await pickNamespace(page, ns);
  await page.getByRole("combobox", { name: "Owner" }).fill("group:default/platform");
  await create();

  // A Component in the System.
  await page.goto("/files/new");
  await page.getByRole("textbox", { name: "Name", exact: true }).fill(core);
  await pickNamespace(page, ns);
  await pickType(page, "service");
  await pickLifecycle(page, "production");
  await page.getByRole("combobox", { name: "Owner" }).fill("group:default/platform");
  await page.getByRole("combobox", { name: "System" }).fill(`system:${ns}/${sys}`);
  await create();

  // A subcomponent of that Component (also in the System — most-specific placement
  // must nest it under the parent component, not directly under the system).
  await page.goto("/files/new");
  await page.getByRole("textbox", { name: "Name", exact: true }).fill(worker);
  await pickNamespace(page, ns);
  await pickType(page, "service");
  await pickLifecycle(page, "production");
  await page.getByRole("combobox", { name: "Owner" }).fill("group:default/platform");
  await page.getByRole("combobox", { name: "System" }).fill(`system:${ns}/${sys}`);
  await page.getByRole("combobox", { name: "Subcomponent of" }).fill(`component:${ns}/${core}`);
  await create();

  // The tree at /, scoped to the run namespace: all three visible, nested.
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Hierarchy" })).toBeVisible();
  // The Files list's filter panel (collapsed by default) scopes the tree to the run namespace.
  await openFilters(page);
  await pickNamespace(page, ns);
  await expect(page.getByText(sys, { exact: true })).toBeVisible();
  await expect(page.getByText(core, { exact: true })).toBeVisible();
  await expect(page.getByText(worker, { exact: true })).toBeVisible();

  // The filters select what is SHOWN: with Type "service" the components stay, but the
  // type-less System does NOT — so the components lose their container and sit flat.
  await pickType(page, "service");
  await expect(page.getByText(worker, { exact: true })).toBeVisible();
  await expect(page.getByText(sys, { exact: true })).toHaveCount(0);
  // No service-typed file is experimental either, so the tree empties entirely;
  // clearing the filters restores the chain.
  await pickLifecycle(page, "experimental");
  await expect(page.getByText(core, { exact: true })).toHaveCount(0);
  await expect(page.getByText(sys, { exact: true })).toHaveCount(0);
  await page.getByLabel("Clear lifecycle filter").click();
  await page.getByLabel("Clear type filter").click();
  await expect(page.getByText(worker, { exact: true })).toBeVisible();

  // Collapsing the parent COMPONENT hides only the subcomponent…
  await page.getByRole("button", { name: `Toggle children of ${core}` }).click();
  await expect(page.getByText(worker, { exact: true })).toHaveCount(0);
  await expect(page.getByText(core, { exact: true })).toBeVisible();
  await page.getByRole("button", { name: `Toggle children of ${core}` }).click();
  // …while collapsing the SYSTEM hides the whole chain.
  await page.getByRole("button", { name: `Toggle children of ${sys}` }).click();
  await expect(page.getByText(core, { exact: true })).toHaveCount(0);
  await expect(page.getByText(worker, { exact: true })).toHaveCount(0);
  await page.getByRole("button", { name: `Toggle children of ${sys}` }).click();

  // Download straight from a tree row's Operations menu.
  const [download] = await Promise.all([
    page.waitForEvent("download"),
    rowOperation(page, worker, "Export as YAML"),
  ]);
  expect(download.suggestedFilename()).toBe("catalog-info.yaml");
  // Let the download's in-flight state settle (the trigger renders as loading) — the next
  // menu interaction must not race that re-render, or it can land on the wrong row's menu.
  await expect(page.getByRole("button", { name: `Operations for ${worker}` })).toBeEnabled();

  // Deleting the System from the tree leaves a MISSING placeholder with the components
  // still nested under it (dangling references appear through deletion by design).
  await rowOperation(page, sys, "Delete");
  await expect(page.getByRole("dialog")).toContainText(sys);
  await Promise.all([
    page.waitForResponse(
      (r) => /\/api\/v1\/files\/\d+$/.test(r.url()) && r.request().method() === "DELETE" && r.ok(),
    ),
    page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click(),
  ]);
  await expect(page.getByText(sys, { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: `Operations for ${sys}` })).toHaveCount(0);
  await expect(page.getByText(core, { exact: true })).toBeVisible();

  // Cleanup: the subcomponent, then the component — the placeholder vanishes with them.
  for (const name of [worker, core]) {
    await rowOperation(page, name, "Delete");
    await expect(page.getByRole("dialog")).toContainText(name);
    await Promise.all([
      page.waitForResponse(
        (r) => /\/api\/v1\/files\/\d+$/.test(r.url()) && r.request().method() === "DELETE" && r.ok(),
      ),
      page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click(),
    ]);
  }
  await expect(page.getByText(core, { exact: true })).toHaveCount(0);
  await expect(page.getByText(sys, { exact: true })).toHaveCount(0);
});
