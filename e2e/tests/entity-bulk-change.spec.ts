import type { APIRequestContext, Page } from "@playwright/test";
import { expect, login, readyDialog, test, uniqueText, waitForApi } from "./helpers";

// Bulk change of one field across a blueprint's entities (2.38.0, `/entities/bulk-change`).
// Each journey seeds its own throwaway world through the API as the seed admin (entities carry
// no admin gate): a parent blueprint with one parent entity, and a child blueprint with an enum
// "Stage" (alpha/beta) and a single `parent` relation mapped in `hierarchyRelations` to the FIRST
// value of the hierarchies dictionary (read, never written — the dictionary is single-writer
// state, see README.md), with three children — two at alpha, one already at beta. The page's
// tree then nests the children under the parent as a non-editable CONTEXT row whose group
// checkbox selects all three. Owns only its own `e2e-ebulk-*` blueprints and entities. If the
// dictionary were empty (it never is: `composition` is seeded) the relation is left unmapped and
// the children are ticked one by one instead of through the parent's group checkbox.

type Auth = { Authorization: string };

type World = {
  parentBp: string;
  childBp: string;
  parentTitle: string;
  parentId: string;
  /** The hierarchy the child blueprint maps, or null when the dictionary is empty. */
  hierarchy: string | null;
  children: { identifier: string; title: string; stage: "alpha" | "beta"; entityId?: number }[];
  parentEntityId?: number;
  childBpId?: number;
  parentBpId?: number;
};

async function created(response: { status(): number; json(): Promise<{ id: number }> }): Promise<number> {
  expect(response.status()).toBe(201);
  return (await response.json()).id;
}

async function seedWorld(request: APIRequestContext, headers: Auth, world: World): Promise<void> {
  world.parentBpId = await created(
    await request.post("/api/v1/blueprints", {
      headers,
      data: { identifier: world.parentBp, title: "E2E Bulk Parent Blueprint", schema: { properties: {}, required: [] } },
    }),
  );
  world.childBpId = await created(
    await request.post("/api/v1/blueprints", {
      headers,
      data: {
        identifier: world.childBp,
        title: "E2E Bulk Child Blueprint",
        schema: {
          properties: { stage: { type: "string", title: "Stage", enum: ["alpha", "beta"] } },
          required: [],
        },
        relations: { parent: { title: "Parent", target: world.parentBp, required: false, many: false } },
        ...(world.hierarchy ? { hierarchyRelations: { [world.hierarchy]: "parent" } } : {}),
      },
    }),
  );
  world.parentEntityId = await created(
    await request.post("/api/v1/entities", {
      headers,
      data: { blueprint: world.parentBp, identifier: world.parentId, title: world.parentTitle },
    }),
  );
  for (const child of world.children) {
    child.entityId = await created(
      await request.post("/api/v1/entities", {
        headers,
        data: {
          blueprint: world.childBp,
          identifier: child.identifier,
          title: child.title,
          properties: { stage: child.stage },
          relations: { parent: world.parentId },
        },
      }),
    );
  }
}

/** Children first, then the parent, then the child blueprint, then the parent blueprint. */
async function cleanupWorld(request: APIRequestContext, headers: Auth, world: World): Promise<void> {
  const targets: [string, number | undefined][] = [
    ...world.children.map((c): [string, number | undefined] => ["entities", c.entityId]),
    ["entities", world.parentEntityId],
    ["blueprints", world.childBpId],
    ["blueprints", world.parentBpId],
  ];
  for (const [collection, id] of targets) {
    if (id === undefined) continue;
    const response = await request.delete(`/api/v1/${collection}/${id}`, { headers });
    expect(response.status(), `cleanup of ${collection}/${id}`).toBe(204);
  }
}

function newWorld(marker: string, hierarchy: string | null): World {
  const run = uniqueText(marker);
  return {
    parentBp: `${run}-parent`,
    childBp: `${run}-child`,
    parentTitle: "E2E Bulk Parent One",
    parentId: `${run}-p1`,
    hierarchy,
    children: [
      { identifier: `${run}-c1`, title: "E2E Bulk Child One", stage: "alpha" },
      { identifier: `${run}-c2`, title: "E2E Bulk Child Two", stage: "alpha" },
      { identifier: `${run}-c3`, title: "E2E Bulk Child Three", stage: "beta" },
    ],
  };
}

/** The first value of the hierarchies dictionary (the page's default hierarchy), read only. */
async function firstHierarchy(request: APIRequestContext, headers: Auth): Promise<string | null> {
  const response = await request.get("/api/v1/dictionaries/hierarchies", { headers });
  expect(response.status()).toBe(200);
  const body = (await response.json()) as { items: { value: string }[] };
  return body.items[0]?.value ?? null;
}

async function adminAuth(page: Page): Promise<Auth> {
  await login(page);
  const token = await page.evaluate(() => localStorage.getItem("toadie.auth.token"));
  expect(token !== null, "the admin login must provide setup/cleanup authorization").toBe(true);
  return { Authorization: `Bearer ${token}` };
}

/** Ticks all three children: the parent's group checkbox, or each row when nothing nests. */
async function selectAllChildren(page: Page, world: World): Promise<void> {
  if (world.hierarchy) {
    await page
      .getByRole("checkbox", { name: `Select all 3 entities in the branch of ${world.parentTitle}`, exact: true })
      .check();
  } else {
    for (const child of world.children) {
      await page.getByRole("checkbox", { name: `Select ${child.title}`, exact: true }).check();
    }
  }
  await expect(page.getByText("Selected: 3", { exact: true })).toBeVisible();
}

/** Confirm → Stage → Set → beta → Preview, returning the open dialog once the preview shows. */
async function previewStageBeta(page: Page) {
  await page.getByRole("button", { name: "Confirm", exact: true }).click();
  const dialog = await readyDialog(page, "Bulk change");

  await dialog.getByRole("combobox", { name: "Field to change" }).click();
  await page.getByRole("option", { name: "Stage", exact: true }).click();
  // "Set" is the default operation; pin it so the journey states what it changes.
  await expect(dialog.getByRole("radio", { name: "Set", exact: true })).toBeChecked();
  await dialog.getByRole("combobox", { name: "Stage", exact: true }).click();
  await page.getByRole("option", { name: "beta", exact: true }).click();

  const [checked] = await Promise.all([
    waitForApi(page, { method: "POST", path: "/api/v1/entities/bulk-change/check" }),
    dialog.getByRole("button", { name: "Preview", exact: true }).click(),
  ]);
  expect(checked.status()).toBe(200);
  await expect(dialog.getByText("Will be updated: 2, unchanged: 1, cannot be changed: 0.")).toBeVisible();
  return dialog;
}

test("bulk-changing a field across a branch updates the differing entities and records the change in their history", async ({
  page,
}) => {
  const authHeaders = await adminAuth(page);
  const world = newWorld("e2e-ebulk-a", await firstHierarchy(page.request, authHeaders));
  const [c1, c2, c3] = world.children;

  try {
    await seedWorld(page.request, authHeaders, world);

    // 1. Enter from the Entities page: pick the child blueprint, then follow the Bulk change
    // link, which carries the pick.
    await page.goto("/entities");
    await expect(page.getByRole("heading", { name: "Entities", exact: true })).toBeVisible();
    const blueprintSelect = page.getByRole("combobox", { name: "Blueprint" });
    await blueprintSelect.click();
    await blueprintSelect.fill(world.childBp);
    await page.getByRole("option", { name: world.childBp, exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`blueprint=${world.childBp}`));
    // The URL is written before React re-renders: gate the click on the link's own href, as the
    // "New entity" link does in entities.spec.ts.
    const bulkLink = page.getByRole("link", { name: "Bulk change", exact: true });
    await expect(bulkLink).toHaveAttribute("href", new RegExp(`/entities/bulk-change\\?.*blueprint=${world.childBp}`));
    await bulkLink.click();

    // 2. The view opens with the child blueprint chosen and its three entities shown, the
    // children nested under the parent (a context row) when the hierarchy is mapped.
    await expect(page).toHaveURL(/\/entities\/bulk-change\?/);
    await expect(page.getByRole("heading", { name: "Bulk change", exact: true })).toBeVisible();
    await expect(page.getByRole("radio", { name: world.childBp, exact: true })).toBeChecked();
    await expect(page.getByText("Entities shown: 3", { exact: true })).toBeVisible();
    for (const child of world.children) {
      await expect(page.getByText(child.title, { exact: true })).toBeVisible();
    }
    if (world.hierarchy) await expect(page.getByText(world.parentTitle, { exact: true })).toBeVisible();

    // 3. Tick the parent's group checkbox: all three children are selected.
    await selectAllChildren(page, world);

    // 4. Confirm → change Stage with Set → beta; the preview predicts 2 updates and 1 unchanged.
    const dialog = await previewStageBeta(page);
    const rowOf = (identifier: string) => dialog.getByRole("row").filter({ hasText: identifier });
    await expect(rowOf(c1.identifier)).toContainText("Will update");
    await expect(rowOf(c1.identifier)).toContainText("alpha → beta");
    await expect(rowOf(c2.identifier)).toContainText("Will update");
    await expect(rowOf(c3.identifier)).toContainText("Unchanged");

    // 5. Apply: the real run reports 2 updated; the finished rows leave the selection.
    const [applied] = await Promise.all([
      waitForApi(page, { method: "POST", path: "/api/v1/entities/bulk-change" }),
      dialog.getByRole("button", { name: "Apply to 2 entities", exact: true }).click(),
    ]);
    expect(applied.status()).toBe(200);
    await expect(dialog.getByText("Updated: 2, unchanged: 1, not changed: 0.")).toBeVisible();
    await expect(rowOf(c1.identifier)).toContainText("Updated");
    await expect(rowOf(c2.identifier)).toContainText("Updated");
    await expect(rowOf(c3.identifier)).toContainText("Unchanged");
    await dialog.getByRole("button", { name: "Close", exact: true }).click();
    await expect(dialog).toHaveCount(0);
    await expect(page.getByText("Selected: 0", { exact: true })).toBeVisible();

    // 6. Both stored values moved (the one already at beta stayed).
    for (const child of world.children) {
      const stored = await page.request.get(`/api/v1/entities/${child.entityId}`, { headers: authHeaders });
      expect(stored.status()).toBe(200);
      expect((await stored.json()).properties.stage).toBe("beta");
    }

    // 7. An updated child's editor shows the bulk change in its History.
    await page.goto(`/entities/${c1.entityId}/edit`);
    const historyHeading = page.getByRole("heading", { name: "History", exact: true });
    await expect(historyHeading).toBeVisible();
    // The History section is the heading's innermost enclosing block (the entity-history idiom).
    const history = page.locator("div").filter({ has: historyHeading }).last();
    await expect(history.getByText("Entity updated by a bulk change: Stage.")).toBeVisible();
    await expect(history.getByText("Stage: alpha → beta")).toBeVisible();
    await expect(history.getByText("Entity created.")).toBeVisible();
  } finally {
    await cleanupWorld(page.request, authHeaders, world);
  }
});

test("an entity edited between the preview and the apply comes back stale while the others update", async ({
  page,
}) => {
  const authHeaders = await adminAuth(page);
  const world = newWorld("e2e-ebulk-b", await firstHierarchy(page.request, authHeaders));
  const [c1, c2, c3] = world.children;
  const editedTitle = "E2E Bulk Child Two Edited";

  try {
    await seedWorld(page.request, authHeaders, world);

    // 1. Open the view directly on the child blueprint (the entry from the Entities page is the
    // first journey's subject), select all three and preview Stage -> beta.
    await page.goto(`/entities/bulk-change?blueprint=${world.childBp}`);
    await expect(page.getByRole("heading", { name: "Bulk change", exact: true })).toBeVisible();
    await expect(page.getByText("Entities shown: 3", { exact: true })).toBeVisible();
    await selectAllChildren(page, world);
    const dialog = await previewStageBeta(page);

    // 2. Another actor edits one of the to-be-updated children through the API: a full
    // replace that changes its title (and so its write-version stamp), keeping Stage alpha.
    const edit = await page.request.put(`/api/v1/entities/${c2.entityId}`, {
      headers: authHeaders,
      data: {
        blueprint: world.childBp,
        identifier: c2.identifier,
        title: editedTitle,
        properties: { stage: "alpha" },
        relations: { parent: world.parentId },
      },
    });
    expect(edit.status()).toBe(204);

    // 3. Apply: the edited row is STALE, the other updates, the unchanged one stays unchanged.
    const [applied] = await Promise.all([
      waitForApi(page, { method: "POST", path: "/api/v1/entities/bulk-change" }),
      dialog.getByRole("button", { name: "Apply to 2 entities", exact: true }).click(),
    ]);
    expect(applied.status()).toBe(200);
    await expect(dialog.getByText("Updated: 1, unchanged: 1, not changed: 1.")).toBeVisible();
    const rowOf = (identifier: string) => dialog.getByRole("row").filter({ hasText: identifier });
    await expect(rowOf(c1.identifier)).toContainText("Updated");
    await expect(rowOf(c2.identifier)).toContainText("Stale");
    await expect(rowOf(c3.identifier)).toContainText("Unchanged");
    await expect(dialog.getByText("Entities that were not changed stay selected", { exact: false })).toBeVisible();

    // 4. The API edit survived the bulk change; the other child moved.
    const storedEdited = await page.request.get(`/api/v1/entities/${c2.entityId}`, { headers: authHeaders });
    expect(storedEdited.status()).toBe(200);
    const editedBody = await storedEdited.json();
    expect(editedBody.title).toBe(editedTitle);
    expect(editedBody.properties.stage).toBe("alpha");
    const storedUpdated = await page.request.get(`/api/v1/entities/${c1.entityId}`, { headers: authHeaders });
    expect((await storedUpdated.json()).properties.stage).toBe("beta");

    // 5. Only the stale row stays selected for a retry.
    await dialog.getByRole("button", { name: "Close", exact: true }).click();
    await expect(dialog).toHaveCount(0);
    await expect(page.getByText("Selected: 1", { exact: true })).toBeVisible();
  } finally {
    await cleanupWorld(page.request, authHeaders, world);
  }
});
