import { createUserViaUi, expect, login, openFilters, test, uniqueText } from "./helpers";

type EntityLayoutDocument = {
  mode: "auto" | "manual";
  positions: Record<string, { x: number; y: number }>;
  collapsed: string[];
};

// The Entity graph (Port migration phase 3): two throwaway blueprints seeded via the API — a
// parent blueprint carrying a `peer` MANY self-relation, and a child blueprint carrying a
// single `parent` relation to it flagged as the `hierarchyRelation` — plus two parent and two
// child entities (p1.peer -> [p2]; c1/c2.parent -> p1). A throwaway user owns the page's
// per-user layout document (`/users/{id}/entity-graph-layout`), never the seed admin's. Owns
// only its own `e2e-eg-*` blueprints/entities and that one throwaway user.
test("the entity graph filters by blueprint, folds hierarchy, and persists a manual layout", async ({
  page,
}) => {
  await login(page);
  const adminToken = await page.evaluate(() => localStorage.getItem("toadie.auth.token"));
  expect(adminToken !== null, "the admin login must provide setup/cleanup authorization").toBe(true);
  const authHeaders = { Authorization: `Bearer ${adminToken}` };

  const parentBp = uniqueText("e2e-eg-bp-parent");
  const childBp = uniqueText("e2e-eg-bp-child");
  const parentTitle = "E2E EG Parent Blueprint";
  const childTitle = "E2E EG Child Blueprint";
  const p1Id = uniqueText("e2e-eg-p1");
  const p2Id = uniqueText("e2e-eg-p2");
  const c1Id = uniqueText("e2e-eg-c1");
  const c2Id = uniqueText("e2e-eg-c2");
  const p1Title = "E2E EG Parent One";
  const p2Title = "E2E EG Parent Two";
  const c1Title = "E2E EG Child One";
  const c2Title = "E2E EG Child Two";

  let user: Awaited<ReturnType<typeof createUserViaUi>> | undefined;
  let p1EntityId: number | undefined;
  let p2EntityId: number | undefined;
  let c1EntityId: number | undefined;
  let c2EntityId: number | undefined;
  let childBpId: number | undefined;
  let parentBpId: number | undefined;

  try {
    // 0. Seed the two throwaway blueprints via the API: a `peer` MANY self-relation on the
    // parent, and a single `parent` relation on the child flagged as its `hierarchyRelation`.
    const parentBpResp = await page.request.post("/api/v1/blueprints", {
      headers: authHeaders,
      data: {
        identifier: parentBp,
        title: parentTitle,
        schema: { properties: {}, required: [] },
        relations: {
          peer: { title: "Peer", target: parentBp, required: false, many: true },
        },
      },
    });
    expect(parentBpResp.status()).toBe(201);
    parentBpId = (await parentBpResp.json()).id;

    const childBpResp = await page.request.post("/api/v1/blueprints", {
      headers: authHeaders,
      data: {
        identifier: childBp,
        title: childTitle,
        schema: { properties: {}, required: [] },
        relations: {
          parent: { title: "Parent", target: parentBp, required: false, many: false },
        },
        hierarchyRelation: "parent",
      },
    });
    expect(childBpResp.status()).toBe(201);
    childBpId = (await childBpResp.json()).id;

    // 1. Seed the entities: p2 first (p1's peer target must already exist), then p1, then the
    // two children pointing their `parent` relation at p1.
    const p2Resp = await page.request.post("/api/v1/entities", {
      headers: authHeaders,
      data: { blueprint: parentBp, identifier: p2Id, title: p2Title },
    });
    expect(p2Resp.status()).toBe(201);
    p2EntityId = (await p2Resp.json()).id;

    const p1Resp = await page.request.post("/api/v1/entities", {
      headers: authHeaders,
      data: { blueprint: parentBp, identifier: p1Id, title: p1Title, relations: { peer: [p2Id] } },
    });
    expect(p1Resp.status()).toBe(201);
    p1EntityId = (await p1Resp.json()).id;

    const c1Resp = await page.request.post("/api/v1/entities", {
      headers: authHeaders,
      data: { blueprint: childBp, identifier: c1Id, title: c1Title, relations: { parent: p1Id } },
    });
    expect(c1Resp.status()).toBe(201);
    c1EntityId = (await c1Resp.json()).id;

    const c2Resp = await page.request.post("/api/v1/entities", {
      headers: authHeaders,
      data: { blueprint: childBp, identifier: c2Id, title: c2Title, relations: { parent: p1Id } },
    });
    expect(c2Resp.status()).toBe(201);
    c2EntityId = (await c2Resp.json()).id;

    // 2. A throwaway user owns the Entity graph's per-user layout document.
    user = await createUserViaUi(page, "E2E Entity Graph");
    const layoutPath = `/api/v1/users/${user.id}/entity-graph-layout`;

    await login(page, user.email, user.password);
    await page.goto("/entity-graph");
    await expect(page.getByRole("heading", { name: "Entity graph" })).toBeVisible();

    // 3. Filter to the two throwaway blueprints — the workspace may also carry other specs'
    // blueprints/entities (running in parallel), so the unfiltered graph is not isolated.
    await openFilters(page);
    const blueprintFilter = page.getByRole("combobox", { name: "Blueprints" });
    await blueprintFilter.click();
    await blueprintFilter.fill(parentBp);
    await page.getByRole("option", { name: parentBp, exact: true }).click();
    await blueprintFilter.fill(childBp);
    await page.getByRole("option", { name: childBp, exact: true }).click();
    // The MultiSelect stays open for further picks; its dropdown overlaps the canvas below and
    // would otherwise intercept the chip/toggle/drag interactions that follow (the
    // annotations.spec.ts idiom: Escape closes it without touching the two selections just made).
    await page.keyboard.press("Escape");

    // 4. Four nodes, two blueprint frames (labelled by blueprint TITLE).
    await expect(page.getByText(p1Id, { exact: true })).toBeVisible();
    await expect(page.getByText(p2Id, { exact: true })).toBeVisible();
    await expect(page.getByText(c1Id, { exact: true })).toBeVisible();
    await expect(page.getByText(c2Id, { exact: true })).toBeVisible();
    const frames = page.locator(".react-flow__viewport-portal");
    await expect(frames.getByText(parentTitle, { exact: true })).toBeVisible();
    await expect(frames.getByText(childTitle, { exact: true })).toBeVisible();

    // 5. Three edges total: p1--peer-->p2, c1--parent-->p1, c2--parent-->p1. Toggling the
    // "peer" relation chip off drops the p1->p2 edge; toggling it back restores it. The relation
    // identifier "peer" also labels the p1->p2 edge on the canvas, so scope the click to the
    // "Relations" chip group (a strict-mode ambiguity otherwise) — and click the visible label,
    // not the Chip's underlying checkbox input, which is visually hidden and not clickable.
    await expect(page.locator(".react-flow__edge")).toHaveCount(3);
    const relationsGroup = page.getByRole("group", { name: "Relations" });
    const peerChip = relationsGroup.getByText("peer", { exact: true });
    await peerChip.click();
    await expect(page.locator(".react-flow__edge")).toHaveCount(2);
    await peerChip.click();
    await expect(page.locator(".react-flow__edge")).toHaveCount(3);

    // 6. Collapse p1 via its fold toggle: c1/c2 (its hierarchy-relation descendants) hide, and
    // the pill names the hidden count.
    await expect(page.locator(".react-flow__node")).toHaveCount(4);
    await page.getByRole("button", { name: `Collapse ${p1Title}` }).click();
    await expect(page.getByText(c1Id, { exact: true })).toHaveCount(0);
    await expect(page.getByText(c2Id, { exact: true })).toHaveCount(0);
    await expect(page.locator(".react-flow__node")).toHaveCount(2);
    await expect(page.getByRole("button", { name: `Expand ${p1Title} (2 hidden)` })).toBeVisible();

    // Expand again — p1's descendants come back.
    await page.getByRole("button", { name: `Expand ${p1Title} (2 hidden)` }).click();
    await expect(page.getByText(c1Id, { exact: true })).toBeVisible();
    await expect(page.getByText(c2Id, { exact: true })).toBeVisible();
    await expect(page.locator(".react-flow__node")).toHaveCount(4);

    // 7. Switch to Manual (the SegmentedControl input is visually hidden — click its label)
    // and drag p1; wait for the exact PUT carrying p1's node id, asserting 204 OUTSIDE the
    // predicate. The mode switch itself triggers an immediate save AND resyncs React Flow's
    // node array (auto -> manual re-applies positions) — await that PUT's response before
    // reading the node's bounding box, or the drag can start mid-resync and land on a node
    // whose drag tracking React Flow just reset, silently producing no position change.
    const p1NodeId = `${parentBp}|${p1Id}`;
    const [modeSaved] = await Promise.all([
      page.waitForResponse(
        (r) =>
          r.url().endsWith(layoutPath) &&
          r.request().method() === "PUT" &&
          (r.request().postDataJSON() as EntityLayoutDocument).mode === "manual",
      ),
      page.getByText("Manual", { exact: true }).click(),
    ]);
    expect(modeSaved.status()).toBe(204);
    await expect(page.getByRole("radio", { name: "Manual" })).toBeChecked();

    const nodeP1 = page.locator(`.react-flow__node[data-id="${p1NodeId}"]`);
    await nodeP1.hover();
    const box = (await nodeP1.boundingBox())!;
    const cx = box.x + box.width / 2;
    const cy = box.y + box.height / 2;
    const [positionSaved] = await Promise.all([
      page.waitForResponse(
        (r) =>
          r.url().endsWith(layoutPath) &&
          r.request().method() === "PUT" &&
          (r.request().postDataJSON() as EntityLayoutDocument).positions[p1NodeId] != null,
      ),
      (async () => {
        await page.mouse.down();
        await page.mouse.move(cx + 140, cy - 60, { steps: 8 });
        // Mid-gesture the node must stay painted (the render.spec.ts idiom) — this also gives
        // React Flow's own pointer handling a turn to register the move before mouse.up() fires.
        await expect(nodeP1).toBeVisible();
        await page.mouse.up();
      })(),
    ]);
    expect(positionSaved.status()).toBe(204);
    const draggedTransform = await nodeP1.evaluate((el) => (el as HTMLElement).style.transform);

    // 8. Reload: Manual is still selected and the dragged position survived, server-side per
    // user (the filter itself is device-local, restored from localStorage on reload).
    await page.reload();
    await expect(page.getByRole("radio", { name: "Manual" })).toBeChecked();
    await expect.poll(async () => nodeP1.evaluate((el) => (el as HTMLElement).style.transform)).toBe(
      draggedTransform,
    );
    const storedLayout = await page.request.get(layoutPath, {
      headers: { Authorization: `Bearer ${await page.evaluate(() => localStorage.getItem("toadie.auth.token"))}` },
    });
    expect(storedLayout.status()).toBe(200);
    expect(((await storedLayout.json()) as EntityLayoutDocument).positions[p1NodeId]).toBeDefined();
  } finally {
    // Cleanup: the children first (they reference p1), then p1 (it references p2), then p2,
    // then the child blueprint (it targets the parent), then the parent blueprint, then the
    // throwaway user.
    if (c1EntityId) {
      const deleted = await page.request.delete(`/api/v1/entities/${c1EntityId}`, { headers: authHeaders });
      expect(deleted.status()).toBe(204);
    }
    if (c2EntityId) {
      const deleted = await page.request.delete(`/api/v1/entities/${c2EntityId}`, { headers: authHeaders });
      expect(deleted.status()).toBe(204);
    }
    if (p1EntityId) {
      const deleted = await page.request.delete(`/api/v1/entities/${p1EntityId}`, { headers: authHeaders });
      expect(deleted.status(), "cleanup: delete the referring entity before its target").toBe(204);
    }
    if (p2EntityId) {
      const deleted = await page.request.delete(`/api/v1/entities/${p2EntityId}`, { headers: authHeaders });
      expect(deleted.status()).toBe(204);
    }
    if (childBpId) {
      const deleted = await page.request.delete(`/api/v1/blueprints/${childBpId}`, { headers: authHeaders });
      expect(deleted.status(), "cleanup: delete the dependent blueprint before its target").toBe(204);
    }
    if (parentBpId) {
      const deleted = await page.request.delete(`/api/v1/blueprints/${parentBpId}`, { headers: authHeaders });
      expect(deleted.status()).toBe(204);
    }
    if (user) {
      const deletedUser = await page.request.delete(`/api/v1/users/${user.id}`, { headers: authHeaders });
      expect(deletedUser.status()).toBe(204);
    }
  }
});
