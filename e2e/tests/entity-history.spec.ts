import { expect, login, test, uniqueText } from "./helpers";

// The per-entity change history on the editor (2.37.0, the Port twin of history.spec.ts), on a
// throwaway blueprint and entity so parallel files and re-runs never collide. The history is
// per entity, so every assertion is scoped to this run's own row by construction. The
// blueprint carries an enum property (a valued before/after line) and a markdown property
// (free text, recorded by NAME only — its content must never reach the trail).
test("an entity's history records its creation and each later edit, field by field", async ({
  page,
}) => {
  await login(page);
  const adminToken = await page.evaluate(() => localStorage.getItem("toadie.auth.token"));
  expect(adminToken !== null, "the admin login must provide setup/cleanup authorization").toBe(true);
  const authHeaders = { Authorization: `Bearer ${adminToken}` };

  const blueprintIdentifier = uniqueText("e2e-ehist");
  const entityIdentifier = `${blueprintIdentifier}-1`;
  const originalTitle = "History Original";
  const editedTitle = "History Edited";
  const originalNotes = "the original notes text";
  const editedNotes = "the edited notes text";

  let blueprintId: number | undefined;
  let entityId: number | undefined;

  try {
    // Seed the blueprint (an enum "Stage" and a markdown "Notes") and one entity via the API —
    // this spec's subject is the history, not blueprint/entity curation.
    const blueprintResp = await page.request.post("/api/v1/blueprints", {
      headers: authHeaders,
      data: {
        identifier: blueprintIdentifier,
        title: "E2E Entity History",
        schema: {
          properties: {
            stage: { type: "string", title: "Stage", enum: ["alpha", "beta"] },
            notes: { type: "string", title: "Notes", format: "markdown" },
          },
          required: [],
        },
      },
    });
    expect(blueprintResp.status()).toBe(201);
    blueprintId = (await blueprintResp.json()).id;

    const entityResp = await page.request.post("/api/v1/entities", {
      headers: authHeaders,
      data: {
        blueprint: blueprintIdentifier,
        identifier: entityIdentifier,
        title: originalTitle,
        properties: { stage: "alpha", notes: originalNotes },
        relations: {},
      },
    });
    expect(entityResp.status()).toBe(201);
    entityId = (await entityResp.json()).id;

    // 1. A fresh entity's history holds exactly the creation.
    await page.goto(`/entities/${entityId}/edit`);
    const historyHeading = page.getByRole("heading", { name: "History", exact: true });
    await expect(historyHeading).toBeVisible();
    // The History section is the heading's innermost enclosing block (document order puts the
    // innermost match last), so the assertions below cannot be satisfied by the form or any
    // preview above it.
    const history = page.locator("div").filter({ has: historyHeading }).last();
    await expect(history.getByText("Entity created.")).toBeVisible();

    // 2. Edit three fields of different shapes: the envelope title, an enum, and free text.
    await page.getByRole("textbox", { name: "Title", exact: true }).fill(editedTitle);
    const properties = page.getByRole("group", { name: "Properties" });
    const stage = properties.getByRole("combobox", { name: "Stage" });
    await stage.click();
    await page.getByRole("option", { name: "beta", exact: true }).click();
    await properties.getByRole("textbox", { name: "Notes" }).fill(editedNotes);
    const [saved] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().endsWith(`/api/v1/entities/${entityId}`) && r.request().method() === "PUT",
      ),
      page.getByRole("button", { name: "Save" }).click(),
    ]);
    expect(saved.status()).toBe(204);

    // 3. Reopen: the sentence names all three changed fields by their blueprint titles; the
    // valued ones get a from → to line; the markdown gets none and its text never appears.
    await page.goto(`/entities/${entityId}/edit`);
    await expect(history.getByText("Entity updated: Title, Stage, Notes.")).toBeVisible();
    await expect(history.getByText(`Title: ${originalTitle} → ${editedTitle}`)).toBeVisible();
    await expect(history.getByText("Stage: alpha → beta")).toBeVisible();
    await expect(history.getByText(/^Notes:/)).toHaveCount(0);
    await expect(history.getByText(originalNotes)).toHaveCount(0);
    await expect(history.getByText(editedNotes)).toHaveCount(0);
    // Newest first: the edit sits above the creation, which is still there.
    const updatedEntry = history.getByText("Entity updated: Title, Stage, Notes.");
    const createdEntry = history.getByText("Entity created.");
    await expect(createdEntry).toBeVisible();
    const updatedBox = await updatedEntry.boundingBox();
    const createdBox = await createdEntry.boundingBox();
    expect(updatedBox !== null && createdBox !== null && updatedBox.y < createdBox.y).toBe(true);
  } finally {
    if (entityId) {
      const deletedEntity = await page.request.delete(`/api/v1/entities/${entityId}`, { headers: authHeaders });
      expect(deletedEntity.status()).toBe(204);
    }
    if (blueprintId) {
      const deletedBlueprint = await page.request.delete(`/api/v1/blueprints/${blueprintId}`, {
        headers: authHeaders,
      });
      expect(deletedBlueprint.status()).toBe(204);
    }
  }
});
