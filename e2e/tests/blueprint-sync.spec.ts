import { expect, login, readyDialog, rowOperation, test, uniqueText } from "./helpers";

// The blueprint source-reference/re-sync journey on one throwaway blueprint (2.10.0, the
// Port-registry twin of entity-sync.spec.ts's first journey): created source-less (the Last
// sync column reads "No source", the kebab's Sync item offered but disabled), the reference set
// after the fact in the editor's Source fieldset (the column flips to "Never synced"), and the
// sync modal opened against an unreachable loopback URL — the SSRF guard's uniform 400 renders
// as the fixed public-https message, exactly like the entity/catalog sync modals — the suite
// runs without external network, so the fetch->diff->overwrite happy path is deliberately
// server-/unit-tested instead (see the scenario file). Sync/fetch are ADMIN-only for blueprints
// (unlike entities), so the whole journey runs as the seed admin; the import page's own loopback
// fetch refusal is already covered by entity-sync.spec.ts and is NOT duplicated here.
test("a source set in the blueprint editor turns on the Last sync column and the sync modal, which refuses a loopback source", async ({
  page,
}) => {
  await login(page);
  const adminToken = await page.evaluate(() => localStorage.getItem("toadie.auth.token"));
  expect(adminToken !== null, "the admin login must provide setup/cleanup authorization").toBe(true);
  const authHeaders = { Authorization: `Bearer ${adminToken}` };

  const blueprintIdentifier = uniqueText("e2e-bpsync");

  let blueprintId: number | undefined;

  try {
    // Seed a minimal throwaway blueprint (one string property) via the API — this spec's
    // subject is source references, not blueprint curation.
    const blueprintResp = await page.request.post("/api/v1/blueprints", {
      headers: authHeaders,
      data: {
        identifier: blueprintIdentifier,
        title: "E2E Blueprint Sync",
        schema: { properties: { label: { type: "string", title: "Label" } }, required: [] },
      },
    });
    expect(blueprintResp.status()).toBe(201);
    blueprintId = (await blueprintResp.json()).id;

    // 1. The list shows "No source" and the kebab's Sync item is offered but greyed out —
    // offered-and-unavailable, never absent (the catalog/entity idiom).
    await page.goto("/blueprints");
    await expect(page.getByRole("heading", { name: "Blueprints" })).toBeVisible();
    const row = page.getByRole("row").filter({ hasText: blueprintIdentifier });
    await expect(row).toBeVisible();
    await expect(row.getByText("No source", { exact: true })).toBeVisible();

    const trigger = page.getByRole("button", { name: `Operations for ${blueprintIdentifier}` });
    await trigger.click();
    await expect(
      page.getByRole("menuitem", { name: `Sync ${blueprintIdentifier} from source` }),
    ).toBeDisabled();
    await page.keyboard.press("Escape");

    // 2. Set the reference in the editor's Source fieldset (statically valid — absolute https;
    // the host is only probed at fetch time) and save.
    await rowOperation(page, blueprintIdentifier, "Edit");
    await page.getByRole("textbox", { name: "Source URL" }).fill("https://127.0.0.1/blueprint.json");
    const [saved] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().endsWith(`/api/v1/blueprints/${blueprintId}`) && r.request().method() === "PUT",
      ),
      page.getByRole("button", { name: "Save" }).click(),
    ]);
    expect(saved.status()).toBe(204);

    // 3. Back on the list, the column now reads the never-synced state.
    await expect(page).toHaveURL(/\/blueprints$/);
    await expect(row.getByText("Never synced", { exact: true })).toBeVisible();

    // 4. The sync modal opens from the kebab; fetching the loopback URL is refused by the SSRF
    // guard, so the modal shows the fixed public-https error and keeps the overwrite disabled —
    // the flow stops safely at the guard.
    await rowOperation(page, blueprintIdentifier, `Sync ${blueprintIdentifier} from source`);
    const modal = await readyDialog(page, `Sync from source — ${blueprintIdentifier}`);
    await expect(modal.getByText(/The URL must be a public https address/)).toBeVisible();
    await expect(modal.getByRole("button", { name: "Overwrite stored copy" })).toBeDisabled();
    await modal.getByRole("button", { name: "Cancel" }).click();
    await expect(page.getByRole("dialog")).toBeHidden();
  } finally {
    if (blueprintId) {
      const deletedBlueprint = await page.request.delete(`/api/v1/blueprints/${blueprintId}`, {
        headers: authHeaders,
      });
      expect(deletedBlueprint.status()).toBe(204);
    }
  }
});
