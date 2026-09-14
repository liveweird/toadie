import { expect, login, openQuery, test, uniqueText } from "./helpers";

// The Port-world Ontology Errors report (v2.5.0, `.claude/docs/entity-query-language.md` and
// `.claude/docs/port-data-model.md` "Lifecycle rules"): a workspace-wide sweep over the active
// entity/blueprint/saved-query registries reporting four classes — stale entities, unresolved
// ownership, computed-property health, and broken saved queries. One throwaway blueprint (a
// `name: string` property plus a `calculationProperties.broken` expression that never compiles),
// one entity of it (created before `name` is required, so it starts clean), and one PRIVATE
// saved entity query naming a blueprint that doesn't exist — all seeded via the API under one
// run marker. Making `name` required via a full-replace PUT turns the entity stale; the broken
// calculation and the broken saved query are stale from the moment they are created. Owns only
// its own `e2e-ee-*` blueprint, entity, and saved query — every locator is anchored on their
// unique identifiers/name, so other specs' parallel registry rows (including their own
// momentarily-stale entities) can never make an assertion here ambiguous.
test("stale entities, a broken calculation and a broken saved query land on the Ontology Errors report", async ({
  page,
}) => {
  await login(page);
  const adminToken = await page.evaluate(() => localStorage.getItem("toadie.auth.token"));
  expect(adminToken !== null, "the admin login must provide setup/cleanup authorization").toBe(true);
  const authHeaders = { Authorization: `Bearer ${adminToken}` };

  const run = uniqueText("e2e-ee");
  const bpIdentifier = `${run}-bp`;
  const entityIdentifier = `${run}-e1`;
  const queryName = `${run}-q`;
  const missingLabel = `${run}-nope`;
  const queryText = `MATCH (n:\`${missingLabel}\`) RETURN n`;

  let blueprintId: number | undefined;
  let entityId: number | undefined;
  let queryId: number | undefined;

  try {
    // 0. Seed the throwaway blueprint: a `name` string property (not yet required) plus a
    // calculation whose jq expression never compiles — this finding exists from creation.
    const blueprintResp = await page.request.post("/api/v1/blueprints", {
      headers: authHeaders,
      data: {
        identifier: bpIdentifier,
        title: "E2E EE Blueprint",
        schema: { properties: { name: { type: "string" } }, required: [] },
        calculationProperties: {
          broken: { title: "Broken", type: "string", calculation: "not valid jq ((" },
        },
      },
    });
    expect(blueprintResp.status()).toBe(201);
    blueprintId = (await blueprintResp.json()).id;

    // 1. Seed one entity of it WITHOUT `name` — legal for now, since `name` isn't required yet.
    const entityResp = await page.request.post("/api/v1/entities", {
      headers: authHeaders,
      data: { blueprint: bpIdentifier, identifier: entityIdentifier, title: "E2E EE Entity" },
    });
    expect(entityResp.status()).toBe(201);
    entityId = (await entityResp.json()).id;

    // 2. Save a PRIVATE saved entity query whose MATCH names a blueprint identifier that has
    // never existed — syntactically valid (saved queries are only parse-checked at save time),
    // so it stores, but it can never validate against the active registry.
    const queryResp = await page.request.post("/api/v1/entity-queries", {
      headers: authHeaders,
      data: { name: queryName, visibility: "PRIVATE", query: queryText },
    });
    expect(queryResp.status()).toBe(201);
    queryId = (await queryResp.json()).id;

    // 3. Make `name` required — a blueprint PUT is a full replace, so read the current
    // definition back first and resend it with `schema.required` added (keeping the broken
    // calculation, or that finding would vanish along with everything else the PUT omits). This
    // turns the seeded entity stale.
    const blueprintGetResp = await page.request.get(`/api/v1/blueprints/${blueprintId}`, { headers: authHeaders });
    expect(blueprintGetResp.status()).toBe(200);
    const stored = await blueprintGetResp.json();
    const staleResp = await page.request.put(`/api/v1/blueprints/${blueprintId}`, {
      headers: authHeaders,
      data: {
        identifier: bpIdentifier,
        title: stored.title,
        schema: { properties: stored.schema.properties, required: ["name"] },
        calculationProperties: stored.calculationProperties,
      },
    });
    expect(staleResp.status()).toBe(204);

    // 4. Open the Ontology Errors report — the entity, blueprint, and saved-query rows are all
    // present, each scoped by row so a parallel spec's own findings can never collide.
    await page.goto("/ontology/errors");
    await expect(page.getByRole("heading", { name: "Errors", exact: true })).toBeVisible();

    const entityLink = page.getByRole("link", { name: `Edit ${entityIdentifier}` });
    await expect(entityLink).toBeVisible();
    const entityRow = page.locator("tr", { has: entityLink });
    await expect(entityRow.getByText("Required property missing", { exact: true })).toBeVisible();

    const blueprintLink = page.getByRole("link", { name: `Edit ${bpIdentifier}` });
    await expect(blueprintLink).toBeVisible();
    const blueprintRow = page.locator("tr", { has: blueprintLink });
    await expect(blueprintRow.getByText("Calculation does not compile", { exact: true })).toBeVisible();

    const queryNameText = page.getByText(queryName, { exact: true });
    await expect(queryNameText).toBeVisible();
    const queryRow = page.locator("tr", { has: queryNameText });
    await expect(queryRow.getByText("Unknown blueprint", { exact: true })).toBeVisible();

    // 5. Toggle the "Stale" class chip off: the entity row (its one finding is class "stale")
    // disappears entirely, while the blueprint and saved-query rows stay. Toggling it back on
    // restores it.
    await page.getByText("Stale", { exact: true }).click();
    await expect(entityLink).toHaveCount(0);
    await expect(blueprintLink).toBeVisible();
    await expect(queryNameText).toBeVisible();
    await page.getByText("Stale", { exact: true }).click();
    await expect(entityLink).toBeVisible();

    // 6. "Open in graph" on the saved-query row hands its text to the Entity graph's shared
    // query bar and runs it there; the graph 400s (the blueprint still doesn't exist) and the
    // bar's diagnostics name the same missing label.
    const [graphResp] = await Promise.all([
      page.waitForResponse((r) => {
        const url = new URL(r.url());
        return url.pathname === "/api/v1/entities/graph" && r.request().method() === "GET" && url.searchParams.has("query");
      }),
      queryRow.getByRole("button", { name: "Open in graph" }).click(),
    ]);
    expect(graphResp.status()).toBe(400);
    await expect(page).toHaveURL(/\/entity-graph$/);
    await openQuery(page);
    await expect(page.getByRole("textbox", { name: "Entity query" })).toContainText(queryText);
    const diagnosticsList = page.getByRole("list", { name: "Query problems" });
    await expect(diagnosticsList.getByText(missingLabel)).toBeVisible();
  } finally {
    // Cleanup: the saved query, then the entity, then the blueprint (the entity references it).
    if (queryId) {
      const deleted = await page.request.delete(`/api/v1/entity-queries/${queryId}`, { headers: authHeaders });
      expect(deleted.status()).toBe(204);
    }
    if (entityId) {
      const deleted = await page.request.delete(`/api/v1/entities/${entityId}`, { headers: authHeaders });
      expect(deleted.status()).toBe(204);
    }
    if (blueprintId) {
      const deleted = await page.request.delete(`/api/v1/blueprints/${blueprintId}`, { headers: authHeaders });
      expect(deleted.status(), "cleanup: delete the entity before its blueprint").toBe(204);
    }
  }
});
