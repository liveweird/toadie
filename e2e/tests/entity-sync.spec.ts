import { expect, login, readyDialog, rowOperation, test, uniqueText } from "./helpers";

// The entity source-reference/re-sync journey on one throwaway blueprint+entity (2.9.0, the
// Port twin of source-sync.spec.ts): created source-less (the Last sync column reads "No
// source", the kebab's Sync item offered but disabled), the reference set after the fact in
// the editor's Source fieldset (the column flips to "Never synced"), and the sync modal opened
// against an unreachable loopback URL — the SSRF guard's uniform 400 renders as the fixed
// public-https message, exactly like the catalog's own sync modal — the suite runs without
// external network, so the fetch->diff->overwrite happy path is deliberately server-/unit-
// tested instead (see the scenario file). Entities carry no admin gate, so the whole journey
// runs as the seed admin.
test("a source set in the entity editor turns on the Last sync column and the sync modal, which refuses a loopback source", async ({
  page,
}) => {
  await login(page);
  const adminToken = await page.evaluate(() => localStorage.getItem("toadie.auth.token"));
  expect(adminToken !== null, "the admin login must provide setup/cleanup authorization").toBe(true);
  const authHeaders = { Authorization: `Bearer ${adminToken}` };

  const blueprintIdentifier = uniqueText("e2e-esync");
  const entityIdentifier = `${blueprintIdentifier}-1`;

  let blueprintId: number | undefined;
  let entityId: number | undefined;

  try {
    // Seed a minimal throwaway blueprint (one string property) and one entity via the API —
    // this spec's subject is source references, not blueprint/entity curation.
    const blueprintResp = await page.request.post("/api/v1/blueprints", {
      headers: authHeaders,
      data: {
        identifier: blueprintIdentifier,
        title: "E2E Entity Sync",
        schema: { properties: { label: { type: "string", title: "Label" } }, required: [] },
      },
    });
    expect(blueprintResp.status()).toBe(201);
    blueprintId = (await blueprintResp.json()).id;

    const entityResp = await page.request.post("/api/v1/entities", {
      headers: authHeaders,
      data: {
        blueprint: blueprintIdentifier,
        identifier: entityIdentifier,
        title: "E2E Entity Sync One",
        properties: { label: "e2e" },
        relations: {},
      },
    });
    expect(entityResp.status()).toBe(201);
    entityId = (await entityResp.json()).id;

    // 1. The list shows "No source" and the kebab's Sync item is offered but greyed out —
    // offered-and-unavailable, never absent (the catalog's own idiom).
    await page.goto(`/entities?blueprint=${encodeURIComponent(blueprintIdentifier)}`);
    await expect(page.getByRole("heading", { name: "Entities" })).toBeVisible();
    const row = page.getByRole("row").filter({ hasText: entityIdentifier });
    await expect(row).toBeVisible();
    await expect(row.getByText("No source", { exact: true })).toBeVisible();

    const trigger = page.getByRole("button", { name: `Operations for ${entityIdentifier}` });
    await trigger.click();
    await expect(page.getByRole("menuitem", { name: "Sync from source" })).toBeDisabled();
    await page.keyboard.press("Escape");

    // 2. Set the reference in the editor's Source fieldset (statically valid — absolute
    // https; the host is only probed at fetch time) and save.
    await rowOperation(page, entityIdentifier, "Edit");
    await page.getByRole("textbox", { name: "Source URL" }).fill("https://127.0.0.1/entity.json");
    const [saved] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().endsWith(`/api/v1/entities/${entityId}`) && r.request().method() === "PUT",
      ),
      page.getByRole("button", { name: "Save" }).click(),
    ]);
    expect(saved.status()).toBe(204);

    // 3. Back on the list, the column now reads the never-synced state.
    await expect(page).toHaveURL(/\/entities\?blueprint=.+$/);
    await expect(row.getByText("Never synced", { exact: true })).toBeVisible();

    // 4. The sync modal opens from the kebab; fetching the loopback URL is refused by the
    // SSRF guard, so the modal shows the fixed public-https error and keeps the overwrite
    // disabled — the flow stops safely at the guard.
    await rowOperation(page, entityIdentifier, "Sync from source");
    const modal = await readyDialog(page, `Sync from source — ${entityIdentifier}`);
    await expect(modal.getByText(/The URL must be a public https address/)).toBeVisible();
    await expect(modal.getByRole("button", { name: "Overwrite stored copy" })).toBeDisabled();
    await modal.getByRole("button", { name: "Cancel" }).click();
    await expect(page.getByRole("dialog")).toBeHidden();
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

// The ontology import page's own fetch-from-URL wiring, exercised WITHOUT external network —
// the url-import.spec.ts twin one level up. Owns nothing: the fetch is refused before any
// content exists.
test("fetching a private URL on the ontology import page is refused with the public-https message", async ({
  page,
}) => {
  await login(page);
  await page.goto("/ontology/import");

  await page
    .getByRole("textbox", { name: "Fetch from URL" })
    .fill("https://127.0.0.1/entities.json");
  await Promise.all([
    page.waitForResponse((r) => r.url().endsWith("/api/v1/entities/fetch") && r.status() === 400),
    page.getByRole("button", { name: "Fetch" }).click(),
  ]);

  await expect(
    page.getByText(
      "The URL must be a public https address (GitHub/GitLab links are converted automatically).",
    ),
  ).toBeVisible();
});
