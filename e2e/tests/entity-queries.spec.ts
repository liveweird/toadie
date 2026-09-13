import { expect, login, openFilters, readyDialog, test, uniqueText, waitForApi } from "./helpers";

// Saved entity queries (Port migration phase 7, v2.1.0): the entity-query.spec.ts blueprint/entity
// shape (a parent blueprint P with no relations, a child blueprint C carrying a single `parent`
// relation to P flagged as the `composition` entry of its `hierarchyRelations`, plus p1/c1->p1/c2)
// under its own `e2e-eqs-*` marker, exercising the "Saved query" picker's save/edit/rename/delete
// round trip and the LensPicker-shaped 404/403 disclosure split: a foreign PUBLIC saved query is
// visible and applicable everywhere, but its actions menu offers no mutation. Owns its own
// `e2e-eqs-*` blueprints/entities, one `e2e-eqs-*`-named saved query, and one throwaway regular
// user (a second browser context signs in as that user for the foreign-public-query half).
test("a saved entity query applies on both canvases and stays creator-only", async ({ page, browser }) => {
  await login(page);
  const adminToken = await page.evaluate(() => localStorage.getItem("toadie.auth.token"));
  expect(adminToken !== null, "the admin login must provide setup/cleanup authorization").toBe(true);
  const authHeaders = { Authorization: `Bearer ${adminToken}` };

  const run = uniqueText("e2e-eqs");
  const parentBp = `${run}-bp-parent`;
  const childBp = `${run}-bp-child`;
  const p1Id = `${run}-p1`;
  const c1Id = `${run}-c1`;
  const c2Id = `${run}-c2`;
  const p1Title = "E2E EQS Parent One";
  const c1Title = "E2E EQS Child One";
  const c2Title = "E2E EQS Child Two";

  let p1EntityId: number | undefined;
  let c1EntityId: number | undefined;
  let c2EntityId: number | undefined;
  let childBpId: number | undefined;
  let parentBpId: number | undefined;
  let queryId: number | undefined;
  let otherUserId: number | undefined;

  try {
    // 0. Seed the two throwaway blueprints via the API (the entity-query.spec.ts shape): the
    // child's single `parent` relation is flagged as the `composition` entry of its
    // `hierarchyRelations`, so the Entity hierarchy page nests c1 under p1.
    const parentBpResp = await page.request.post("/api/v1/blueprints", {
      headers: authHeaders,
      data: { identifier: parentBp, title: "E2E EQS Parent Blueprint", schema: { properties: {}, required: [] } },
    });
    expect(parentBpResp.status()).toBe(201);
    parentBpId = (await parentBpResp.json()).id;

    const childBpResp = await page.request.post("/api/v1/blueprints", {
      headers: authHeaders,
      data: {
        identifier: childBp,
        title: "E2E EQS Child Blueprint",
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

    // 2. Open the Entity graph, narrowed to this run's own three entities with the search
    // filter, and run a MATCH query selecting c1's `parent` edge to p1. The run marker carries
    // hyphens, so the labels are backticked.
    await page.goto("/entity-graph");
    await expect(page.getByRole("heading", { name: "Entity graph" })).toBeVisible();
    await openFilters(page);
    await page.getByRole("textbox", { name: "Search" }).fill(run);

    const matchQuery = `MATCH (a:\`${childBp}\`)-[:parent]->(b:\`${parentBp}\`) RETURN a, b`;
    const queryBox = page.getByRole("textbox", { name: "Entity query" });
    await queryBox.click();
    await queryBox.pressSequentially(matchQuery);
    await expect(queryBox).toContainText(matchQuery);

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
    await expect(page.locator(".react-flow__node")).toHaveCount(2);
    await expect(page.getByText(/^Applied · \d+ /)).toBeVisible();

    // 3. Save the applied query as a new PRIVATE saved query.
    await page.getByRole("button", { name: "Saved query actions" }).click();
    await page.getByRole("menuitem", { name: "Save as new query…" }).click();
    const saveDialog = await readyDialog(page, "Save query");
    const queryName = uniqueText("e2e-eqs");
    await saveDialog.getByLabel("Name").fill(queryName);
    const [savedQuery] = await Promise.all([
      waitForApi(page, { method: "POST", path: "/api/v1/entity-queries" }),
      saveDialog.getByRole("button", { name: "Save" }).click(),
    ]);
    expect(savedQuery.status()).toBe(201);
    queryId = (await savedQuery.json()).id;
    const queryPath = `/api/v1/entity-queries/${queryId}`;
    await expect(saveDialog).toBeHidden();

    const savedQueryCombobox = page.getByRole("combobox", { name: "Saved query", exact: true });
    await expect(savedQueryCombobox).toHaveValue(queryName);
    await expect(page.getByText("Modified", { exact: true })).toHaveCount(0);

    // 4. Diverge the draft from the selected saved query without re-running it — the query is a
    // single line, so End reaches the end of the whole document.
    await queryBox.click();
    await page.keyboard.press("End");
    await queryBox.pressSequentially(" LIMIT 1");
    await expect(page.getByText("Modified", { exact: true })).toBeVisible();

    // 5. The Entity hierarchy page shares the same draft/applied query state (localStorage), but
    // the "Saved query" picker's own selection is per-component: re-apply the search filter and
    // pick the saved query there — it resets the draft to the STORED text (discarding the
    // unapplied ` LIMIT 1`) and re-runs it.
    await page.goto("/entity-hierarchy");
    await expect(page.getByRole("heading", { name: "Entity hierarchy" })).toBeVisible();
    await openFilters(page);
    const hierarchyCombobox = page.getByRole("combobox", { name: "Saved query", exact: true });

    const [hierarchyGraphResp] = await Promise.all([
      page.waitForResponse((r) => {
        const url = new URL(r.url());
        return url.pathname === "/api/v1/entities/graph" && r.request().method() === "GET" && url.searchParams.has("query");
      }),
      (async () => {
        await page.getByRole("textbox", { name: "Search" }).fill(run);
        await hierarchyCombobox.click();
        await page.getByRole("option", { name: queryName, exact: true }).click();
      })(),
    ]);
    expect(hierarchyGraphResp.status()).toBe(200);
    await expect(page.getByText(p1Title, { exact: true })).toBeVisible();
    await expect(page.getByText(c1Title, { exact: true })).toBeVisible();
    await expect(page.getByText(c2Title, { exact: true })).toHaveCount(0);

    // 6. Rename + flip to Public from the same (Entity hierarchy) page.
    await page.getByRole("button", { name: "Saved query actions" }).click();
    await page.getByRole("menuitem", { name: "Rename / visibility…" }).click();
    const renameDialog = await readyDialog(page, "Edit query");
    const renamedName = `${queryName}-renamed`;
    await renameDialog.getByLabel("Name").fill(renamedName);
    await renameDialog.getByRole("radio", { name: /Public/ }).click();
    const [updatedQuery] = await Promise.all([
      page.waitForResponse((r) => new URL(r.url()).pathname === queryPath && r.request().method() === "PUT"),
      renameDialog.getByRole("button", { name: "Save" }).click(),
    ]);
    expect(updatedQuery.status()).toBe(204);
    await expect(renameDialog).toBeHidden();
    await expect(hierarchyCombobox).toHaveValue(renamedName);

    // 7. A throwaway regular user (created via the API) picks the now-public query from a
    // SECOND, independent browser context and confirms the actions menu offers no mutation of a
    // query this user does not own — the LensPicker disclosure split, restated for saved queries.
    const otherEmail = `${uniqueText("e2e-eqs-user")}@toadie.local`.toLowerCase();
    const otherPassword = "Throwaway-Pass-1";
    const otherUserResp = await page.request.post("/api/v1/users", {
      headers: authHeaders,
      data: { name: "E2E EQS User", email: otherEmail, password: otherPassword },
    });
    expect(otherUserResp.status()).toBe(201);
    otherUserId = (await otherUserResp.json()).id;

    const otherContext = await browser.newContext();
    try {
      const otherPage = await otherContext.newPage();
      await login(otherPage, otherEmail, otherPassword);
      await otherPage.goto("/entity-graph");
      await expect(otherPage.getByRole("heading", { name: "Entity graph" })).toBeVisible();
      await openFilters(otherPage);
      const otherCombobox = otherPage.getByRole("combobox", { name: "Saved query", exact: true });

      const [otherGraphResp] = await Promise.all([
        otherPage.waitForResponse((r) => {
          const url = new URL(r.url());
          return url.pathname === "/api/v1/entities/graph" && r.request().method() === "GET" && url.searchParams.has("query");
        }),
        (async () => {
          await otherPage.getByRole("textbox", { name: "Search" }).fill(run);
          await otherCombobox.click();
          await otherPage.getByRole("option", { name: `${renamedName} — Admin`, exact: true }).click();
        })(),
      ]);
      expect(otherGraphResp.status()).toBe(200);
      // Two nodes zoom the canvas in far enough for the identifier line to truncate ("e2e…"),
      // so assert on the titles (never clipped) and the node count, not the identifiers.
      await expect(otherPage.locator(".react-flow__node")).toHaveCount(2);
      await expect(otherPage.getByText(p1Title, { exact: true })).toBeVisible();
      await expect(otherPage.getByText(c1Title, { exact: true })).toBeVisible();
      await expect(otherPage.getByText(c2Title, { exact: true })).toHaveCount(0);

      await otherPage.getByRole("button", { name: "Saved query actions" }).click();
      await expect(otherPage.getByRole("menuitem", { name: "Save as new query…" })).toBeVisible();
      await expect(otherPage.getByRole("menuitem", { name: "Save changes" })).toHaveCount(0);
      await expect(otherPage.getByRole("menuitem", { name: "Rename / visibility…" })).toHaveCount(0);
      await expect(otherPage.getByRole("menuitem", { name: "Delete", exact: true })).toHaveCount(0);
      await otherPage.keyboard.press("Escape");
    } finally {
      await otherContext.close();
    }

    // 8. Back in the admin's context (still on Entity hierarchy, still the query's creator):
    // delete the saved query through the confirm modal.
    await page.getByRole("button", { name: "Saved query actions" }).click();
    await page.getByRole("menuitem", { name: "Delete", exact: true }).click();
    const deleteDialog = await readyDialog(page, "Delete saved query?");
    await expect(deleteDialog).toContainText(renamedName);
    const [deletedQuery] = await Promise.all([
      page.waitForResponse((r) => new URL(r.url()).pathname === queryPath && r.request().method() === "DELETE"),
      deleteDialog.getByRole("button", { name: "Delete", exact: true }).click(),
    ]);
    expect(deletedQuery.status()).toBe(204);
    await expect(deleteDialog).toBeHidden();
    await expect(hierarchyCombobox).toHaveValue("");
    queryId = undefined;
  } finally {
    // Cleanup: the saved query (tolerate 404 — it may already be gone via step 8), the children
    // first (c1/c2 reference p1 or nothing), then p1, then the child blueprint (it targets the
    // parent), then the parent blueprint, then the throwaway user.
    if (queryId) {
      const deleted = await page.request.delete(`/api/v1/entity-queries/${queryId}`, { headers: authHeaders });
      expect([204, 404], "cleanup: the saved query may already be gone").toContain(deleted.status());
    }
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
    if (otherUserId) {
      const deleted = await page.request.delete(`/api/v1/users/${otherUserId}`, { headers: authHeaders });
      expect(deleted.status()).toBe(204);
    }
  }
});
