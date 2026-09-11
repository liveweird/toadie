import { readFileSync } from "node:fs";
import { expect, login, test, uniqueText } from "./helpers";

// The ontology bulk-import page (Phase 6, v1.28.0 — `.claude/docs/port-data-model.md` "Import
// and export"): a mixed Toadie-shaped batch (two blueprints forming a relation/aggregation
// CYCLE — A's mirror+aggregation forward-reference B, B's relation forward-references A, so the
// two-pass deferral trick fires; two entities in deliberately reversed order; one entity naming
// an unknown blueprint) -> Check (dry run, nothing stored) -> Import (real) -> re-import reports
// Already exists -> edit + Replace-existing reports Updated -> Export JSON from both the
// Blueprints and Entities pages -> pasting both exports back in round-trips as Already exists.
//
// One documented deviation from a literal "nothing exists yet" prediction: `POST
// /entities/import(/check)` resolves `blueprint` against the ACTUAL stored registry only (never
// against a sibling `/blueprints/import` call's own pending batch — the two are separate
// requests, `.claude/docs/persistence.md` "Reads are plain, uncoordinated snapshots"), so at the
// CHECK step — before the blueprint half has stored anything — the two entities targeting the
// brand-new blueprints A/B report Invalid "Unknown blueprint" exactly like the deliberately
// unresolvable `bad` document; they only become Created once the real Import has stored A/B
// first (verified empirically against the compose stack before writing this spec).
//
// The blueprint registry is shared run-state and this spec is a FIFTH in-run writer (alongside
// blueprints.spec.ts/entities.spec.ts/entity-graph.spec.ts/entity-hierarchy.spec.ts) — it only
// ever creates and deletes its own unique `e2e-oi-bp-*` blueprints and `e2e-oi-ent-*` entities.
test("a mixed batch imports with a two-pass blueprint cycle, then round-trips through export", async ({
  page,
}) => {
  await login(page);
  const adminToken = await page.evaluate(() => localStorage.getItem("toadie.auth.token"));
  expect(adminToken !== null, "the admin login must provide setup/cleanup authorization").toBe(true);
  const authHeaders = { Authorization: `Bearer ${adminToken}` };

  // Two independent unique markers for the blueprints (never one derived as a substring of the
  // other — the blueprints.spec.ts rule) plus one that is never created at all.
  const aId = uniqueText("e2e-oi-bp-a");
  const bId = uniqueText("e2e-oi-bp-b");
  const missingBpId = uniqueText("e2e-oi-bp-x");
  const a1Id = uniqueText("e2e-oi-ent-a1");
  const b1Id = uniqueText("e2e-oi-ent-b1");
  const badId = uniqueText("e2e-oi-ent-bad");

  function payload(aTitle: string) {
    return {
      blueprints: [
        {
          identifier: aId,
          title: aTitle,
          // Read-only/response-only noise a Port export or a re-imported Toadie export can
          // carry — stripped client-side before anything reaches the server.
          createdAt: "2020-01-01T00:00:00Z",
          _meta: {},
          schema: { properties: {}, required: [] },
          // A's own relation forward-references B (declared BELOW) — together with B's "peer"
          // relation back to A, this is a genuine 2-cycle: whichever blueprint the planner
          // stores first must defer everything targeting the other.
          relations: { sibling: { title: "Sibling", target: bId, required: false, many: false } },
          // The mirror's path starts with "sibling" — stripped alongside that relation and
          // restored together with it in the same pass-2 write.
          mirrorProperties: { siblingTitle: { title: "Sibling title", path: "sibling.$title" } },
          // The forward aggregation: targets B, declared after A in this very array.
          aggregationProperties: {
            peerCount: {
              title: "Peer count",
              target: bId,
              calculationSpec: { calculationBy: "entities", func: "count" },
            },
          },
        },
        {
          identifier: bId,
          title: "E2E OI Blueprint B",
          schema: { properties: {}, required: [] },
          relations: { peer: { title: "Peer", target: aId, required: false, many: false } },
        },
      ],
      entities: [
        // Out of order on purpose: b1 relates to a1 but is listed BEFORE it.
        { blueprint: bId, identifier: b1Id, title: "E2E OI Entity B1", properties: {}, relations: { peer: a1Id } },
        { blueprint: aId, identifier: a1Id, title: "E2E OI Entity A1", properties: {}, relations: {} },
        // Names a blueprint that is never created — always Invalid, in every step below.
        { blueprint: missingBpId, identifier: badId, title: "E2E OI Bad Entity", properties: {}, relations: {} },
      ],
    };
  }

  // The nav's Port Ontology section — scoped explicitly because, once the Blueprints page has
  // rendered its own per-row "Entities" link (`viewEntities`, a plain per-blueprint navigation
  // link with the SAME bare text) and this shared registry carries many other blueprints, a
  // bare `getByRole("link", { name: "Entities" })` matches every one of those rows too.
  const portOntologyNav = page.getByRole("group", { name: "Port Ontology" });
  const textarea = page.getByRole("textbox", { name: "JSON content" });
  const checkButton = page.getByRole("button", { name: "Check", exact: true });
  const importButton = page.getByRole("button", { name: "Import", exact: true });
  const replaceSwitch = page.getByRole("switch", { name: "Replace existing definitions" });

  async function setReplaceExisting(checked: boolean) {
    if ((await replaceSwitch.isChecked()) !== checked) await replaceSwitch.click();
    if (checked) await expect(replaceSwitch).toBeChecked();
    else await expect(replaceSwitch).not.toBeChecked();
  }

  // Rows are disambiguated the round-trip.spec.ts way: an entity row's identifier cell also
  // contains its blueprint's identifier as a dimmed prefix, so a bare blueprint-identifier
  // filter would ALSO match that entity's row — exclude it explicitly.
  const aRow = () => page.getByRole("row").filter({ hasText: aId }).filter({ hasNotText: a1Id });
  const bRow = () => page.getByRole("row").filter({ hasText: bId }).filter({ hasNotText: b1Id });
  const a1Row = () => page.getByRole("row").filter({ hasText: a1Id });
  const b1Row = () => page.getByRole("row").filter({ hasText: b1Id });
  const badRow = () => page.getByRole("row").filter({ hasText: badId });

  let aBlueprintId: number | undefined;
  let bBlueprintId: number | undefined;
  let a1EntityId: number | undefined;
  let b1EntityId: number | undefined;

  try {
    // 1. Open Import from the Port Ontology nav.
    await portOntologyNav.getByRole("link", { name: "Import", exact: true }).click();
    await expect(page.getByRole("heading", { name: "Import ontology" })).toBeVisible();
    await expect(replaceSwitch).not.toBeChecked();
    await expect(importButton).toBeDisabled();

    // 2. Paste the mixed batch.
    await textarea.fill(JSON.stringify(payload("E2E OI Blueprint A"), null, 2));
    await expect(page.getByText("2 blueprints, 3 entities ready")).toBeVisible();
    await expect(page.getByText("Ignored read-only keys in 1 documents: _meta, createdAt")).toBeVisible();

    // 3. Check: nothing is stored. The two blueprints predict Created; the two entities that
    // target them predict Invalid (see the header comment) alongside the deliberately unknown
    // `bad` document — all three share the "Unknown blueprint" message.
    await Promise.all([
      page.waitForResponse((r) => r.url().endsWith("/api/v1/blueprints/import/check") && r.ok()),
      checkButton.click(),
    ]);
    await page.waitForResponse((r) => r.url().endsWith("/api/v1/entities/import/check") && r.ok());
    await expect(aRow().getByText("Would be created", { exact: true })).toBeVisible();
    await expect(bRow().getByText("Would be created", { exact: true })).toBeVisible();
    await expect(a1Row().getByText("Invalid", { exact: true })).toBeVisible();
    await expect(a1Row().getByText("Unknown blueprint")).toBeVisible();
    await expect(b1Row().getByText("Invalid", { exact: true })).toBeVisible();
    await expect(badRow().getByText("Invalid", { exact: true })).toBeVisible();
    await expect(badRow().getByText("Unknown blueprint")).toBeVisible();

    const registryCheck = await page.request.get("/api/v1/blueprints", { headers: authHeaders });
    expect(registryCheck.status()).toBe(200);
    const registryIdentifiers = ((await registryCheck.json()).items as { identifier: string }[]).map((b) => b.identifier);
    expect(registryIdentifiers).not.toContain(aId);
    expect(registryIdentifiers).not.toContain(bId);

    // 4. Import for real: the blueprint cycle resolves in two passes, then the entities (now
    // that A/B actually exist) both land, and `bad` still fails.
    const [blueprintImportResp] = await Promise.all([
      page.waitForResponse((r) => r.url().endsWith("/api/v1/blueprints/import") && r.ok()),
      importButton.click(),
    ]);
    const entityImportResp = await page.waitForResponse((r) => r.url().endsWith("/api/v1/entities/import") && r.ok());
    const blueprintResults = (await blueprintImportResp.json()).results as { identifier: string; id: number }[];
    aBlueprintId = blueprintResults.find((r) => r.identifier === aId)?.id;
    bBlueprintId = blueprintResults.find((r) => r.identifier === bId)?.id;
    const entityResults = (await entityImportResp.json()).results as { identifier: string; id?: number }[];
    a1EntityId = entityResults.find((r) => r.identifier === a1Id)?.id;
    b1EntityId = entityResults.find((r) => r.identifier === b1Id)?.id;
    expect(aBlueprintId, "blueprint A must have stored").toBeDefined();
    expect(bBlueprintId, "blueprint B must have stored").toBeDefined();
    expect(a1EntityId, "entity a1 must have stored").toBeDefined();
    expect(b1EntityId, "entity b1 must have stored").toBeDefined();

    await expect(aRow().getByText("Created", { exact: true })).toBeVisible();
    await expect(bRow().getByText("Created", { exact: true })).toBeVisible();
    await expect(a1Row().getByText("Created", { exact: true })).toBeVisible();
    await expect(b1Row().getByText("Created", { exact: true })).toBeVisible();
    await expect(badRow().getByText("Invalid", { exact: true })).toBeVisible();
    await expect(aRow().getByRole("link", { name: `Edit ${aId}`, exact: true })).toBeVisible();
    await expect(bRow().getByRole("link", { name: `Edit ${bId}`, exact: true })).toBeVisible();
    await expect(a1Row().getByRole("link", { name: `Edit ${aId} / ${a1Id}`, exact: true })).toBeVisible();
    await expect(b1Row().getByRole("link", { name: `Edit ${bId} / ${b1Id}`, exact: true })).toBeVisible();

    const aGetAfterImport = await page.request.get(`/api/v1/blueprints/${aBlueprintId}`, { headers: authHeaders });
    expect(aGetAfterImport.status()).toBe(200);
    const aBodyAfterImport = await aGetAfterImport.json();
    expect(aBodyAfterImport.aggregationProperties.peerCount.target).toBe(bId);
    expect(aBodyAfterImport.relations.sibling.target).toBe(bId);

    const b1GetAfterImport = await page.request.get(`/api/v1/entities/${b1EntityId}`, { headers: authHeaders });
    expect(b1GetAfterImport.status()).toBe(200);
    expect((await b1GetAfterImport.json()).relations.peer).toBe(a1Id);

    // 5. Import the SAME batch again, switch off: all four now report Already exists (gray);
    // `bad` is still Invalid.
    await Promise.all([
      page.waitForResponse((r) => r.url().endsWith("/api/v1/blueprints/import") && r.ok()),
      importButton.click(),
    ]);
    await page.waitForResponse((r) => r.url().endsWith("/api/v1/entities/import") && r.ok());
    await expect(aRow().getByText("Already exists", { exact: true })).toBeVisible();
    await expect(bRow().getByText("Already exists", { exact: true })).toBeVisible();
    await expect(a1Row().getByText("Already exists", { exact: true })).toBeVisible();
    await expect(b1Row().getByText("Already exists", { exact: true })).toBeVisible();
    await expect(badRow().getByText("Invalid", { exact: true })).toBeVisible();

    // 6. Edit A's title, switch Replace-existing ON, import again: all four report Updated.
    const newATitle = "E2E OI Blueprint A (renamed)";
    await textarea.fill(JSON.stringify(payload(newATitle), null, 2));
    await expect(page.getByText("2 blueprints, 3 entities ready")).toBeVisible();
    await setReplaceExisting(true);
    await Promise.all([
      page.waitForResponse((r) => r.url().endsWith("/api/v1/blueprints/import") && r.ok()),
      importButton.click(),
    ]);
    await page.waitForResponse((r) => r.url().endsWith("/api/v1/entities/import") && r.ok());
    await expect(aRow().getByText("Updated", { exact: true })).toBeVisible();
    await expect(bRow().getByText("Updated", { exact: true })).toBeVisible();
    await expect(a1Row().getByText("Updated", { exact: true })).toBeVisible();
    await expect(b1Row().getByText("Updated", { exact: true })).toBeVisible();

    const aGetAfterRename = await page.request.get(`/api/v1/blueprints/${aBlueprintId}`, { headers: authHeaders });
    expect((await aGetAfterRename.json()).title).toBe(newATitle);

    // 7. Blueprints page: Export JSON, then confirm A and B are both present, response-only
    // keys are gone, and A's aggregation survived the round trip.
    await portOntologyNav.getByRole("link", { name: "Blueprints", exact: true }).click();
    await expect(page.getByRole("heading", { name: "Blueprints" })).toBeVisible();
    const [blueprintsDownload] = await Promise.all([
      page.waitForEvent("download"),
      page.getByRole("button", { name: "Export JSON" }).click(),
    ]);
    const blueprintsExportText = readFileSync((await blueprintsDownload.path())!, "utf8");
    const blueprintsExport = JSON.parse(blueprintsExportText) as { blueprints: Record<string, any>[] };
    const exportedA = blueprintsExport.blueprints.find((b) => b.identifier === aId);
    const exportedB = blueprintsExport.blueprints.find((b) => b.identifier === bId);
    expect(exportedA, "the export must contain blueprint A").toBeDefined();
    expect(exportedB, "the export must contain blueprint B").toBeDefined();
    for (const key of ["id", "system", "createdAt", "createdBy", "updatedAt", "creatorName", "creatorDeleted"]) {
      expect(exportedA).not.toHaveProperty(key);
      expect(exportedB).not.toHaveProperty(key);
    }
    expect(exportedA!.aggregationProperties.peerCount.target).toBe(bId);

    // 8. Entities page, blueprint A: Export JSON, then confirm a1 is present with none of the
    // response-only/computed keys — A's own mirror value ("siblingTitle") must not be carried.
    await portOntologyNav.getByRole("link", { name: "Entities", exact: true }).click();
    await expect(page.getByRole("heading", { name: "Entities" })).toBeVisible();
    const blueprintSelect = page.getByRole("combobox", { name: "Blueprint" });
    await blueprintSelect.click();
    await blueprintSelect.fill(aId);
    await page.getByRole("option", { name: aId, exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`blueprint=${aId}`));
    const [entitiesDownload] = await Promise.all([
      page.waitForEvent("download"),
      page.getByRole("button", { name: "Export JSON" }).click(),
    ]);
    const entitiesExportText = readFileSync((await entitiesDownload.path())!, "utf8");
    const entitiesExport = JSON.parse(entitiesExportText) as { entities: Record<string, any>[] };
    const exportedA1 = entitiesExport.entities.find((e) => e.identifier === a1Id);
    expect(exportedA1, "the export must contain entity a1").toBeDefined();
    for (const key of ["findings", "blueprintId", "id"]) {
      expect(exportedA1).not.toHaveProperty(key);
    }
    expect(exportedA1!.properties).not.toHaveProperty("siblingTitle");
    expect(exportedA1!.properties).not.toHaveProperty("peerCount");

    // 9. Round trip: back on Import, paste the blueprints export, add the entities export as a
    // picked file, switch off, and import again — everything that exists (A, B, a1) reports
    // Already exists, and the Source column appears because there are now two sources.
    await portOntologyNav.getByRole("link", { name: "Import", exact: true }).click();
    await expect(page.getByRole("heading", { name: "Import ontology" })).toBeVisible();
    await textarea.fill(blueprintsExportText);
    const fileInput = page.locator('input[type="file"]');
    await fileInput.setInputFiles((await entitiesDownload.path())!);
    await setReplaceExisting(false);
    await expect(importButton).toBeEnabled();
    await Promise.all([
      page.waitForResponse((r) => r.url().endsWith("/api/v1/blueprints/import") && r.ok()),
      importButton.click(),
    ]);
    await page.waitForResponse((r) => r.url().endsWith("/api/v1/entities/import") && r.ok());
    await expect(page.getByRole("columnheader", { name: "Source" })).toBeVisible();
    await expect(aRow().getByText("Already exists", { exact: true })).toBeVisible();
    await expect(bRow().getByText("Already exists", { exact: true })).toBeVisible();
    await expect(a1Row().getByText("Already exists", { exact: true })).toBeVisible();
  } finally {
    // PUT A without its relation/mirror/aggregation FIRST — this breaks the A-aggregates/
    // relates-to-B vs B-relates-to-A cycle so both blueprints can be deleted afterward.
    if (aBlueprintId) {
      const restoredA = await page.request.put(`/api/v1/blueprints/${aBlueprintId}`, {
        headers: authHeaders,
        data: { identifier: aId, title: "E2E OI Blueprint A", schema: { properties: {}, required: [] } },
      });
      expect(restoredA.status(), "cleanup: strip A's forward references before deleting B").toBe(204);
    }
    // Entities next — the referring one (b1) before the referenced one (a1).
    if (b1EntityId) {
      const deletedB1 = await page.request.delete(`/api/v1/entities/${b1EntityId}`, { headers: authHeaders });
      expect(deletedB1.status(), "cleanup: delete the referring entity first").toBe(204);
    }
    if (a1EntityId) {
      const deletedA1 = await page.request.delete(`/api/v1/entities/${a1EntityId}`, { headers: authHeaders });
      expect(deletedA1.status()).toBe(204);
    }
    // Blueprints last — B (still targeted by nothing once A is stripped) before A (still
    // targeted by B's own "peer" relation until B is gone).
    if (bBlueprintId) {
      const deletedB = await page.request.delete(`/api/v1/blueprints/${bBlueprintId}`, { headers: authHeaders });
      expect(deletedB.status(), "cleanup: delete B before A").toBe(204);
    }
    if (aBlueprintId) {
      const deletedA = await page.request.delete(`/api/v1/blueprints/${aBlueprintId}`, { headers: authHeaders });
      expect(deletedA.status()).toBe(204);
    }
  }
});
