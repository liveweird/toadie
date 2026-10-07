import { expect, login, openFilters, openQuery, rowOperation, test, uniqueText } from "./helpers";

// Fill-in tiers (2.18.0): a Toadie-only, purely visual priority hint stored beside a blueprint's
// Port document at three levels (the blueprint, each schema property, each relation). Two
// throwaway blueprints are seeded via the API — one that the journey tiers through the real
// blueprint editor (blueprint tier 1 and its `alpha` property tier 1, `beta` left untiered) and
// one that stays untiered — each with one entity. The journey then walks every surface that
// reads tiers: the Blueprints list dot + Focus, the entity editor's field dots, "Filled through
// tier N" badge and Focus fold, and the Entity graph's `$fillTier` query + Focus. Owns only its
// own `e2e-tier-*` blueprints and entities (the registry-ownership rule in ../README.md); the
// admin is the only actor, and nothing is written to a per-user document.
test("tiers mark blueprints and fields, and Focus narrows the lists, the entity editor and the graph", async ({
  page,
}) => {
  await login(page);
  const adminToken = await page.evaluate(() => localStorage.getItem("toadie.auth.token"));
  expect(adminToken !== null, "the admin login must provide setup/cleanup authorization").toBe(true);
  const authHeaders = { Authorization: `Bearer ${adminToken}` };

  const run = uniqueText("e2e-tier");
  const tieredBp = `${run}-bp-tiered`;
  const plainBp = `${run}-bp-plain`;
  const tieredEntity = `${run}-ent-tiered`;
  const plainEntity = `${run}-ent-plain`;
  const tieredEntityTitle = "E2E Tier Entity";
  const plainEntityTitle = "E2E Plain Entity";

  let tieredEntityId: number | undefined;
  let plainEntityId: number | undefined;
  let tieredBpId: number | undefined;
  let plainBpId: number | undefined;

  try {
    // 0. Seed through the API: a blueprint with two string properties (no tiers yet), an
    // untiered twin, and one entity of each.
    const tieredBpResp = await page.request.post("/api/v1/blueprints", {
      headers: authHeaders,
      data: {
        identifier: tieredBp,
        title: "E2E Tier Tiered Blueprint",
        schema: {
          properties: {
            alpha: { type: "string", title: "E2E Alpha" },
            beta: { type: "string", title: "E2E Beta" },
          },
          required: [],
        },
        relations: {},
      },
    });
    expect(tieredBpResp.status()).toBe(201);
    tieredBpId = (await tieredBpResp.json()).id;

    const plainBpResp = await page.request.post("/api/v1/blueprints", {
      headers: authHeaders,
      data: {
        identifier: plainBp,
        title: "E2E Tier Plain Blueprint",
        schema: { properties: { gamma: { type: "string", title: "E2E Gamma" } }, required: [] },
        relations: {},
      },
    });
    expect(plainBpResp.status()).toBe(201);
    plainBpId = (await plainBpResp.json()).id;

    const tieredEntityResp = await page.request.post("/api/v1/entities", {
      headers: authHeaders,
      data: { blueprint: tieredBp, identifier: tieredEntity, title: tieredEntityTitle, properties: { beta: "filled" } },
    });
    expect(tieredEntityResp.status()).toBe(201);
    tieredEntityId = (await tieredEntityResp.json()).id;

    const plainEntityResp = await page.request.post("/api/v1/entities", {
      headers: authHeaders,
      data: { blueprint: plainBp, identifier: plainEntity, title: plainEntityTitle },
    });
    expect(plainEntityResp.status()).toBe(201);
    plainEntityId = (await plainEntityResp.json()).id;

    // 1. Blueprint editor: give the blueprint tier 1 and its `alpha` property tier 1 through
    // the TierSelects, watch the live JSON preview carry `tiers`, and save.
    await page.goto("/blueprints");
    await expect(page.getByRole("heading", { name: "Blueprints" })).toBeVisible();
    await rowOperation(page, tieredBp, "Edit");
    // Wait for an element unique to the editor before interacting (the lazy-route fill race).
    await expect(page.getByRole("textbox", { name: "Identifier" })).toHaveValue(tieredBp);

    await page.getByRole("combobox", { name: "Fill-in tier" }).click();
    await page.getByRole("option", { name: "Tier 1", exact: true }).click();
    await page.getByRole("combobox", { name: "Tier for alpha" }).click();
    await page.getByRole("option", { name: "Tier 1", exact: true }).click();
    await expect(page.getByLabel("JSON preview")).toContainText('"tiers"');

    const [saveResp] = await Promise.all([
      page.waitForResponse(
        (r) => new URL(r.url()).pathname === `/api/v1/blueprints/${tieredBpId}` && r.request().method() === "PUT",
      ),
      page.getByRole("button", { name: "Save" }).click(),
    ]);
    expect(saveResp.status()).toBe(204);
    await expect(page).toHaveURL(/\/blueprints$/);

    // 2. Blueprints list: the tiered row leads with its dot, the untiered one has none, and a
    // Tier 1 Focus hides the untiered row (an active Focus excludes untiered items).
    const tieredRow = page.getByRole("row").filter({ hasText: tieredBp });
    const plainRow = page.getByRole("row").filter({ hasText: plainBp });
    await expect(tieredRow.locator('[data-tier="1"]')).toBeVisible();
    await expect(plainRow).toBeVisible();
    await expect(plainRow.locator("[data-tier]")).toHaveCount(0);

    await page.getByRole("combobox", { name: "Focus" }).click();
    await page.getByRole("option", { name: "Tier 1", exact: true }).click();
    await expect(plainRow).toHaveCount(0);
    await expect(tieredRow).toBeVisible();

    // 3. Entity editor: the tiered field's label leads with a dot (the untiered one has none),
    // the badge reads "Tier 1 incomplete" while `alpha` is empty, and a Tier 1 Focus folds the
    // untiered `beta` behind "Show 1 more field" without touching its stored value.
    await page.goto(`/entities/${tieredEntityId}/edit`);
    await expect(page.getByRole("textbox", { name: "Identifier" })).toHaveValue(tieredEntity);
    await expect(page.locator("label", { hasText: "E2E Alpha" }).locator('[data-tier="1"]')).toBeVisible();
    await expect(page.locator("label", { hasText: "E2E Beta" }).locator("[data-tier]")).toHaveCount(0);
    await expect(page.getByText("Tier 1 incomplete")).toBeVisible();

    await page.getByRole("combobox", { name: "Focus" }).click();
    await page.getByRole("option", { name: "Tier 1", exact: true }).click();
    const showMore = page.getByRole("button", { name: "Show 1 more field" });
    await expect(showMore).toBeVisible();
    await expect(page.getByRole("textbox", { name: "E2E Beta" })).toBeHidden();
    await showMore.click();
    await expect(page.getByRole("textbox", { name: "E2E Beta" })).toHaveValue("filled");

    // Filling the only tiered field moves the badge live (nothing is saved).
    await page.getByRole("textbox", { name: "E2E Alpha" }).fill("done");
    await expect(page.getByText("Filled through tier 4")).toBeVisible();

    // 4. Entity graph: narrow to this run, then ask for entities missing a tier-1 field. The
    // untiered blueprint's entity has no `$fillTier` (null), so only the tiered entity remains.
    await page.goto("/entity-graph");
    await expect(page.getByRole("heading", { name: "Entity graph" })).toBeVisible();
    await openFilters(page);
    await page.getByRole("textbox", { name: "Search" }).fill(run);
    await expect(page.getByText(tieredEntity, { exact: true })).toBeVisible();
    await expect(page.getByText(plainEntity, { exact: true })).toBeVisible();
    await expect(page.locator(".react-flow__node")).toHaveCount(2);

    const query = `MATCH (n) WHERE n.$fillTier < 1 AND n.$identifier STARTS WITH '${run}' RETURN n`;
    await openQuery(page);
    const queryBox = page.getByRole("textbox", { name: "Entity query" });
    await queryBox.click();
    await queryBox.pressSequentially(query);
    await expect(queryBox).toContainText(query);
    const [queryResp] = await Promise.all([
      page.waitForResponse(
        (r) =>
          new URL(r.url()).pathname === "/api/v1/entities/graph" &&
          r.request().method() === "GET" &&
          new URL(r.url()).searchParams.has("query"),
      ),
      page.getByRole("button", { name: "Run" }).click(),
    ]);
    expect(queryResp.status()).toBe(200);
    await expect(page.locator(".react-flow__node")).toHaveCount(1);
    await expect(page.locator(".react-flow__node", { hasText: tieredEntityTitle })).toBeVisible();

    // 5. Clear the query, then the graph's Focus (Tier 1) hides the untiered blueprint's node.
    await page.getByRole("button", { name: "Clear", exact: true }).click();
    await expect(page.locator(".react-flow__node")).toHaveCount(2);
    await page.getByRole("combobox", { name: "Focus" }).click();
    await page.getByRole("option", { name: "Tier 1", exact: true }).click();
    await expect(page.locator(".react-flow__node", { hasText: plainEntityTitle })).toHaveCount(0);
    await expect(page.locator(".react-flow__node", { hasText: tieredEntityTitle })).toBeVisible();
    await expect(page.locator(".react-flow__node")).toHaveCount(1);
  } finally {
    // Cleanup: the entities first, then the blueprints (no relations tie them together). Every
    // delete is attempted before any status is asserted, so one failure cannot leak the rest.
    const statuses: { what: string; status: number }[] = [];
    for (const [what, path, id] of [
      ["entity", "entities", tieredEntityId],
      ["entity", "entities", plainEntityId],
      ["blueprint", "blueprints", tieredBpId],
      ["blueprint", "blueprints", plainBpId],
    ] as const) {
      if (!id) continue;
      const deleted = await page.request.delete(`/api/v1/${path}/${id}`, { headers: authHeaders });
      statuses.push({ what: `${what} ${id}`, status: deleted.status() });
    }
    for (const { what, status } of statuses) {
      expect(status, `cleanup: delete ${what}`).toBe(204);
    }
  }
});
