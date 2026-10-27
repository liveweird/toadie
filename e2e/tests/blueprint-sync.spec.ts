import { readFileSync } from "node:fs";
import { expect, login, readyDialog, rowOperation, test, uniqueText, waitForApi } from "./helpers";
import type { APIRequestContext } from "@playwright/test";

// The blueprint source-reference/re-sync journey on one throwaway blueprint (2.10.0, the
// Port-registry twin of entity-sync.spec.ts's first journey): created source-less (the Last
// sync column reads "No source", the kebab's Sync item offered but disabled), the reference set
// after the fact in the editor's Source fieldset (the column flips to "Never synced"), and the
// sync modal opened against an unreachable loopback URL — the SSRF guard's uniform 400 renders
// as the fixed public-https message, exactly like the entity/catalog sync modals — the guard
// admits no fixture server the suite could host, so the fetch->diff->overwrite happy path is the
// SECOND journey below, which reads a pinned public file instead. Sync/fetch are ADMIN-only for blueprints
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

// The success journey (the loopback refusal above stops at the guard by design): a throwaway
// blueprint is synced from a REAL public https file the unchanged SSRF guard already accepts.
// The remote copy is `e2e/fixtures/blueprint-sync-source.json`, fetched from raw.githubusercontent.com
// at an immutable COMMIT (never a branch), so later edits to the fixture or the sample data cannot
// change what this journey sees; the fetch happens server-side in the app container, so CI's
// hosted runner (and a developer's stack) only needs outbound https. A single-document source
// is taken as-is and may rename the target, so the throwaway blueprint carries the fixture's
// own, RESERVED identifier (no other spec uses it; a leftover from a crashed run is removed
// first). Pin rule: after ANY edit of the fixture, commit it (and push) FIRST, then set
// FIXTURE_COMMIT to that commit — a PR-branch commit stays fetchable after the merge; the
// up-front probe fails loudly while the pin and the file on disk disagree.
const FIXTURE_REPO_PATH = "e2e/fixtures/blueprint-sync-source.json";
const FIXTURE_COMMIT = "ea5ff202188538eac530c539a1765c84bc3eda24";
const FIXTURE_SOURCE_URL = `https://raw.githubusercontent.com/liveweird/toadie/${FIXTURE_COMMIT}/${FIXTURE_REPO_PATH}`;

type SourceFixture = {
  identifier: string;
  title: string;
  schema: { properties: Record<string, { title: string }> };
};

/**
 * Proves the APP (not the test runner) can fetch the pinned source and that it is the file in this
 * checkout, so a network problem or a stale pin reads as a precise message instead of a modal
 * timeout. Missing outbound network: skipped locally, FAILS in CI — the Mailpit policy
 * (`mfa.spec.ts`). An answering upstream with the wrong content (HTTP 404 = unpushed/mistyped pin,
 * a different file body) always fails.
 */
async function requirePinnedSource(
  request: APIRequestContext,
  headers: Record<string, string>,
  fixture: SourceFixture,
): Promise<void> {
  const resp = await request.post("/api/v1/blueprints/fetch", { headers, data: { url: FIXTURE_SOURCE_URL } });
  if (resp.status() === 200) {
    const { content } = (await resp.json()) as { content: string };
    expect(JSON.parse(content), `the pinned source ${FIXTURE_SOURCE_URL} must equal ${FIXTURE_REPO_PATH} in this checkout`).toEqual(fixture);
    return;
  }
  const { detail = "" } = (await resp.json().catch(() => ({}))) as { detail?: string };
  // The app container's DNS/connect failures surface as 400 (the unresolvable-host arm of the
  // guard's uniform public-https message) or 502 WITHOUT an upstream "(HTTP n)" status; an
  // answering upstream (404 = unpushed/mistyped pin, 5xx) or any other status is never "offline".
  const transportFailure = (resp.status() === 400 || resp.status() === 502) && !/\(HTTP \d+\)/.test(detail);
  const message = `The app could not fetch the pinned blueprint source (${resp.status()} ${detail}): ${FIXTURE_SOURCE_URL}`;
  if (transportFailure && !process.env.CI) {
    test.skip(true, `${message} — outbound https from the app container is unavailable`);
  }
  throw new Error(message);
}

test("a blueprint synced from a pinned public source shows the diff, overwrites on confirm and stamps Last sync", async ({
  page,
}) => {
  await login(page);
  const adminToken = await page.evaluate(() => localStorage.getItem("toadie.auth.token"));
  expect(adminToken !== null, "the admin login must provide setup/cleanup authorization").toBe(true);
  const authHeaders = { Authorization: `Bearer ${adminToken}` };

  const fixture = JSON.parse(readFileSync(new URL("../fixtures/blueprint-sync-source.json", import.meta.url), "utf8")) as SourceFixture;
  const identifier = fixture.identifier;
  const storedTitle = "E2E Blueprint Sync (stored)";
  expect(storedTitle).not.toBe(fixture.title);
  // The one property the stored copy lacks (it only has `label`): the "visible change" to assert.
  const addedProperty = Object.keys(fixture.schema.properties).find((key) => key !== "label");
  expect(addedProperty, "the fixture must carry a property beyond `label`").toBeDefined();
  await requirePinnedSource(page.request, authHeaders, fixture);

  let blueprintId: number | undefined;

  try {
    // The identifier is reserved for this journey, so a leftover can only be a crashed run's.
    const listed = await page.request.get("/api/v1/blueprints", { headers: authHeaders });
    expect(listed.status()).toBe(200);
    for (const leftover of ((await listed.json()) as { items: { id: number; identifier: string }[] }).items) {
      if (leftover.identifier !== identifier) continue;
      const removed = await page.request.delete(`/api/v1/blueprints/${leftover.id}`, { headers: authHeaders });
      expect(removed.status()).toBe(204);
    }

    // Seed through the API (this journey's subject is the sync, not curation): the stored copy
    // differs from the source in a known way — another title, and one property fewer.
    const created = await page.request.post("/api/v1/blueprints", {
      headers: authHeaders,
      data: {
        identifier,
        title: storedTitle,
        schema: { properties: { label: { type: "string", title: "Label" } }, required: [] },
        sourceUrl: FIXTURE_SOURCE_URL,
      },
    });
    expect(created.status()).toBe(201);
    blueprintId = ((await created.json()) as { id: number }).id;

    await page.goto("/blueprints");
    await expect(page.getByRole("heading", { name: "Blueprints" })).toBeVisible();
    const row = page.getByRole("row").filter({ hasText: identifier });
    await expect(row).toBeVisible();
    await expect(row.getByRole("cell", { name: storedTitle, exact: true })).toBeVisible();
    await expect(row.getByText("Never synced", { exact: true })).toBeVisible();

    // The kebab item is enabled now; the modal fetches through the real SSRF guard and shows what
    // would change: the title line is replaced and the new property appears, nothing else moves.
    await rowOperation(page, identifier, `Sync ${identifier} from source`);
    const modal = await readyDialog(page, `Sync from source — ${identifier}`);
    const diff = modal.getByRole("group", { name: "Changes between the stored copy and the source copy" });
    await expect(diff).toBeVisible();
    await expect(diff.locator("pre").filter({ hasText: `"title": ${JSON.stringify(storedTitle)}` })).toHaveText(/^- /);
    await expect(diff.locator("pre").filter({ hasText: `"title": ${JSON.stringify(fixture.title)}` })).toHaveText(/^\+ /);
    await expect(diff.locator("pre").filter({ hasText: `"${addedProperty}": {` })).toHaveText(/^\+ /);
    const confirm = modal.getByRole("button", { name: "Overwrite stored copy" });
    await expect(confirm).toBeEnabled();

    // Register the wait BEFORE the click; the 204 is asserted outside the predicate.
    const synced = waitForApi(page, { method: "POST", path: `/api/v1/blueprints/${blueprintId}/sync` });
    await confirm.click();
    expect((await synced).status()).toBe(204);
    await expect(page.getByRole("dialog")).toBeHidden();

    // The list reflects the overwrite: the source's title, two properties, and a stamped column.
    await expect(row.getByRole("cell", { name: fixture.title, exact: true })).toBeVisible();
    await expect(row.getByRole("cell", { name: storedTitle, exact: true })).toBeHidden();
    await expect(row.getByText("Never synced", { exact: true })).toBeHidden();

    // And the stored blueprint is the source document: same title and properties, reference kept.
    const stored = await page.request.get(`/api/v1/blueprints/${blueprintId}`, { headers: authHeaders });
    expect(stored.status()).toBe(200);
    const body = (await stored.json()) as SourceFixture & { sourceUrl: string; lastSyncedAt: number };
    expect(body.title).toBe(fixture.title);
    expect(Object.keys(body.schema.properties).sort()).toEqual(Object.keys(fixture.schema.properties).sort());
    expect(body.sourceUrl).toBe(FIXTURE_SOURCE_URL);
    expect(body.lastSyncedAt).toBeGreaterThan(0);

    // Re-opening the modal now finds the two sides identical.
    await rowOperation(page, identifier, `Sync ${identifier} from source`);
    const again = await readyDialog(page, `Sync from source — ${identifier}`);
    await expect(again.getByText("Toadie and the source are in sync")).toBeVisible();
    await expect(again.getByRole("button", { name: "Overwrite stored copy" })).toBeDisabled();
    await again.getByRole("button", { name: "Cancel" }).click();
    await expect(page.getByRole("dialog")).toBeHidden();
  } finally {
    if (blueprintId) {
      const deleted = await page.request.delete(`/api/v1/blueprints/${blueprintId}`, { headers: authHeaders });
      expect(deleted.status()).toBe(204);
    }
  }
});
