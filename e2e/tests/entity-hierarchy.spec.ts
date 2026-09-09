import { expect, login, openFilters, readyDialog, rowOperation, test, uniqueText } from "./helpers";

// The Entity hierarchy (Port migration phase 3): the same kind of throwaway blueprint pair as
// entity-graph.spec.ts (a parent blueprint, a child blueprint whose single `parent` relation is
// flagged as its `hierarchyRelation`), with one parent entity carrying two children plus one
// ORPHAN child whose `parent` relation is left unset (a second root). Entities carry no admin
// gate, so the whole journey runs as the seed admin. Owns only its own `e2e-eh-*`
// blueprints/entities.
test("the entity hierarchy nests by the hierarchy relation, pins a subtree, and blocks a referenced delete", async ({
  page,
}) => {
  await login(page);
  const adminToken = await page.evaluate(() => localStorage.getItem("toadie.auth.token"));
  expect(adminToken !== null, "the admin login must provide setup/cleanup authorization").toBe(true);
  const authHeaders = { Authorization: `Bearer ${adminToken}` };

  const parentBp = uniqueText("e2e-eh-bp-parent");
  const childBp = uniqueText("e2e-eh-bp-child");
  const p1Id = uniqueText("e2e-eh-p1");
  const c1Id = uniqueText("e2e-eh-c1");
  const c2Id = uniqueText("e2e-eh-c2");
  const c3Id = uniqueText("e2e-eh-c3");
  const p1Title = "E2E EH Parent One";
  const c1Title = "E2E EH Child One";
  const c2Title = "E2E EH Child Two";
  const c3Title = "E2E EH Orphan Child";

  let p1EntityId: number | undefined;
  let c1EntityId: number | undefined;
  let c2EntityId: number | undefined;
  let c3EntityId: number | undefined;
  let childBpId: number | undefined;
  let parentBpId: number | undefined;

  try {
    // 0. Seed the two throwaway blueprints via the API — the child's single `parent`
    // relation is flagged as its `hierarchyRelation`.
    const parentBpResp = await page.request.post("/api/v1/blueprints", {
      headers: authHeaders,
      data: { identifier: parentBp, title: "E2E EH Parent Blueprint", schema: { properties: {}, required: [] } },
    });
    expect(parentBpResp.status()).toBe(201);
    parentBpId = (await parentBpResp.json()).id;

    const childBpResp = await page.request.post("/api/v1/blueprints", {
      headers: authHeaders,
      data: {
        identifier: childBp,
        title: "E2E EH Child Blueprint",
        schema: { properties: {}, required: [] },
        relations: {
          parent: { title: "Parent", target: parentBp, required: false, many: false },
        },
        hierarchyRelation: "parent",
      },
    });
    expect(childBpResp.status()).toBe(201);
    childBpId = (await childBpResp.json()).id;

    // 1. Seed the entities: p1, two children pointing their `parent` relation at it, and one
    // orphan child whose `parent` relation is left unset — a second root.
    const p1Resp = await page.request.post("/api/v1/entities", {
      headers: authHeaders,
      data: { blueprint: parentBp, identifier: p1Id, title: p1Title },
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

    const c3Resp = await page.request.post("/api/v1/entities", {
      headers: authHeaders,
      data: { blueprint: childBp, identifier: c3Id, title: c3Title },
    });
    expect(c3Resp.status()).toBe(201);
    c3EntityId = (await c3Resp.json()).id;

    // 2. Open the Entity hierarchy and filter to the two throwaway blueprints — this page
    // persists its OWN filter state (`toadie.viewSettings.entityHierarchy.*`), independent of
    // the Entity graph page's, though both render through the same shared filter controls.
    await page.goto("/entity-hierarchy");
    await expect(page.getByRole("heading", { name: "Entity hierarchy" })).toBeVisible();
    await openFilters(page);
    const blueprintFilter = page.getByRole("combobox", { name: "Blueprints" });
    await blueprintFilter.click();
    await blueprintFilter.fill(parentBp);
    await page.getByRole("option", { name: parentBp, exact: true }).click();
    await blueprintFilter.fill(childBp);
    await page.getByRole("option", { name: childBp, exact: true }).click();
    // The MultiSelect stays open for further picks; its dropdown overlaps the tree below and
    // would otherwise intercept the row-button clicks that follow (the annotations.spec.ts
    // idiom: Escape closes it without touching the two selections just made).
    await page.keyboard.press("Escape");

    await expect(page.getByText(p1Title, { exact: true })).toBeVisible();
    await expect(page.getByText(c1Title, { exact: true })).toBeVisible();
    await expect(page.getByText(c2Title, { exact: true })).toBeVisible();
    await expect(page.getByText(c3Title, { exact: true })).toBeVisible();

    // 3. Collapsing p1's branch hides only its hierarchy-relation children; the orphan c3 (a
    // root of its own) is unaffected — proving c1/c2 nest under p1 and c3 does not.
    await page.getByRole("button", { name: `Toggle children of ${p1Title}` }).click();
    await expect(page.getByText(c1Title, { exact: true })).toHaveCount(0);
    await expect(page.getByText(c2Title, { exact: true })).toHaveCount(0);
    await expect(page.getByText(c3Title, { exact: true })).toBeVisible();
    await expect(page.getByText(p1Title, { exact: true })).toBeVisible();
    await page.getByRole("button", { name: `Toggle children of ${p1Title}` }).click();
    await expect(page.getByText(c1Title, { exact: true })).toBeVisible();
    await expect(page.getByText(c2Title, { exact: true })).toBeVisible();

    // 4. Pin p1: the tree narrows to it and its descendants — c3 (outside that subtree)
    // disappears; unpinning restores it.
    await rowOperation(page, p1Title, "Pin");
    await expect(page.getByText(`Pinned: ${p1Title}`)).toBeVisible();
    await expect(page.getByText(c1Title, { exact: true })).toBeVisible();
    await expect(page.getByText(c2Title, { exact: true })).toBeVisible();
    await expect(page.getByText(c3Title, { exact: true })).toHaveCount(0);
    await page.getByRole("button", { name: `Unpin ${p1Title}` }).click();
    await expect(page.getByText(`Pinned: ${p1Title}`)).toHaveCount(0);
    await expect(page.getByText(c3Title, { exact: true })).toBeVisible();

    // 5. Deleting p1 from its row menu is refused (409): it is still targeted by c1/c2's
    // `parent` relation, and the confirm dialog names that.
    await rowOperation(page, p1Title, "Delete");
    await readyDialog(page, "Delete entity");
    const [blockedDelete] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().endsWith(`/api/v1/entities/${p1EntityId}`) && r.request().method() === "DELETE",
      ),
      page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click(),
    ]);
    expect(blockedDelete.status()).toBe(409);
    await expect(
      page.getByRole("dialog").getByText("This entity is still targeted by another entity's relation."),
    ).toBeVisible();
    await page.getByRole("dialog").getByRole("button", { name: "Cancel", exact: true }).click();
    await expect(page.getByText(p1Title, { exact: true })).toBeVisible();
  } finally {
    // Cleanup: the children first (c1/c2 reference p1), then p1, then the child blueprint (it
    // targets the parent), then the parent blueprint.
    for (const id of [c1EntityId, c2EntityId, c3EntityId]) {
      if (!id) continue;
      const deleted = await page.request.delete(`/api/v1/entities/${id}`, { headers: authHeaders });
      expect(deleted.status(), "cleanup: delete the referring entities before their target").toBe(204);
    }
    if (p1EntityId) {
      const deleted = await page.request.delete(`/api/v1/entities/${p1EntityId}`, { headers: authHeaders });
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
  }
});
