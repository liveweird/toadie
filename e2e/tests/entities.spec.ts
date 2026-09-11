import { createTeamEntity, expect, login, readyDialog, test, uniqueText } from "./helpers";

// Instances of a blueprint (Port migration phase 2): two throwaway blueprints are seeded via
// the API (a target carrying typed properties AND Direct ownership, a dependent carrying a
// required relation to it) -> the Entities list's blueprint picker (?blueprint=) and
// New-entity validation -> an entity is created with an enum/number/boolean property and a
// team picked in the "Owned by" MultiSelect (Phase 4 ownership, v1.26.0), watching the JSON
// preview -> the row's Team chip, the toolbar Team filter (?team=), the owned team's blocked
// (409) then unblocked (204, after unlinking in the editor) delete -> a second entity relates
// to the first -> deleting the referenced entity is blocked, naming the referrer -> editing
// the target blueprint to add a required property turns the first entity STALE (the list's
// findings badge) -> the editor's stale alert names the missing field, fixed and saved, clears
// it -> computed properties (phase 5, v1.27.0): the target blueprint gains a colorized
// calculation mirroring its own tier property and an aggregation counting entities of the
// dependent blueprint that relate to it, and the dependent blueprint gains a mirror reading the
// target's tier through its existing relation -> the dependent's list preview column and its
// editor's read-only Computed section show the mirrored value -> the target entity's editor
// shows the calculation and the aggregation count, the JSON preview strips all three computed
// ids, and the save still succeeds -> cleanup (entities, then blueprints, the target blueprint's
// computed properties removed first to break the aggregation-created delete cycle). The feature
// has no admin gate, so the whole journey runs as the seed admin (no throwaway user needed). The
// blueprint registry is shared
// run-state and THIS SPEC IS ONE OF ITS TWO IN-RUN WRITERS (alongside blueprints.spec.ts) — it
// only ever creates and deletes its own unique `e2e-ent-bp-*` blueprints, `e2e-ent-*` entities,
// and one throwaway `e2e-ent-team-*` `_team` entity (never a foreign `_team`/`_user` row).
test("an entity is created from a blueprint, a relation blocks its deletion, and a blueprint change makes it stale until fixed", async ({
  page,
}) => {
  await login(page);
  const adminToken = await page.evaluate(() => localStorage.getItem("toadie.auth.token"));
  expect(adminToken !== null, "the admin login must provide setup/cleanup authorization").toBe(true);
  const authHeaders = { Authorization: `Bearer ${adminToken}` };

  // Two INDEPENDENT unique markers (the blueprints.spec.ts rule) — a derived "<target>-dep"
  // identifier would make locators matching by substring ambiguous between the two.
  const targetIdentifier = uniqueText("e2e-ent-bp");
  const depIdentifier = uniqueText("e2e-ent-bp-dep");
  const entityAIdentifier = uniqueText("e2e-ent-a");
  const entityBIdentifier = uniqueText("e2e-ent-b");
  const entityATitle = "E2E Entity A";
  const entityBTitle = "E2E Entity B";
  // A throwaway _team entity (Phase 4 ownership) — the target blueprint's `ownership` below
  // makes its entities Direct-owned, so entity A can name this team.
  const teamIdentifier = uniqueText("e2e-ent-team");
  const teamTitle = "E2E Entity Team";

  const targetProperties = {
    tier: { type: "string", title: "Tier", enum: ["gold", "silver"] },
    replicas: { type: "number", title: "Replicas" },
    public: { type: "boolean", title: "Public" },
  };

  let targetBlueprintId: number | undefined;
  let depBlueprintId: number | undefined;
  let entityAId: number | undefined;
  let entityBId: number | undefined;
  let teamEntityId: number | undefined;
  // Set once the computed-property PUTs below land, so `finally` knows to restore the target
  // blueprint's pre-computed definition before the ordinary entity/blueprint cleanup.
  let computedAdded = false;

  try {
    // 0. Seed the two throwaway blueprints via the API (not the editor — this spec's subject
    // is entities, not blueprint curation).
    const targetBlueprintResp = await page.request.post("/api/v1/blueprints", {
      headers: authHeaders,
      data: {
        identifier: targetIdentifier,
        title: "E2E Entity Target",
        schema: { properties: targetProperties, required: ["replicas"] },
        // Direct/absent ownership (the default) lets its entities carry a `team` — the
        // MultiSelect's label follows this title instead of the generic "Team" fallback.
        ownership: { type: "Direct", title: "Owned by" },
      },
    });
    expect(targetBlueprintResp.status()).toBe(201);
    targetBlueprintId = (await targetBlueprintResp.json()).id;

    const depBlueprintResp = await page.request.post("/api/v1/blueprints", {
      headers: authHeaders,
      data: {
        identifier: depIdentifier,
        title: "E2E Entity Dependent",
        schema: { properties: {}, required: [] },
        relations: {
          parent: { title: "Parent", target: targetIdentifier, required: true, many: false },
        },
      },
    });
    expect(depBlueprintResp.status()).toBe(201);
    depBlueprintId = (await depBlueprintResp.json()).id;

    // A throwaway _team entity, seeded via the API like blueprints.spec.ts seeds its own
    // fixtures — this spec's subject is entity ownership, not team curation. `createTeamEntity`
    // satisfies whatever `_team`'s current schema requires (an environment carrying the sample
    // ontology extension may add required properties beyond the seeded base shape).
    teamEntityId = await createTeamEntity(page.request, adminToken!, teamIdentifier, teamTitle);

    // 1. Open Entities from the nav's Port Ontology section and pick the target blueprint in
    // the toolbar Select — the URL gets ?blueprint=.
    await page.getByRole("link", { name: "Entities" }).click();
    await expect(page.getByRole("heading", { name: "Entities" })).toBeVisible();

    const blueprintSelect = page.getByRole("combobox", { name: "Blueprint" });
    await blueprintSelect.click();
    await blueprintSelect.fill(targetIdentifier);
    await page.getByRole("option", { name: targetIdentifier, exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`blueprint=${targetIdentifier}`));

    // 2. New entity, submit empty: identifier/title/required-property errors render inline
    // and nothing navigates away.
    await page.getByRole("link", { name: "New entity" }).click();
    const identifierInput = page.getByRole("textbox", { name: "Identifier" });
    await expect(identifierInput).toBeVisible();
    await page.getByRole("button", { name: "Create" }).click();
    await expect(page.getByText("Enter a valid identifier", { exact: false })).toBeVisible();
    await expect(page.getByText("Enter a title (max 200 characters)")).toBeVisible();
    const propertiesGroup = page.getByRole("group", { name: "Properties" });
    await expect(propertiesGroup.getByText("Required")).toBeVisible();
    await expect(page).toHaveURL(/\/entities\/new\?blueprint=.+$/);

    // 3. Fill identity and properties: a string enum, a required number, and a boolean.
    await identifierInput.fill(entityAIdentifier);
    await page.getByRole("textbox", { name: "Title" }).fill(entityATitle);

    const tierSelect = propertiesGroup.getByRole("combobox", { name: "Tier" });
    await tierSelect.click();
    await page.getByRole("option", { name: "gold", exact: true }).click();

    await page.getByLabel("Replicas").fill("3");

    const publicSelect = propertiesGroup.getByRole("combobox", { name: "Public" });
    await publicSelect.click();
    await page.getByRole("option", { name: "True", exact: true }).click();

    // Phase 4 ownership: the target blueprint's `ownership.title` labels the team MultiSelect
    // "Owned by"; options read "identifier — title".
    const ownedBySelect = page.getByRole("combobox", { name: "Owned by" });
    await ownedBySelect.click();
    await ownedBySelect.fill(teamIdentifier);
    await page.getByRole("option", { name: `${teamIdentifier} — ${teamTitle}`, exact: true }).click();
    await page.keyboard.press("Escape");

    const preview = page.getByLabel("JSON preview");
    await expect(preview).toContainText('"replicas": 3');

    const [createAResp] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().endsWith("/api/v1/entities") && r.request().method() === "POST" && r.ok(),
      ),
      page.getByRole("button", { name: "Create" }).click(),
    ]);
    entityAId = (await createAResp.json()).id;
    await expect(page).toHaveURL(/\/entities\?blueprint=.+$/);

    const entityARow = page.getByRole("row").filter({ hasText: entityAIdentifier });
    await expect(entityARow).toBeVisible();
    await expect(entityARow.getByText("gold")).toBeVisible();
    await expect(entityARow.getByText("3", { exact: true })).toBeVisible();
    await expect(entityARow.getByText("True")).toBeVisible();
    await expect(entityARow.getByText(/\d+ findings?/)).toHaveCount(0);
    await expect(entityARow.getByText(teamIdentifier, { exact: true })).toBeVisible();

    // 3b. The toolbar Team filter narrows the list to `?team=` and the owned entity still
    // shows; deleting the team it owns is refused (409) naming the referrer; unlinking it in
    // the editor and saving lets the delete through.
    const teamFilterSelect = page.getByRole("combobox", { name: "Team", exact: true });
    await teamFilterSelect.click();
    await teamFilterSelect.fill(teamIdentifier);
    await page.getByRole("option", { name: `${teamIdentifier} — ${teamTitle}`, exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`team=${teamIdentifier}`));
    await expect(entityARow).toBeVisible();

    const blockedTeamDelete = await page.request.delete(`/api/v1/entities/${teamEntityId}`, {
      headers: authHeaders,
    });
    expect(blockedTeamDelete.status()).toBe(409);
    const blockedTeamBody = await blockedTeamDelete.json();
    expect(blockedTeamBody.detail).toContain(`${targetIdentifier}/${entityAIdentifier}`);

    await page.getByRole("button", { name: `Edit ${entityAIdentifier}` }).click();
    const ownedByEditSelect = page.getByRole("combobox", { name: "Owned by" });
    await ownedByEditSelect.click();
    await ownedByEditSelect.fill(teamIdentifier);
    await page.getByRole("option", { name: `${teamIdentifier} — ${teamTitle}`, exact: true }).click();
    await page.keyboard.press("Escape");
    await Promise.all([
      page.waitForResponse(
        (r) => r.url().endsWith(`/api/v1/entities/${entityAId}`) && r.request().method() === "PUT" && r.ok(),
      ),
      page.getByRole("button", { name: "Save" }).click(),
    ]);
    await expect(page).toHaveURL(/\/entities\?blueprint=.+$/);

    const unblockedTeamDelete = await page.request.delete(`/api/v1/entities/${teamEntityId}`, {
      headers: authHeaders,
    });
    expect(unblockedTeamDelete.status()).toBe(204);
    teamEntityId = undefined;

    // 4. Pick the dependent blueprint and create a second entity whose relation targets the
    // first — the Select shows "identifier — title".
    await blueprintSelect.click();
    await blueprintSelect.fill(depIdentifier);
    await page.getByRole("option", { name: depIdentifier, exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`blueprint=${depIdentifier}`));

    await page.getByRole("link", { name: "New entity" }).click();
    await expect(identifierInput).toBeVisible();
    await identifierInput.fill(entityBIdentifier);
    await page.getByRole("textbox", { name: "Title" }).fill(entityBTitle);

    const relationsGroup = page.getByRole("group", { name: "Relations" });
    const parentSelect = relationsGroup.getByRole("combobox", { name: "Parent" });
    await parentSelect.click();
    await parentSelect.fill(entityAIdentifier);
    const parentOptionLabel = `${entityAIdentifier} — ${entityATitle}`;
    await page.getByRole("option", { name: parentOptionLabel, exact: true }).click();
    await expect(parentSelect).toHaveValue(parentOptionLabel);

    const [createBResp] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().endsWith("/api/v1/entities") && r.request().method() === "POST" && r.ok(),
      ),
      page.getByRole("button", { name: "Create" }).click(),
    ]);
    entityBId = (await createBResp.json()).id;
    await expect(page).toHaveURL(/\/entities\?blueprint=.+$/);

    // 5. Back on the target blueprint, deleting the referenced entity is refused with the
    // 409 naming the referrer.
    await page.goto(`/entities?blueprint=${encodeURIComponent(targetIdentifier)}`);
    await expect(entityARow).toBeVisible();
    await page.getByRole("button", { name: `Delete ${entityAIdentifier}` }).click();
    await readyDialog(page, "Delete entity");
    const [blockedDelete] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().endsWith(`/api/v1/entities/${entityAId}`) && r.request().method() === "DELETE",
      ),
      page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click(),
    ]);
    expect(blockedDelete.status()).toBe(409);
    await expect(
      page.getByRole("dialog").getByText("This entity is still targeted by another entity's relation."),
    ).toBeVisible();
    await page.getByRole("dialog").getByRole("button", { name: "Cancel", exact: true }).click();
    await expect(entityARow).toBeVisible();

    // 6. Edit the target blueprint (API) to add a required "owner" property — entities go
    // stale, never re-validated until their next save.
    const updateResp = await page.request.put(`/api/v1/blueprints/${targetBlueprintId}`, {
      headers: authHeaders,
      data: {
        identifier: targetIdentifier,
        title: "E2E Entity Target",
        schema: {
          properties: { ...targetProperties, owner: { type: "string" } },
          required: ["replicas", "owner"],
        },
      },
    });
    expect(updateResp.status()).toBe(204);

    // 7. Reload: the now-stale first entity carries a findings badge.
    await page.goto(`/entities?blueprint=${encodeURIComponent(targetIdentifier)}`);
    await expect(entityARow.getByText("1 finding")).toBeVisible();

    // 8. Edit it: the stale alert names the missing field; fill it and save; the badge clears.
    await page.getByRole("button", { name: `Edit ${entityAIdentifier}` }).click();
    await expect(page.getByText("This entity is out of date with its blueprint")).toBeVisible();
    await expect(page.getByText("properties.owner", { exact: false })).toBeVisible();
    await page.getByRole("textbox", { name: "owner" }).fill("platform-team");
    await Promise.all([
      page.waitForResponse(
        (r) => r.url().endsWith(`/api/v1/entities/${entityAId}`) && r.request().method() === "PUT" && r.ok(),
      ),
      page.getByRole("button", { name: "Save" }).click(),
    ]);
    await expect(page).toHaveURL(/\/entities\?blueprint=.+$/);
    await expect(entityARow.getByText(/\d+ findings?/)).toHaveCount(0);

    // 9. Computed properties (phase 5, v1.27.0), via the API — this spec's subject is entity
    // computed VALUES, not blueprint curation. The target blueprint (its step-6 schema, since
    // PUT is a full replace) gains a colorized calculation reading its own `tier` property and
    // an aggregation counting the dependent blueprint's entities that relate to it; the
    // dependent blueprint (its original definition) gains a mirror reading the target's `tier`
    // through the existing `parent` relation.
    const targetSchemaWithOwner = {
      properties: { ...targetProperties, owner: { type: "string" } },
      required: ["replicas", "owner"],
    };
    const computedTargetResp = await page.request.put(`/api/v1/blueprints/${targetBlueprintId}`, {
      headers: authHeaders,
      data: {
        identifier: targetIdentifier,
        title: "E2E Entity Target",
        schema: targetSchemaWithOwner,
        calculationProperties: {
          tier_badge: {
            title: "Tier badge",
            type: "string",
            calculation: ".properties.tier",
            colorized: true,
            colors: { gold: "gold", silver: "silver" },
          },
        },
        aggregationProperties: {
          dependents: {
            title: "Dependents",
            target: depIdentifier,
            calculationSpec: { calculationBy: "entities", func: "count" },
          },
        },
      },
    });
    expect(computedTargetResp.status()).toBe(204);
    computedAdded = true;

    const computedDepResp = await page.request.put(`/api/v1/blueprints/${depBlueprintId}`, {
      headers: authHeaders,
      data: {
        identifier: depIdentifier,
        title: "E2E Entity Dependent",
        schema: { properties: {}, required: [] },
        relations: {
          parent: { title: "Parent", target: targetIdentifier, required: true, many: false },
        },
        mirrorProperties: {
          parent_tier: { title: "Parent tier", path: "parent.tier" },
        },
      },
    });
    expect(computedDepResp.status()).toBe(204);

    // 10. The dependent blueprint has no schema properties of its own, so the mirrored tier is
    // its first (and only) preview column on the list; its editor's read-only Computed section
    // names the same value with the Mirror badge.
    await page.goto(`/entities?blueprint=${encodeURIComponent(depIdentifier)}`);
    await expect(page.getByRole("columnheader", { name: "Parent tier" })).toBeVisible();
    const entityBRow = page.getByRole("row").filter({ hasText: entityBIdentifier });
    await expect(entityBRow.getByText("gold")).toBeVisible();

    await page.getByRole("button", { name: `Edit ${entityBIdentifier}` }).click();
    const depComputedGroup = page.getByRole("group", { name: "Computed" });
    await expect(depComputedGroup.getByText("Parent tier")).toBeVisible();
    await expect(depComputedGroup.getByText("Mirror")).toBeVisible();
    await expect(depComputedGroup.getByText("gold")).toBeVisible();

    // 11. The target entity's own editor shows both new computed values (the colorized
    // calculation and the aggregation counting the dependent entity created in step 4/7 above),
    // the JSON preview carries none of the three computed ids, and saving still succeeds — a
    // leaked computed key in the request would 400 instead.
    await page.goto(`/entities?blueprint=${encodeURIComponent(targetIdentifier)}`);
    await page.getByRole("button", { name: `Edit ${entityAIdentifier}` }).click();
    const targetComputedGroup = page.getByRole("group", { name: "Computed" });
    await expect(targetComputedGroup.getByText("Tier badge")).toBeVisible();
    await expect(targetComputedGroup.getByText("Calculation")).toBeVisible();
    await expect(targetComputedGroup.getByText("gold")).toBeVisible();
    await expect(targetComputedGroup.getByText("Dependents")).toBeVisible();
    await expect(targetComputedGroup.getByText("Aggregation")).toBeVisible();
    await expect(targetComputedGroup.getByText("1", { exact: true })).toBeVisible();

    const targetPreview = page.getByLabel("JSON preview");
    await expect(targetPreview).not.toContainText("tier_badge");
    await expect(targetPreview).not.toContainText("dependents");

    const [savedA] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().endsWith(`/api/v1/entities/${entityAId}`) && r.request().method() === "PUT",
      ),
      page.getByRole("button", { name: "Save" }).click(),
    ]);
    expect(savedA.ok(), "a leaked computed key in the request would 400 instead of ok").toBe(true);
    await expect(page).toHaveURL(/\/entities\?blueprint=.+$/);
  } finally {
    // Computed properties added a NEW delete cycle: the target blueprint's aggregation now
    // targets the dependent blueprint, and an aggregation target blocks deletion exactly like a
    // relation target does — so the dependent could no longer be deleted first as cleanup
    // expects. Restore the target blueprint's pre-computed (step-6) definition FIRST to break
    // that cycle before the ordinary entity/blueprint cleanup order runs.
    if (computedAdded && targetBlueprintId) {
      const restoredTarget = await page.request.put(`/api/v1/blueprints/${targetBlueprintId}`, {
        headers: authHeaders,
        data: {
          identifier: targetIdentifier,
          title: "E2E Entity Target",
          schema: {
            properties: { ...targetProperties, owner: { type: "string" } },
            required: ["replicas", "owner"],
          },
        },
      });
      expect(
        restoredTarget.status(),
        "cleanup: restore the target blueprint before deleting its aggregation target",
      ).toBe(204);
    }

    // Cleanup: entities first (the referring one before the referenced one), then blueprints
    // (the dependent before the target — the blueprints.spec.ts order).
    if (entityBId) {
      const deletedB = await page.request.delete(`/api/v1/entities/${entityBId}`, { headers: authHeaders });
      expect(deletedB.status(), "cleanup: delete the referring entity first").toBe(204);
    }
    if (entityAId) {
      const deletedA = await page.request.delete(`/api/v1/entities/${entityAId}`, { headers: authHeaders });
      expect(deletedA.status()).toBe(204);
    }
    if (teamEntityId) {
      const deletedTeam = await page.request.delete(`/api/v1/entities/${teamEntityId}`, { headers: authHeaders });
      expect(deletedTeam.status()).toBe(204);
    }
    if (depBlueprintId) {
      const deletedDep = await page.request.delete(`/api/v1/blueprints/${depBlueprintId}`, {
        headers: authHeaders,
      });
      expect(deletedDep.status(), "cleanup: delete the dependent blueprint first").toBe(204);
    }
    if (targetBlueprintId) {
      const deletedTarget = await page.request.delete(`/api/v1/blueprints/${targetBlueprintId}`, {
        headers: authHeaders,
      });
      expect(deletedTarget.status()).toBe(204);
    }
  }
});
