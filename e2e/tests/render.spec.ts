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
  // The node's second line is spec.type, not the namespace: A and B were created as
  // `service`, and the namespace (this run's `ns`) never appears on a node face.
  await expect(page.locator(`.react-flow__node[data-id="component:${ns}/${a}"]`)).toContainText("service");
  // Scoped to the canvas on purpose: the namespace still appears in the filter panel's Select.
  await expect(page.locator(".react-flow__node").filter({ hasText: ns })).toHaveCount(0);
  // Two namespaces are on screen — this run's, and `default` (the shared platform owner) —
  // so each gets a frame behind its nodes. The frames live in React Flow's viewport portal,
  // not among the nodes, which is why the assertion above still holds.
  const frames = page.locator(".react-flow__viewport-portal");
  await expect(frames.getByText(ns, { exact: true })).toBeVisible();
  await expect(frames.getByText("default", { exact: true })).toBeVisible();

  // Disabling the Depends-on relation prunes the orphaned VIRTUAL ghost node; stored nodes
  // (B, the platform owner) always stay. (The Chip's checkbox input is visually hidden —
  // click its label.)
  await page.getByText("Depends on", { exact: true }).click();
  await expect(page.getByText(ghost, { exact: true })).toHaveCount(0);
  await expect(page.getByText(b, { exact: true })).toBeVisible();
  await expect(page.getByText("platform", { exact: true })).toBeVisible();

  // Manual layout: node canvas positions read via the React Flow wrapper's translate()
  // transform — viewport-independent, unlike boundingBox (fitView rescales after reload).
  const nodeB = page.locator(`.react-flow__node[data-id="component:${ns}/${b}"]`);
  const transformOfB = () => nodeB.evaluate((el) => (el as HTMLElement).style.transform);
  const layoutPut = () =>
    page.waitForResponse(
      (r) => r.url().includes("/graph-layout") && r.request().method() === "PUT" && r.ok(),
    );

  // Switch to Manual (the SegmentedControl input is visually hidden — click its label).
  // Assert the radio state rather than awaiting the mode PUT: a retry inheriting a
  // manual-mode residue would fire no change event (and so no PUT) on this click.
  await page.getByText("Manual", { exact: true }).click();
  await expect(page.getByRole("radio", { name: "Manual" })).toBeChecked();
  const dagreTransform = await transformOfB();

  // Drag B and wait for the debounced position save; the drag must NOT navigate to the
  // editor (a stored node's click is swallowed when it rides a drag gesture). hover()
  // scrolls the node into view, waits for it to be stable, and parks the pointer on it —
  // page.mouse alone would neither scroll nor wait for the canvas to settle.
  await nodeB.hover();
  const box = (await nodeB.boundingBox())!;
  const cx = box.x + box.width / 2;
  const cy = box.y + box.height / 2;
  await Promise.all([
    layoutPut(),
    (async () => {
      await page.mouse.down();
      await page.mouse.move(cx + 140, cy - 60, { steps: 8 });
      // Mid-gesture the canvas must stay painted (the v1.7.1 flicker fix: nodes vanished
      // until drag end while the controlled node array was replaced every frame).
      await expect(nodeB).toBeVisible();
      await expect(page.getByText(a, { exact: true })).toBeVisible();
      await page.mouse.up();
    })(),
  ]);
  const draggedTransform = await transformOfB();
  expect(draggedTransform).not.toBe(dagreTransform);
  await expect(page).toHaveURL(/\/graph$/);

  // Reload: the filter persists locally, the dragged position SERVER-side per user.
  await page.reload();
  await expect(page.getByText(b, { exact: true })).toBeVisible();
  await expect.poll(transformOfB).toBe(draggedTransform);

  // Reset layout drops the stored positions — B returns to a computed dagre spot.
  await Promise.all([layoutPut(), page.getByRole("button", { name: "Reset layout" }).click()]);
  await expect.poll(transformOfB).not.toBe(draggedTransform);

  // Back to Auto — the seed admin's layout document ends the run pristine (auto, no
  // positions), so parallel/later runs start from the default.
  await Promise.all([layoutPut(), page.getByText("Auto", { exact: true }).click()]);
  await expect(page.getByRole("button", { name: "Reset layout" })).toHaveCount(0);

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
