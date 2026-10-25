import AxeBuilder from "@axe-core/playwright";
import { expect, test, type APIRequestContext, type Locator, type Page, type Response } from "@playwright/test";

export { expect, test };

/**
 * Wait for one API response by exact path and method. Deliberately does NOT bake a status
 * check into the predicate (`.claude/docs/testing.md`: "assert status OUTSIDE the predicate so
 * a failed response fails directly instead of timing out") — a `waitForResponse` predicate that
 * requires `r.ok()` keeps waiting past an error response with the same path/method, so a real
 * failure surfaces only as a confusing timeout instead of a clear status mismatch. Matches the
 * response's URL by exact `pathname` (never a substring/suffix check, which can also match an
 * unrelated longer path) and returns the raw `Response`; callers assert
 * `expect(response.status()).toBe(...)` (or `.ok()`) immediately after resolving.
 */
export function waitForApi(page: Page, { method, path }: { method: string; path: string }): Promise<Response> {
  return page.waitForResponse((r) => new URL(r.url()).pathname === path && r.request().method() === method);
}

/**
 * Visibility alone does not mean a Mantine dialog finished entering: opacity zero still
 * counts as visible to Playwright, and a deferred slide can start AFTER its stability check.
 * Wait for the computed endpoint before interacting; never sleep or retry a destructive click.
 */
export async function readyDialog(page: Page, title: string) {
  const dialog = page.getByRole("dialog", { name: title, exact: true });
  await expect(dialog).toHaveCSS("opacity", "1");
  return dialog;
}

/**
 * [readyDialog] plus the focus trap's own move. A Mantine modal does two things after it
 * mounts: it fades in, and its focus trap moves focus from the opener to the `data-autofocus`
 * field (or the first tabbable one) a tick later. Typing or clicking a field BEFORE that move
 * is undone by it: a combobox dropdown opened by the click closes on the blur, and text typed
 * into a tags input is lost together with the following Enter. Traces of the 2026-10-25
 * failures (annotations "Applies to kinds" option never appearing; tags/types edit steps whose
 * PUT never left): the last snapshot had focus on the modal's FIRST field, the dropdown closed
 * and the typed value missing. Use this, not bare `readyDialog`, before the first interaction
 * with a form modal's fields; the title is the modal's heading.
 */
export async function readyFormDialog(page: Page, title: string) {
  const dialog = await readyDialog(page, title);
  await expect(dialog.locator(":focus")).toHaveCount(1);
  return dialog;
}

/** The seeded bootstrap admin (V3) — the compose demo leaves its password unrotated. */
export const ADMIN = "admin@toadie.local";
const PASSWORD = "changeme";
export const MAILPIT = process.env.E2E_MAILPIT_URL ?? "http://localhost:8026";

/**
 * Navigate to a usable sign-in form. Any leftover session has to go first: while one exists
 * the app's RedirectIfAuthed bounces /login to the home page, so the form never renders and
 * a fill() waits out the whole test timeout.
 */
async function gotoSignInForm(page: Page): Promise<void> {
  if (!page.url().startsWith("http")) await page.goto("/login");
  await page.evaluate(() => localStorage.clear());
  await page.goto("/login");
  await expect(page.getByRole("button", { name: "Sign in" })).toBeVisible();
}

/** Sign in through the real login form. */
export async function login(page: Page, email = ADMIN, password = PASSWORD): Promise<void> {
  await gotoSignInForm(page);
  // Target by textbox role: getByLabel("Password") also matches the visibility-toggle button.
  await page.getByRole("textbox", { name: "Email" }).fill(email);
  await page.getByRole("textbox", { name: "Password" }).fill(password);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(accountMenu(page)).toBeVisible({ timeout: 15_000 });
}

/** The header account-menu trigger — visible only inside the authenticated shell (v1.19.0). */
export function accountMenu(page: Page) {
  return page.getByRole("button", { name: /Account menu$/ });
}

/** Sign out through the account menu (the former header Logout button). */
export async function signOut(page: Page): Promise<void> {
  await accountMenu(page).click();
  await page.getByRole("menuitem", { name: "Sign out" }).click();
}

/**
 * Switch the sidebar's product world via its "Product world" SegmentedControl radiogroup
 * (v2.3.0). Checking a radio navigates to that world's home — Backstage lands on the catalog
 * Hierarchy page (`/hierarchy`), Port on the Entity hierarchy page (`/entity-hierarchy`). A
 * fresh browser context (empty localStorage) lands on Port after `login()`, so a spec that
 * needs a Backstage-world leaf from the sidebar must switch first.
 *
 * Mantine's `SegmentedControl` renders its native radio inputs visually hidden — Playwright's
 * `.check()` refuses them as "element is not visible" — so this clicks the visible label text
 * inside the radiogroup instead, after waiting for Mantine's own `data-initialized` readiness
 * signal (the `readyDialog` posture: never a sleep, wait for the library's own marker).
 */
export async function switchWorld(page: Page, world: "Backstage" | "Port"): Promise<void> {
  const group = page.getByRole("radiogroup", { name: "Product world" });
  await expect(group).toHaveAttribute("data-initialized", "true");
  await group.getByText(world, { exact: true }).click();
  await expect(page).toHaveURL(world === "Backstage" ? /\/hierarchy$/ : /\/entity-hierarchy$/);
}

/**
 * Delete a user from the Users list through its row menu (v1.19.0: the row actions sit under
 * an "Operations for <name>" kebab) — the cleanup step every throwaway-user spec ends with.
 * Filters the list to the name first, then waits for the DELETE to land.
 */
export async function deleteUserRow(page: Page, name: string): Promise<void> {
  await page.goto("/users");
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(name);
  await rowOperation(page, name, `Delete ${name}`);
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "DELETE" && r.ok()),
    page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click(),
  ]);
}

/**
 * Create a throwaway user through the real UI (an admin must be signed in) and capture the
 * generated password from the one-time reveal modal. Never mutate seeded accounts — use this.
 * The id comes from the POST response; the password from the dialog after "Show password".
 */
export async function createUserViaUi(
  page: Page,
  namePrefix = "E2E User",
): Promise<{ id: number; name: string; email: string; password: string }> {
  const name = uniqueText(namePrefix);
  const email = `${name.toLowerCase().replace(/[^a-z0-9-]/g, "-")}@toadie.local`;
  await page.goto("/users/new");
  await page.getByRole("textbox", { name: "Name" }).fill(name);
  await page.getByRole("textbox", { name: "Email" }).fill(email);
  const [created] = await Promise.all([
    page.waitForResponse(
      (r) => r.url().endsWith("/api/v1/users") && r.request().method() === "POST" && r.ok(),
    ),
    page.getByRole("button", { name: "Create" }).click(),
  ]);
  const id: number = (await created.json()).id;
  const password = await revealCreatedPassword(page);
  const dialog = page.getByRole("dialog", { name: "User created", exact: true });
  // Mantine renders both a header X and the footer button named Close.
  await dialog.getByRole("button", { name: "Close", exact: true }).last().click();
  await expect(page).toHaveURL(/\/users$/);
  return { id, name, email, password };
}

/** Wait for the one-time create modal, reveal its password, and reject masked/empty reads. */
export async function revealCreatedPassword(page: Page): Promise<string> {
  const dialog = await readyDialog(page, "User created");
  await dialog.getByRole("button", { name: "Show password" }).click();
  await expect(dialog.getByRole("button", { name: "Hide password" })).toHaveAttribute(
    "aria-pressed",
    "true",
  );
  const password = (await dialog.locator("code").textContent()) ?? "";
  expect(password.length > 0, "the generated password must not be empty").toBe(true);
  expect(/^\*+$/.test(password), "the generated password must be revealed").toBe(false);
  return password;
}

/** Collision-free text so specs never depend on absolute counts or clean state. */
export function uniqueText(prefix: string): string {
  return `${prefix}-${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
}

type PropertyDefinition = { type?: string; format?: string; enum?: unknown[] };
type BlueprintSchema = { properties: Record<string, PropertyDefinition>; required: string[] };

/**
 * Create a throwaway `_team` entity satisfying whatever `_team`'s CURRENT schema requires.
 * The seeded base shape (`V31__system_blueprints.sql`) has no required properties, but an
 * environment carrying the sample ontology extension — or any future one — may add some
 * (Phase 4 ownership, v1.26.0 — see `.claude/docs/port-data-model.md`); every ownership-aware
 * spec must go through this helper instead of posting `properties: {}` directly, or it 400s
 * wherever `_team` has been extended. Reads the blueprint's live `schema` via
 * `GET /api/v1/blueprints` and fills each required property with a type-appropriate value
 * (enum -> its first value; string -> "e2e", format email -> an email, format url -> a URL;
 * number -> 0; boolean -> false; array -> []; object -> {}).
 */
export async function createTeamEntity(
  request: APIRequestContext,
  token: string,
  identifier: string,
  title: string,
): Promise<number> {
  const headers = { Authorization: `Bearer ${token}` };
  const blueprintsResp = await request.get("/api/v1/blueprints", { headers });
  expect(blueprintsResp.status(), "GET /api/v1/blueprints must succeed to seed a _team entity").toBe(200);
  const blueprints = ((await blueprintsResp.json()).items ?? []) as { identifier: string; schema: BlueprintSchema }[];
  const team = blueprints.find((b) => b.identifier === "_team");
  if (!team) throw new Error("the seeded _team blueprint was not found in the registry");

  const properties: Record<string, unknown> = {};
  for (const propId of team.schema.required) {
    const def = team.schema.properties[propId];
    if (def?.enum && def.enum.length > 0) properties[propId] = def.enum[0];
    else if (def?.type === "number") properties[propId] = 0;
    else if (def?.type === "boolean") properties[propId] = false;
    else if (def?.type === "array") properties[propId] = [];
    else if (def?.type === "object") properties[propId] = {};
    else if (def?.format === "email") properties[propId] = "e2e@example.com";
    else if (def?.format === "url") properties[propId] = "https://example.com";
    else properties[propId] = "e2e";
  }

  const resp = await request.post("/api/v1/entities", {
    headers,
    data: { blueprint: "_team", identifier, title, properties, relations: {} },
  });
  const body = await resp.json().catch(() => ({}));
  expect(resp.status(), `create _team entity "${identifier}" failed: ${body.detail ?? JSON.stringify(body)}`).toBe(
    201,
  );
  return body.id;
}

/** The entity import endpoint's batch cap (`EntityImportRequest.documents`, `maxItems: 200`). */
const ENTITY_IMPORT_BATCH = 200;

/**
 * Seed `n` entities of ONE blueprint through the batched entity import (several
 * `POST /api/v1/entities/import` calls of at most 200 documents each), as chains: entity `i`
 * points its `parent` relation at entity `i - 1` unless it starts a chain (`i % chainLength ===
 * 0`, a ROOT). The blueprint must carry a single `parent` relation to itself; flag it in
 * `hierarchyRelations` for the chains to nest on the Entity graph/hierarchy. Identifiers are
 * `<prefix>-<i>` with the title `<prefix> <i>`, so a spec narrows the canvas to its own rows with
 * the `q` search filter on the prefix. Chains may cross a batch boundary: the import resolves a
 * parent stored by an earlier batch like any other workspace entity.
 *
 * Returns the stored entity ids in creation order — delete them in REVERSE (a referrer before
 * its target) — and the root identifiers. Pass `created` (an array the caller owns) to have each
 * stored id pushed into it AS the batches land, so a `finally` can still clean up after a
 * mid-seed failure.
 */
export async function seedEntityChain(
  request: APIRequestContext,
  token: string,
  blueprint: string,
  prefix: string,
  n: number,
  { chainLength = 40, created = [] }: { chainLength?: number; created?: number[] } = {},
): Promise<{ ids: number[]; roots: string[] }> {
  const headers = { Authorization: `Bearer ${token}` };
  const documents = Array.from({ length: n }, (_, i) => ({
    blueprint,
    identifier: `${prefix}-${i}`,
    title: `${prefix} ${i}`,
    ...(i % chainLength === 0 ? {} : { relations: { parent: `${prefix}-${i - 1}` } }),
  }));
  const ids = created;
  for (let start = 0; start < documents.length; start += ENTITY_IMPORT_BATCH) {
    const resp = await request.post("/api/v1/entities/import", {
      headers,
      data: { documents: documents.slice(start, start + ENTITY_IMPORT_BATCH) },
    });
    expect(resp.status(), `entity import batch at ${start}`).toBe(200);
    const { results } = (await resp.json()) as { results: { status: string; id?: number; message?: string }[] };
    // Record every stored id BEFORE asserting, so a failing row cannot hide its siblings.
    for (const row of results) if (row.id !== undefined) ids.push(row.id);
    for (const [offset, row] of results.entries()) {
      expect(row.status, `import row ${start + offset}: ${row.message ?? ""}`).toBe("CREATED");
    }
  }
  return { ids, roots: documents.filter((_, i) => i % chainLength === 0).map((d) => d.identifier) };
}

/**
 * Ensure a list view's filter panel is expanded. Idempotent on purpose: the open/collapsed
 * state persists per view in localStorage (toadie.viewSettings.*), so within one test a
 * revisited page restores the panel open — a blind toggle click would close it again.
 */
export async function openFilters(page: Page): Promise<void> {
  const toggle = page.getByRole("button", { name: "Filters" });
  await expect(toggle).toBeVisible();
  if ((await toggle.getAttribute("aria-expanded")) !== "true") {
    await toggle.click();
  }
  await expect(toggle).toHaveAttribute("aria-expanded", "true");
}

/**
 * Ensure the Entity graph/hierarchy canvases' collapsible Query section is expanded (2.4.1) —
 * the `openFilters` twin, over the toggle labelled "Query" (anchored `/^Query( |$)/`: a plain
 * substring match on "Query" would also hit the saved-query picker's "Saved query actions"
 * button). Idempotent: the open state persists under the account-scoped shared
 * `entityQuery.account.<userId>.open` key, so a revisited page (or a canvas action that already opened it, e.g.
 * a context-menu query) is left alone rather than toggled shut.
 */
export async function openQuery(page: Page): Promise<void> {
  const toggle = page.getByRole("button", { name: /^Query( |$)/ });
  await expect(toggle).toBeVisible();
  if ((await toggle.getAttribute("aria-expanded")) !== "true") {
    await toggle.click();
  }
  await expect(toggle).toHaveAttribute("aria-expanded", "true");
}

/**
 * Ensure the collapsible Visibility section is expanded (2.4.2) — the `openFilters`/`openQuery`
 * twin, over the toggle labelled "Visibility". Reveals the blueprint/relation pills on the Port
 * canvases and the kind/relation pills on the Backstage Graph and Hierarchy pages, all of which
 * moved behind this one toggle from their previous always-visible row. Idempotent: the open
 * state persists per view under `<viewKey>.pillsOpen`, so a revisited page is left alone.
 */
export async function openPills(page: Page): Promise<void> {
  const toggle = page.getByRole("button", { name: /^Visibility/ });
  await expect(toggle).toBeVisible();
  if ((await toggle.getAttribute("aria-expanded")) !== "true") {
    await toggle.click();
  }
  await expect(toggle).toHaveAttribute("aria-expanded", "true");
}

/**
 * The per-run namespaces registered in the namespaces dictionary by global-setup (and
 * removed by global-teardown). Catalog writes accept only defined namespaces, and the
 * dictionary PUT is a whole-document replace — parallel workers must never write it
 * concurrently, so the setup process is the one writer and specs just read these values
 * (namespaces.spec.ts, the sole in-run writer, appends/removes only its own entries).
 */
export function runNamespace(key: "kinds" | "graph" | "graphAlt" | "roundTrip" | "hierarchy"): string {
  const value = process.env[`E2E_NS_${key.toUpperCase()}`];
  if (!value) throw new Error(`global-setup did not register the "${key}" run namespace`);
  return value;
}

/**
 * The one throwaway `hierarchies`-dictionary value global-setup registers (and
 * global-teardown removes) alongside the seeded `composition` entry — the `runNamespace()`
 * shape, for the SAME reason: the dictionary is a whole-document-replace resource with its
 * own single in-run writer (`hierarchies.spec.ts`), so a second value needed DURING the
 * parallel run (rather than before/after it) is minted outside that window instead of
 * risking a second concurrent writer. `entity-hierarchy.spec.ts`'s "a second hierarchy" step
 * is its only consumer.
 */
export function runHierarchy(): string {
  const value = process.env.E2E_HIER_ENTITY;
  if (!value) throw new Error('global-setup did not register the run hierarchy value ("E2E_HIER_ENTITY")');
  return value;
}

/**
 * Commit one option of a searchable Mantine Select through the keyboard. Both picks below
 * share it because the naive sequence (fill, wait for the option, ArrowDown, Enter) LOSES the
 * selection now and then, and the old closing `toHaveValue` could not notice: the input
 * still holds the TYPED text, which equals the wanted value. Trace of the 2026-10-25 hierarchy
 * failure: the Lifecycle input read "experimental", its listbox was still open with the
 * option marked `data-combobox-selected`, no filter request ever left the page, and the
 * assertion after it waited out its 10s on the unfiltered tree. A probe repeating the pick
 * under 8 parallel workers lost 87 of 800 (about 11%) that way, against 0 of 800 through
 * this helper. The same race starves the catalog-files "No catalog files" step: its Type
 * filter silently stays on `service`, so the row never leaves the list.
 *
 * Mantine tracks the keyboard-selected option by INDEX into the listbox DOM, and `fill`
 * re-renders that list asynchronously, so an ArrowDown/Enter that lands while the list still
 * holds the pre-filter options indexes into a list that is about to change — "the option is
 * visible" holds on both sides of that re-render and proves nothing. So: wait until the
 * listbox is SETTLED (every listed option matches the typed text), only then navigate, wait
 * for Mantine's own selection marker before Enter, and finally require what a real commit
 * does — the dropdown closes. No sleeps, no retried keystrokes.
 */
async function commitKeyboardOption(
  page: Page,
  select: Locator,
  text: string,
  option: Locator,
): Promise<void> {
  await expect(select).toHaveAttribute("aria-expanded", "true");
  await expect(select).toHaveAttribute("aria-controls", /.+/);
  const listbox = page.locator(`[id="${(await select.getAttribute("aria-controls")) ?? ""}"]`);
  const wanted = text.toLowerCase();
  await expect
    .poll(async () => {
      const labels = await listbox.getByRole("option").allTextContents();
      return labels.length > 0 && labels.every((label) => label.toLowerCase().includes(wanted));
    })
    .toBe(true);
  await expect(option).toBeVisible();
  await select.press("ArrowDown");
  await expect(option).toHaveAttribute("data-combobox-selected", "true");
  await select.press("Enter");
  await expect(select).toHaveAttribute("aria-expanded", "false");
  await expect(select).toHaveValue(text);
}

/**
 * Pick a spec.type in the catalog form's Type Select (free text is not accepted — the
 * field offers only the kind's admin-defined type dictionary, seeded by V15 and re-curated
 * by V22). Same shape as [pickNamespace].
 */
export async function pickType(page: Page, type: string): Promise<void> {
  const select = page.getByRole("combobox", { name: "Type" });
  await select.click();
  await select.fill(type);
  // The FILTER Type Select groups options by kind, so one type value can appear once per
  // kind that allows it — the V22 lists happen to be disjoint, but nothing enforces that
  // (the dictionaries are INDEPENDENT). Every such option sets the same bare type, so the
  // first match is always correct (the form's own Select has unique options anyway).
  //
  // A CI trace on this exact pick showed the option retried for 60s under "element is not
  // stable", then intercepted by the editor's sticky action bar and its fieldset in turn —
  // the filtered dropdown option keeps moving under those elements while the live
  // reference/registry check re-renders the form. A pointer click needs stable geometry;
  // keyboard selection does not, so commit it through [commitKeyboardOption].
  const option = page.getByRole("option", { name: type, exact: true }).first();
  await commitKeyboardOption(page, select, type, option);
}

/**
 * Pick a spec.lifecycle in the catalog form's Lifecycle Select (free text is not accepted —
 * the field offers only the global lifecycles dictionary, seeded by V16 and extended by V22
 * with `sunsetting`). Same shape as [pickType].
 */
export async function pickLifecycle(page: Page, lifecycle: string): Promise<void> {
  const select = page.getByRole("combobox", { name: "Lifecycle" });
  await select.click();
  await select.fill(lifecycle);
  // Same interception evidence as pickType above (sticky action bar / fieldset subtree
  // stealing the pointer event while the live check re-renders) — keyboard selection needs
  // no pointer geometry, so it is deterministic where a click is not.
  const option = page.getByRole("option", { name: lifecycle, exact: true });
  await commitKeyboardOption(page, select, lifecycle, option);
}

/**
 * Drive a list row's action through its "Operations for <name>" kebab menu (the catalog
 * Files/Hierarchy rows and, since v1.19.0, the Users rows — whose items carry interpolated
 * names, hence the open `string` union member). Sync is always present but DISABLED on a
 * source-less row, so target it only where a source is set.
 */
export async function rowOperation(
  page: Page,
  name: string,
  operation:
    | "Edit"
    | "Export as YAML"
    | "Overwrite with YAML"
    | "Delete"
    | "Sync from source"
    | "Pin"
    | "Unpin"
    | (string & {}),
): Promise<void> {
  const trigger = page.getByRole("button", { name: `Operations for ${name}` });
  // Ensure THIS row's menu actually opened: a previous row's still-fading dropdown treats
  // the first click as its outside-click and swallows it, leaving the WRONG menu mounted —
  // an unscoped menuitem click would then drive the other row's operation.
  await expect(async () => {
    if ((await trigger.getAttribute("aria-expanded")) !== "true") await trigger.click();
    expect(await trigger.getAttribute("aria-expanded")).toBe("true");
  }).toPass();
  const dropdownId = await trigger.getAttribute("aria-controls");
  await page.locator(`[id="${dropdownId}"]`).getByRole("menuitem", { name: operation }).click();
}

/**
 * Pick a namespace in a namespace Select — the catalog form's field AND the list/graph
 * pages' filter combo (both offer only the dictionary's entries; free text is not
 * accepted). Mantine Select inputs carry the combobox role; searchable filtering narrows
 * the dropdown before the option click.
 */
export async function pickNamespace(page: Page, ns: string): Promise<void> {
  const select = page.getByRole("combobox", { name: "Namespace" });
  await select.click();
  await select.fill(ns);
  await page.getByRole("option", { name: ns }).click();
  await expect(select).toHaveValue(ns);
}

/**
 * The accessibility spec's axe scan (WCAG 2.0/2.1 A+AA, its documented `color-contrast` waiver),
 * scoped to `<main>` and asserted in place — for journeys that REACH a state the page-level scan
 * cannot count on seeing (a collapsed fold pill, the top-level badge on a large graph). The
 * page-level scan depends on whatever the shared database holds; this one runs against the state
 * the calling journey just built.
 */
export async function expectNoAccessibilityViolations(page: Page): Promise<void> {
  const scan = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
    .disableRules(["color-contrast"])
    .include("main")
    .analyze();
  expect(scan.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(" ")).join(", ")}`)).toEqual([]);
}
