import {
  createTeamEntity,
  createUserViaUi,
  expect,
  expectNoAccessibilityViolations,
  login,
  openFilters,
  openPills,
  seedEntityChain,
  test,
  uniqueText,
} from "./helpers";

type EntityLayoutDocument = {
  mode: "auto" | "manual";
  positions: Record<string, { x: number; y: number }>;
  collapsed: string[];
};

// The Entity graph (Port migration phase 3, + Phase 4 ownership edges v1.26.0; parallel
// hierarchies v1.32.0): two throwaway blueprints seeded via the API — a parent blueprint
// carrying a `peer` MANY self-relation, and a child blueprint carrying a single `parent`
// relation flagged as the `composition` entry of its `hierarchyRelations` — plus two parent
// and two child entities (p1.peer -> [p2]; c1/c2.parent -> p1) and one throwaway `_team`
// entity p1 is Direct-owned by (p1.team -> [team]). Every
// identifier shares ONE run marker so the graph can be narrowed to this run's own rows with
// `q` even once `_team` (a workspace-wide blueprint other specs also write to) joins the
// blueprint filter. A throwaway user owns the page's per-user layout document
// (`/users/{id}/entity-graph-layout`), never the seed admin's. Owns only its own `e2e-eg-*`
// blueprints/entities, one `e2e-eg-*`-marked `_team` entity (never a foreign one), and that one
// throwaway user.
test("the entity graph filters by blueprint, folds hierarchy, and persists a manual layout", async ({
  page,
}) => {
  await login(page);
  const adminToken = await page.evaluate(() => localStorage.getItem("toadie.auth.token"));
  expect(adminToken !== null, "the admin login must provide setup/cleanup authorization").toBe(true);
  const authHeaders = { Authorization: `Bearer ${adminToken}` };

  const run = uniqueText("e2e-eg");
  const parentBp = `${run}-bp-parent`;
  const childBp = `${run}-bp-child`;
  const parentTitle = "E2E EG Parent Blueprint";
  const childTitle = "E2E EG Child Blueprint";
  const p1Id = `${run}-p1`;
  const p2Id = `${run}-p2`;
  const c1Id = `${run}-c1`;
  const c2Id = `${run}-c2`;
  const teamId = `${run}-team`;
  const p1Title = "E2E EG Parent One";
  const p2Title = "E2E EG Parent Two";
  const c1Title = "E2E EG Child One";
  const c2Title = "E2E EG Child Two";
  const teamTitle = "E2E EG Team";

  let user: Awaited<ReturnType<typeof createUserViaUi>> | undefined;
  let p1EntityId: number | undefined;
  let p2EntityId: number | undefined;
  let c1EntityId: number | undefined;
  let c2EntityId: number | undefined;
  let teamEntityId: number | undefined;
  let childBpId: number | undefined;
  let parentBpId: number | undefined;

  try {
    // 0. Seed the two throwaway blueprints via the API: a `peer` MANY self-relation on the
    // parent, and a single `parent` relation on the child flagged as the `composition` entry
    // of its `hierarchyRelations`.
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
        hierarchyRelations: { composition: "parent" },
      },
    });
    expect(childBpResp.status()).toBe(201);
    childBpId = (await childBpResp.json()).id;

    // 1. Seed the entities: the throwaway team (p1's ownership target — `createTeamEntity`
    // satisfies whatever `_team`'s current schema requires, since an environment carrying the
    // sample ontology extension may add required properties beyond the seeded base shape),
    // p2 (p1's peer target), then p1 (owned by the team), then the two children pointing their
    // `parent` relation at p1.
    teamEntityId = await createTeamEntity(page.request, adminToken!, teamId, teamTitle);

    const p2Resp = await page.request.post("/api/v1/entities", {
      headers: authHeaders,
      data: { blueprint: parentBp, identifier: p2Id, title: p2Title },
    });
    expect(p2Resp.status()).toBe(201);
    p2EntityId = (await p2Resp.json()).id;

    const p1Resp = await page.request.post("/api/v1/entities", {
      headers: authHeaders,
      data: {
        blueprint: parentBp,
        identifier: p1Id,
        title: p1Title,
        relations: { peer: [p2Id] },
        team: [teamId],
      },
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

    // 3. Narrow the graph to this run's own rows with the search filter — the run marker on
    // every seeded identifier/title already isolates this run without picking blueprints
    // (the Blueprints pill row, behind the Visibility toggle, starts every blueprint shown,
    // so nothing needs toggling to see them).
    await openFilters(page);
    await page.getByRole("textbox", { name: "Search" }).fill(run);
    // The Relations and Blueprints pill rows (steps 5/5b/5d) sit behind the same collapsed
    // Visibility toggle — open it once, up front.
    await openPills(page);

    // 4. Five nodes, three blueprint frames (labelled by blueprint TITLE) — the throwaway team
    // joins the run's own parent/child entities, narrowed to just this run by `q`.
    await expect(page.getByText(p1Id, { exact: true })).toBeVisible();
    await expect(page.getByText(p2Id, { exact: true })).toBeVisible();
    await expect(page.getByText(c1Id, { exact: true })).toBeVisible();
    await expect(page.getByText(c2Id, { exact: true })).toBeVisible();
    await expect(page.getByText(teamId, { exact: true })).toBeVisible();
    const frames = page.locator(".react-flow__viewport-portal");
    await expect(frames.getByText(parentTitle, { exact: true })).toBeVisible();
    await expect(frames.getByText(childTitle, { exact: true })).toBeVisible();
    await expect(frames.getByText("Team", { exact: true })).toBeVisible();

    // 5. Four edges total: p1--peer-->p2, c1--parent-->p1, c2--parent-->p1, and the Phase 4
    // ownership edge p1--$team-->team. Toggling the "peer" relation chip off drops the p1->p2
    // edge; toggling it back restores it. The relation identifier "peer" also labels the
    // p1->p2 edge on the canvas, so scope the click to the "Relations" chip group (a
    // strict-mode ambiguity otherwise) — and click the visible label, not the Chip's
    // underlying checkbox input, which is visually hidden and not clickable.
    await expect(page.locator(".react-flow__edge")).toHaveCount(4);
    const relationsGroup = page.getByRole("group", { name: "Relations" });
    const peerChip = relationsGroup.getByText("peer", { exact: true });
    await peerChip.click();
    await expect(page.locator(".react-flow__edge")).toHaveCount(3);
    await peerChip.click();
    await expect(page.locator(".react-flow__edge")).toHaveCount(4);

    // 5b. The ownership edge rides its own "$team" relation chip — toggling it off drops the
    // p1->team edge; toggling it back on restores it; the four nodes are unaffected either way.
    const teamChip = relationsGroup.getByText("$team", { exact: true });
    await teamChip.click();
    await expect(page.locator(".react-flow__edge")).toHaveCount(3);
    await teamChip.click();
    await expect(page.locator(".react-flow__edge")).toHaveCount(4);

    // 5c. Setting the toolbar's Team filter to the throwaway team narrows the graph further —
    // p1 (owned by it) and the team node itself (the server's self-match rule) both stay.
    const teamFilter = page.getByRole("combobox", { name: "Team", exact: true });
    await teamFilter.click();
    await teamFilter.fill(teamId);
    await page.getByRole("option", { name: `${teamId} — ${teamTitle}`, exact: true }).click();
    await expect(page.getByText(p1Id, { exact: true })).toBeVisible();
    await expect(page.getByText(teamId, { exact: true })).toBeVisible();
    // Mantine renders a Select's clear button aria-hidden (mouse-only affordance), so a role
    // query never sees it — target its aria-label attribute directly.
    await page.locator('button[aria-label="Clear team filter"]').click();

    // 5d. Toggling the child blueprint's pill off (in the Blueprints group, behind the
    // Visibility toggle opened above) hides its two entities without touching the parent's or
    // the team's; toggling it back on restores them. Other parallel specs' own blueprints may
    // appear as extra chips in this workspace-wide group, so the chip is located by its exact
    // identifier.
    const blueprintsGroup = page.getByRole("group", { name: "Blueprints" });
    const childChip = blueprintsGroup.getByText(childBp, { exact: true });
    await childChip.click();
    await expect(page.getByText(c1Id, { exact: true })).toHaveCount(0);
    await expect(page.getByText(c2Id, { exact: true })).toHaveCount(0);
    await expect(page.getByText(p1Id, { exact: true })).toBeVisible();
    await childChip.click();
    await expect(page.getByText(c1Id, { exact: true })).toBeVisible();
    await expect(page.getByText(c2Id, { exact: true })).toBeVisible();

    // 6. Collapse p1 via its fold toggle: c1/c2 (its hierarchy-relation descendants) hide, and
    // the pill names the hidden count. The team node is unaffected — ownership never nests.
    await expect(page.locator(".react-flow__node")).toHaveCount(5);
    await page.getByRole("button", { name: `Collapse ${p1Title}` }).click();
    await expect(page.getByText(c1Id, { exact: true })).toHaveCount(0);
    await expect(page.getByText(c2Id, { exact: true })).toHaveCount(0);
    await expect(page.getByText(teamId, { exact: true })).toBeVisible();
    await expect(page.locator(".react-flow__node")).toHaveCount(3);
    await expect(page.getByRole("button", { name: `Expand ${p1Title}, 2 hidden` })).toBeVisible();
    // The collapsed pill is on screen: its accessible name must carry the visible count outside
    // any parenthetical (axe `label-content-name-mismatch`).
    await expectNoAccessibilityViolations(page);

    // Expand again — p1's descendants come back.
    await page.getByRole("button", { name: `Expand ${p1Title}, 2 hidden` }).click();
    await expect(page.getByText(c1Id, { exact: true })).toBeVisible();
    await expect(page.getByText(c2Id, { exact: true })).toBeVisible();
    await expect(page.locator(".react-flow__node")).toHaveCount(5);

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
        // Mid-gesture the node must stay painted (the graph.spec.ts idiom) — this also gives
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
    // Cleanup: the children first (they reference p1), then p1 (it references p2 AND the
    // team), then p2, then the team (now unreferenced), then the child blueprint (it targets
    // the parent), then the parent blueprint, then the throwaway user.
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
    if (teamEntityId) {
      const deleted = await page.request.delete(`/api/v1/entities/${teamEntityId}`, { headers: authHeaders });
      expect(deleted.status(), "cleanup: delete the team only after its owned entity is gone").toBe(204);
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

// The large-graph gate (2.20.0; opens folded by default since 2.22.0): one throwaway blueprint whose single self-`parent` relation is
// the `composition` hierarchy, and 320 entities in eight chains of forty, imported through the
// batched entity import (two batches of at most 200). The seed admin opens the canvas narrowed
// to this run with the search filter; at 300+ drawn nodes the canvas must open on the top-level
// overlay (the eight chain roots) instead of laying all 320 out, and nothing it does may be
// written to the layout document (the admin's own is only READ, so a
// PUT of it fails the journey). Owns only its `e2e-eg-*` blueprint and entities.
test("a large entity graph opens folded to its top level and shows everything on request", async ({ page }) => {
  test.slow();
  await login(page);
  const adminToken = await page.evaluate(() => localStorage.getItem("toadie.auth.token"));
  expect(adminToken !== null, "the admin login must provide setup/cleanup authorization").toBe(true);
  const authHeaders = { Authorization: `Bearer ${adminToken}` };

  const run = uniqueText("e2e-eg");
  const blueprint = `${run}-bp-large`;
  const total = 320;
  const chainLength = 40;
  const storedIds: number[] = [];
  let blueprintId: number | undefined;
  const layoutWrites: string[] = [];
  page.on("request", (request) => {
    if (request.method() === "PUT" && request.url().includes("entity-graph-layout")) {
      layoutWrites.push(request.url());
    }
  });

  try {
    // 0. The blueprint (a single `parent` self-relation flagged as the `composition` hierarchy)
    // and its 320 chained entities.
    const blueprintResp = await page.request.post("/api/v1/blueprints", {
      headers: authHeaders,
      data: {
        identifier: blueprint,
        title: "E2E EG Large Blueprint",
        schema: { properties: {}, required: [] },
        relations: { parent: { title: "Parent", target: blueprint, required: false, many: false } },
        hierarchyRelations: { composition: "parent" },
      },
    });
    expect(blueprintResp.status()).toBe(201);
    blueprintId = (await blueprintResp.json()).id;
    const { roots } = await seedEntityChain(page.request, adminToken!, blueprint, run, total, {
      chainLength,
      created: storedIds,
    });
    expect(roots).toHaveLength(total / chainLength);

    // 1. Open the Entity graph narrowed to this run: 320 nodes are over the gate's 300, so the
    // canvas opens on the top-level overlay by itself: the eight chain roots, under a "Top level"
    // badge, with no panel in the way.
    await page.goto("/entity-graph");
    await expect(page.getByRole("heading", { name: "Entity graph" })).toBeVisible();
    await openFilters(page);
    await page.getByRole("textbox", { name: "Search" }).fill(run);
    const gate = page.getByRole("region", { name: "Large graph" });
    const wrapper = page.getByTestId("rf__wrapper");
    await expect(wrapper).toHaveAttribute("data-drawn-nodes", String(roots.length), { timeout: 60_000 });
    await expect(page.locator(".react-flow__node")).toHaveCount(roots.length);
    await expect(page.getByText("Top level", { exact: true })).toBeVisible();
    await expect(gate).toHaveCount(0);
    await expect(page.getByText(/nothing to render/i)).toHaveCount(0);
    // The folded top level carries collapsed fold pills and the badge: axe over exactly that.
    await expect(page.getByRole("button", { name: /^Expand .*, \d+ hidden$/ }).first()).toBeVisible();
    await expectNoAccessibilityViolations(page);

    // 2. Leaving the top-level view brings the gate panel, which names the whole selection.
    await page.getByRole("button", { name: "Leave the top-level view" }).click();
    await expect(gate).toBeVisible();
    await expect(gate).toContainText(`Nodes: ${total}`);
    await expect(page.locator(".react-flow__node")).toHaveCount(0);

    // 3. Show everything lays all 320 out (the worker; the main thread stays responsive). Above
    // the gate's limit the canvas mounts only the nodes in view (`onlyRenderVisibleElements`), so
    // the DOM holds a subset; the canvas wrapper's `data-drawn-nodes` carries the laid-out count.
    await gate.getByRole("button", { name: "Show everything" }).click();
    await expect(wrapper).toHaveAttribute("data-drawn-nodes", String(total), { timeout: 60_000 });
    await expect(page.locator(".react-flow__node").first()).toBeVisible();
    await expect(gate).toHaveCount(0);
    await expect(page.getByText("Top level", { exact: true })).toHaveCount(0);

    // Nothing the gate did reached the per-user layout document.
    expect(layoutWrites).toEqual([]);
  } finally {
    // Cleanup: every entity (a referrer before its target, so newest first), then the blueprint.
    for (const id of [...storedIds].reverse()) {
      const deleted = await page.request.delete(`/api/v1/entities/${id}`, { headers: authHeaders });
      expect(deleted.status(), "cleanup: delete the referring entity before its target").toBe(204);
    }
    if (blueprintId) {
      const deleted = await page.request.delete(`/api/v1/blueprints/${blueprintId}`, { headers: authHeaders });
      expect(deleted.status()).toBe(204);
    }
  }
});
