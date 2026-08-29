import { expect, login, openFilters, pickLifecycle, pickNamespace, pickType, rowOperation, runNamespace, test, uniqueText } from "./helpers";

// The render-graph journey in this run's throwaway NAMESPACE (registered by global-setup —
// the form accepts only defined namespaces): the namespace filter isolates this spec's nodes
// from other files in the shared database, and per-attempt-unique node names keep retries
// honest against their own residue. Saves enforce reference resolution, so the MISSING node
// is MADE by deleting a stored target after its referrer saved.
test("the graph renders stored and missing nodes for a namespace", async ({ page }) => {
  await login(page);
  const ns = runNamespace("render");
  const a = uniqueText("e2e-rnode-a");
  const b = uniqueText("e2e-rnode-b");
  const ghost = uniqueText("e2e-rnode-ghost");

  // The targets first (B and the doomed ghost), then A depending on both.
  for (const [name, dependsOn] of [
    [b, []],
    [ghost, []],
    [a, [`component:${ns}/${b}`, `component:${ns}/${ghost}`]],
  ] as [string, string[]][]) {
    await page.goto("/files/new");
    await page.getByRole("textbox", { name: "Name", exact: true }).fill(name);
    await pickNamespace(page, ns);
    await pickType(page, "service");
    await pickLifecycle(page, "production");
    await page.getByRole("combobox", { name: "Owner" }).fill("group:default/platform");
    for (const ref of dependsOn) {
      await page.getByRole("combobox", { name: "Depends on" }).fill(ref);
      await page.keyboard.press("Enter");
    }
    await Promise.all([
      page.waitForResponse(
        (r) => r.url().endsWith("/api/v1/files") && r.request().method() === "POST" && r.ok(),
      ),
      page.getByRole("button", { name: "Create" }).click(),
    ]);
  }

  // Deleting the ghost leaves A's reference dangling — the graph's MISSING node.
  await page.goto("/files");
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(ghost);
  await rowOperation(page, ghost, "Delete");
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "DELETE" && r.ok()),
    page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click(),
  ]);

  // Render the namespace: A and B stored, the ghost missing, the (stored) owner group too.
  await page.goto("/graph");
  // The graph shares the Files list's filter panel now (collapsed by default).
  await openFilters(page);
  await pickNamespace(page, ns);
  await expect(page.getByText(a, { exact: true })).toBeVisible();
  await expect(page.getByText(b, { exact: true })).toBeVisible();
  await expect(page.getByText(ghost, { exact: true })).toBeVisible();
  await expect(page.getByText("platform", { exact: true })).toBeVisible();

  // Disabling the Depends-on relation prunes the orphaned VIRTUAL ghost node; stored nodes
  // (B, the platform owner) always stay. (The Chip's checkbox input is visually hidden —
  // click its label.)
  await page.getByText("Depends on", { exact: true }).click();
  await expect(page.getByText(ghost, { exact: true })).toHaveCount(0);
  await expect(page.getByText(b, { exact: true })).toBeVisible();
  await expect(page.getByText("platform", { exact: true })).toBeVisible();

  // Cleanup: delete both throwaway files.
  for (const name of [a, b]) {
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
