import { createUserViaUi, expect, login, openFilters, pickLifecycle, pickNamespace, pickType, rowOperation, runNamespace, test, uniqueText } from "./helpers";

type LayoutDocument = {
  mode: "auto" | "manual";
  positions: Record<string, { x: number; y: number }>;
  collapsed: string[];
};

// The render-graph journey across this run's two throwaway NAMESPACES (registered by
// global-setup — the form accepts only defined namespaces). Isolation runs off a per-attempt
// unique NAME stem shared by all three nodes, so the graph's name filter selects exactly this
// attempt's entities and retries never inherit their own residue; the second namespace is what
// puts two namespace frames on one canvas, now that a neighbour outside the filter is no longer
// drawn. Saves enforce reference resolution, so the MISSING node is MADE by deleting a stored
// target after its referrer saved.
test("the graph renders stored and missing nodes for a namespace", async ({ page }) => {
  // ~200 actions measured at 60.7s on the 2-vCPU CI runner vs 19.6s locally — a long journey,
  // not a slow assertion; per-`expect` timeouts (10s, playwright.config.ts) stay untouched.
  test.slow();
  await login(page);
  const adminToken = await page.evaluate(() => localStorage.getItem("toadie.auth.token"));
  expect(adminToken !== null, "the admin login must provide cleanup authorization").toBe(true);
  const user = await createUserViaUi(page, "E2E Render Graph");
  const layoutPath = `/api/v1/users/${user.id}/graph-layout`;
  // Names computed up front (pure, no await) and ids captured at creation below, both kept
  // reachable from `finally`: cleanup deletes A/B/System via the API instead of round-tripping
  // the Files UI (see the cleanup block below).
  const ns = runNamespace("render");
  const nsAlt = runNamespace("renderAlt");
  const stem = uniqueText("e2e-rnode");
  const a = `${stem}-a`;
  const b = `${stem}-b`;
  const ghost = `${stem}-ghost`;
  const sys = `${stem}-sys`;
  const fileIds: Record<string, string> = {};
  try {
    await login(page, user.email, user.password);
    const created = () =>
      page.waitForResponse(
        (r) => r.url().endsWith("/api/v1/files") && r.request().method() === "POST" && r.ok(),
      );

    // The System first (type is optional for Systems — left blank): A will belong to it, which
    // is what makes it collapsible on the canvas.
    await page.goto("/files/new");
    await page.getByRole("combobox", { name: "Kind" }).click();
    await page.getByRole("option", { name: "System", exact: true }).click();
    await page.getByRole("textbox", { name: "Name", exact: true }).fill(sys);
    await pickNamespace(page, ns);
    await page.getByRole("combobox", { name: "Owner" }).fill("group:default/platform");
    const [sysCreated] = await Promise.all([created(), page.getByRole("button", { name: "Create" }).click()]);
    fileIds[sys] = (await sysCreated.json()).id;

    // Then the targets (B in the OTHER namespace, the doomed ghost here), then A — in the
    // System, depending on both — a cross-namespace edge is what makes the canvas span two
    // frames.
    for (const [name, namespace, dependsOn, system] of [
      [b, nsAlt, [], null],
      [ghost, ns, [], null],
      [a, ns, [`component:${nsAlt}/${b}`, `component:${ns}/${ghost}`], `system:${ns}/${sys}`],
    ] as [string, string, string[], string | null][]) {
      await page.goto("/files/new");
      await page.getByRole("textbox", { name: "Name", exact: true }).fill(name);
      await pickNamespace(page, namespace);
      await pickType(page, "service");
      await pickLifecycle(page, "production");
      await page.getByRole("combobox", { name: "Owner" }).fill("group:default/platform");
      if (system) await page.getByRole("combobox", { name: "System" }).fill(system);
      for (const ref of dependsOn) {
        await page.getByRole("combobox", { name: "Depends on" }).fill(ref);
        await page.keyboard.press("Enter");
      }
      const [fileCreated] = await Promise.all([created(), page.getByRole("button", { name: "Create" }).click()]);
      fileIds[name] = (await fileCreated.json()).id;
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

    // Render this attempt's entities by NAME: A and B stored, the ghost missing. The shared
    // owner group is NOT drawn — it does not match the filter, and the filters now select what
    // is shown; a MISSING node is judged on the same identity, so the ghost's name lets it in.
    await page.goto("/graph");
    // The graph shares the Files list's filter panel now (collapsed by default).
    await openFilters(page);
    await page.getByLabel("Name", { exact: true }).fill(stem);
    await expect(page.getByText(a, { exact: true })).toBeVisible();
    await expect(page.getByText(b, { exact: true })).toBeVisible();
    await expect(page.getByText(ghost, { exact: true })).toBeVisible();
    await expect(page.getByText(sys, { exact: true })).toBeVisible();
    await expect(page.getByText("platform", { exact: true })).toHaveCount(0);
    // The node's second line is spec.type, not the namespace: A and B were created as
    // `service`, and the namespace never appears on a node face.
    await expect(page.locator(`.react-flow__node[data-id="component:${ns}/${a}"]`)).toContainText("service");
    // Scoped to the canvas on purpose: the namespace still appears in the filter panel's Select.
    await expect(page.locator(".react-flow__node").filter({ hasText: ns })).toHaveCount(0);
    // Two namespaces are on screen — A and the ghost here, B next door — so each gets a frame
    // behind its nodes. The frames live in React Flow's viewport portal, not among the nodes,
    // which is why the assertion above still holds.
    const frames = page.locator(".react-flow__viewport-portal");
    await expect(frames.getByText(ns, { exact: true })).toBeVisible();
    await expect(frames.getByText(nsAlt, { exact: true })).toBeVisible();

    // Disabling the Depends-on relation prunes the orphaned MISSING ghost — its only edge was
    // what made it knowable — while the stored nodes stay: a relation chip governs relations,
    // not which entities are shown. (The Chip's checkbox input is visually hidden — click its
    // label.)
    await page.getByText("Depends on", { exact: true }).click();
    await expect(page.getByText(ghost, { exact: true })).toHaveCount(0);
    await expect(page.getByText(b, { exact: true })).toBeVisible();
    await expect(page.getByText(a, { exact: true })).toBeVisible();

    // Manual layout: node canvas positions read via the React Flow wrapper's translate()
    // transform — viewport-independent, unlike boundingBox (fitView rescales after reload).
    const nodeB = page.locator(`.react-flow__node[data-id="component:${nsAlt}/${b}"]`);
    const transformOfB = () => nodeB.evaluate((el) => (el as HTMLElement).style.transform);
    const layoutPut = (matches: (document: LayoutDocument) => boolean) =>
      page.waitForResponse(
        (response) => response.url().endsWith(layoutPath) &&
          response.request().method() === "PUT" &&
          matches(response.request().postDataJSON() as LayoutDocument),
      );

    // Folding. Depends-on goes back on first — the folded edges are what this step watches.
    // Only the System has something beneath it (A), so it alone offers a fold toggle; the
    // leaves do not. Collapsing hides A and re-attributes A's two dependsOn relations to the
    // System: two DASHED edges leave it, one to B and one to the ghost.
    await page.getByText("Depends on", { exact: true }).click();
    await expect(page.getByText(ghost, { exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: `Collapse ${a}` })).toHaveCount(0);
    await expect(page.locator(".react-flow__node")).toHaveCount(4);
    await expect(page.locator(".react-flow__edge-path[style*='stroke-dasharray']")).toHaveCount(0);
    const [collapsedSaved] = await Promise.all([
      layoutPut((document) => document.collapsed.includes(`system:${ns}/${sys}`)),
      page.getByRole("button", { name: `Collapse ${sys}` }).click(),
    ]);
    expect(collapsedSaved.status()).toBe(204);
    await expect(page.getByText(a, { exact: true })).toHaveCount(0);
    await expect(page.locator(".react-flow__node")).toHaveCount(3);
    await expect(page.getByRole("button", { name: `Expand ${sys} (1 hidden)` })).toBeVisible();
    await expect(page.locator(".react-flow__edge").filter({ hasText: "dependsOn" })).toHaveCount(2);
    await expect(page.locator(".react-flow__edge-path[style*='stroke-dasharray']")).toHaveCount(2);

    // Reload: the collapsed set lives in the per-user layout document, server-side.
    await page.reload();
    await expect(page.getByRole("button", { name: `Expand ${sys} (1 hidden)` })).toBeVisible();
    await expect(page.getByText(a, { exact: true })).toHaveCount(0);

    // Expand all brings A back with its own solid edges, and clears the stored list.
    const [expandedSaved] = await Promise.all([
      layoutPut((document) => document.collapsed.length === 0),
      page.getByRole("button", { name: "Expand all" }).click(),
    ]);
    expect(expandedSaved.status()).toBe(204);
    await expect(page.getByText(a, { exact: true })).toBeVisible();
    await expect(page.locator(".react-flow__edge-path[style*='stroke-dasharray']")).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Expand all" })).toHaveCount(0);

    // Switch to Manual (the SegmentedControl input is visually hidden — click its label).
    // Keep its acknowledgement separate from the drag save. CI proved the mode response can
    // arrive during the gesture, before the debounced position-bearing request even starts.
    const manualSaved = layoutPut(
      (document) => document.mode === "manual" && Object.keys(document.positions).length === 0,
    );
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
    const nodeBId = `component:${nsAlt}/${b}`;
    const [positionSaved] = await Promise.all([
      layoutPut((document) => document.positions[nodeBId] != null),
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
    expect((await manualSaved).status()).toBe(204);
    expect(positionSaved.status()).toBe(204);
    const draggedTransform = await transformOfB();
    expect(draggedTransform).not.toBe(dagreTransform);
    await expect(page).toHaveURL(/\/graph$/);

    // Reload: the filter persists locally, the dragged position SERVER-side per user.
    await page.reload();
    await expect(page.getByText(b, { exact: true })).toBeVisible();
    await expect.poll(transformOfB).toBe(draggedTransform);

    // Reset layout drops the stored positions — B returns to a computed dagre spot.
    const [resetSaved] = await Promise.all([
      layoutPut((document) => document.mode === "manual" && Object.keys(document.positions).length === 0),
      page.getByRole("button", { name: "Reset layout" }).click(),
    ]);
    expect(resetSaved.status()).toBe(204);
    await expect.poll(transformOfB).not.toBe(draggedTransform);

    // Back to Auto — the throwaway user's layout document ends pristine (auto, no positions,
    // nothing collapsed — Expand all above).
    const [autoSaved] = await Promise.all([
      layoutPut((document) => document.mode === "auto" && Object.keys(document.positions).length === 0),
      page.getByText("Auto", { exact: true }).click(),
    ]);
    expect(autoSaved.status()).toBe(204);
    await expect(page.getByRole("button", { name: "Reset layout" })).toHaveCount(0);
  } finally {
    // Cleanup via the API, not the Files UI round trip: this is a ~200-action journey
    // (test.slow() above), and the UI-delete flow itself is already covered as BEHAVIOUR by
    // every other catalog-file spec's `rowOperation(page, name, "Delete")` case. Deleting a
    // referenced entity is allowed (references just go dangling), so order doesn't matter.
    for (const name of [a, b, sys]) {
      const id = fileIds[name];
      if (id === undefined) continue;
      const deletedFile = await page.request.delete(`/api/v1/files/${id}`, {
        headers: { Authorization: `Bearer ${adminToken}` },
      });
      expect(deletedFile.status(), `cleanup: delete ${name}`).toBe(204);
    }
    const deletedUser = await page.request.delete(`/api/v1/users/${user.id}`, {
      headers: { Authorization: `Bearer ${adminToken}` },
    });
    expect(deletedUser.status()).toBe(204);
  }
});
