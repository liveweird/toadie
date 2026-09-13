import { expect, login, openFilters, test, uniqueText } from "./helpers";

// The entity query bar (Port migration phase 7, entity query language, 2.0.0): a Cypher-shaped
// `MATCH` query narrows both Port Ontology canvases at once. Two throwaway blueprints seeded
// via the API under one run marker — a parent blueprint P with no relations, and a child
// blueprint C carrying a single `parent` relation to P flagged as the `composition` entry of
// its `hierarchyRelations` (the entity-graph.spec.ts shape, needed here too so the Entity
// hierarchy page nests c1 under p1) — plus three entities: p1 (P), c1 (C, `parent` -> p1), and
// c2 (C, no `parent`). The whole journey runs as the seed admin (entities carry no admin gate).
// Owns only its own `e2e-eq-*` blueprints and entities, all removed at the end.
test("the entity query narrows both canvases and reports a suggestion for a mistyped label", async ({
  page,
}) => {
  await login(page);
  const adminToken = await page.evaluate(() => localStorage.getItem("toadie.auth.token"));
  expect(adminToken !== null, "the admin login must provide setup/cleanup authorization").toBe(true);
  const authHeaders = { Authorization: `Bearer ${adminToken}` };

  const run = uniqueText("e2e-eq");
  const parentBp = `${run}-bp-parent`;
  const childBp = `${run}-bp-child`;
  const p1Id = `${run}-p1`;
  const c1Id = `${run}-c1`;
  const c2Id = `${run}-c2`;
  const p1Title = "E2E EQ Parent One";
  const c1Title = "E2E EQ Child One";
  const c2Title = "E2E EQ Child Two";

  let p1EntityId: number | undefined;
  let c1EntityId: number | undefined;
  let c2EntityId: number | undefined;
  let childBpId: number | undefined;
  let parentBpId: number | undefined;

  try {
    // 0. Seed the two throwaway blueprints via the API — the child's single `parent` relation
    // is flagged as the `composition` entry of its `hierarchyRelations` (the entity-graph.spec.ts
    // shape), so the Entity hierarchy page nests c1 under p1.
    const parentBpResp = await page.request.post("/api/v1/blueprints", {
      headers: authHeaders,
      data: { identifier: parentBp, title: "E2E EQ Parent Blueprint", schema: { properties: {}, required: [] } },
    });
    expect(parentBpResp.status()).toBe(201);
    parentBpId = (await parentBpResp.json()).id;

    const childBpResp = await page.request.post("/api/v1/blueprints", {
      headers: authHeaders,
      data: {
        identifier: childBp,
        title: "E2E EQ Child Blueprint",
        schema: { properties: {}, required: [] },
        relations: {
          parent: { title: "Parent", target: parentBp, required: false, many: false },
        },
        hierarchyRelations: { composition: "parent" },
      },
    });
    expect(childBpResp.status()).toBe(201);
    childBpId = (await childBpResp.json()).id;

    // 1. Seed p1 (P), c1 (C, `parent` -> p1), and c2 (C, no `parent`).
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
      data: { blueprint: childBp, identifier: c2Id, title: c2Title },
    });
    expect(c2Resp.status()).toBe(201);
    c2EntityId = (await c2Resp.json()).id;

    // 2. Open the Entity graph and narrow it to this run's own three entities with the search
    // filter (the workspace may carry other specs' blueprints/entities running in parallel).
    await page.goto("/entity-graph");
    await expect(page.getByRole("heading", { name: "Entity graph" })).toBeVisible();
    await openFilters(page);
    await page.getByRole("textbox", { name: "Search" }).fill(run);

    await expect(page.getByText(p1Id, { exact: true })).toBeVisible();
    await expect(page.getByText(c1Id, { exact: true })).toBeVisible();
    await expect(page.getByText(c2Id, { exact: true })).toBeVisible();
    await expect(page.locator(".react-flow__node")).toHaveCount(3);

    // 3. Type a MATCH query selecting c1's `parent` edge to p1, and run it. The graph request
    // carries both the run's own `q` filter and the new `query` param.
    // The run marker carries hyphens, so the labels are backticked (`IDENT` is `[A-Za-z_][A-Za-z0-9_]*`).
    const validQuery = `MATCH (a:\`${childBp}\`)-[:parent]->(b:\`${parentBp}\`) RETURN a, b`;
    const queryBox = page.getByRole("textbox", { name: "Entity query" });
    await queryBox.click();
    await queryBox.pressSequentially(validQuery);
    await expect(queryBox).toContainText(validQuery);

    const [graphResp] = await Promise.all([
      page.waitForResponse((r) => {
        const url = new URL(r.url());
        return (
          url.pathname === "/api/v1/entities/graph" &&
          r.request().method() === "GET" &&
          url.searchParams.has("query") &&
          url.searchParams.has("q")
        );
      }),
      page.getByRole("button", { name: "Run" }).click(),
    ]);
    expect(graphResp.status()).toBe(200);

    // 4. Exactly c1 and p1 remain; c2 (no `parent` edge) drops out, and an "Applied" badge
    // appears.
    await expect(page.locator(".react-flow__node")).toHaveCount(2);
    await expect(page.getByText(p1Id, { exact: true })).toBeVisible();
    await expect(page.getByText(c1Id, { exact: true })).toBeVisible();
    await expect(page.getByText(c2Id, { exact: true })).toHaveCount(0);
    await expect(page.getByText(/^Applied · \d+ /)).toBeVisible();

    // 5. The Entity hierarchy page shares the same draft/applied query (localStorage) — it
    // shows the identical text and the identical narrowing without re-running anything.
    await page.goto("/entity-hierarchy");
    await expect(page.getByRole("heading", { name: "Entity hierarchy" })).toBeVisible();
    await openFilters(page);
    await page.getByRole("textbox", { name: "Search" }).fill(run);

    const hierarchyQueryBox = page.getByRole("textbox", { name: "Entity query" });
    await expect(hierarchyQueryBox).toContainText(validQuery);
    await expect(page.getByText(/^Applied · \d+ /)).toBeVisible();
    await expect(page.getByText(p1Title, { exact: true })).toBeVisible();
    await expect(page.getByText(c1Title, { exact: true })).toBeVisible();
    await expect(page.getByText(c2Title, { exact: true })).toHaveCount(0);

    // 6. Back on the Entity graph, replace the query with a mistyped label and let the
    // debounced live check run; it must suggest the real blueprint identifier.
    await page.goto("/entity-graph");
    await expect(page.getByRole("heading", { name: "Entity graph" })).toBeVisible();
    const mistypedQuery = `MATCH (a:\`${childBp}x\`) RETURN a`;
    const queryBoxAgain = page.getByRole("textbox", { name: "Entity query" });

    const [checkResp] = await Promise.all([
      page.waitForResponse(
        (r) => new URL(r.url()).pathname === "/api/v1/entities/query/check" && r.request().method() === "POST",
      ),
      (async () => {
        await queryBoxAgain.click();
        await page.keyboard.press("ControlOrMeta+A");
        await queryBoxAgain.pressSequentially(mistypedQuery);
      })(),
    ]);
    expect(checkResp.status()).toBe(200);

    // The suggestion span renders exactly "Did you mean `<identifier>`?" — an exact match, since a
    // substring match on the identifier would also hit the editor's own text.
    await expect(page.getByText(`Did you mean \`${childBp}\`?`, { exact: true })).toBeVisible();

    // 7. Clear resets both the draft and the applied query: the badge disappears and the next
    // graph fetch carries no `query` param.
    const [clearedGraphResp] = await Promise.all([
      page.waitForResponse(
        (r) => new URL(r.url()).pathname === "/api/v1/entities/graph" && r.request().method() === "GET",
      ),
      page.getByRole("button", { name: "Clear", exact: true }).click(),
    ]);
    expect(clearedGraphResp.status()).toBe(200);
    expect(new URL(clearedGraphResp.url()).searchParams.has("query")).toBe(false);
    await expect(page.getByText(/^Applied · \d+ /)).toHaveCount(0);
  } finally {
    // Cleanup: the children first (c1/c2 reference p1 or nothing), then p1, then the child
    // blueprint (it targets the parent), then the parent blueprint.
    for (const id of [c1EntityId, c2EntityId]) {
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
