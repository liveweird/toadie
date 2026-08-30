import { expect, test, type Page } from "@playwright/test";

export { expect, test };

/** The seeded bootstrap admin (V3) — the compose demo leaves its password unrotated. */
export const ADMIN = "admin@toadie.local";
export const PASSWORD = "changeme";

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
  await expect(logoutButton(page)).toBeVisible({ timeout: 15_000 });
}

/** The header logout affordance — visible only inside the authenticated shell. */
export function logoutButton(page: Page) {
  return page.getByRole("button", { name: "Logout" });
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
  const dialog = page.getByRole("dialog");
  // Masked as "*" until revealed — click the eye toggle first.
  await dialog.getByRole("button", { name: "Show password" }).click();
  const password = (await dialog.locator("code").textContent()) ?? "";
  // Mantine renders both a header X and the footer button named Close.
  await dialog.getByRole("button", { name: "Close", exact: true }).last().click();
  await expect(page).toHaveURL(/\/users$/);
  return { id, name, email, password };
}

/** Collision-free text so specs never depend on absolute counts or clean state. */
export function uniqueText(prefix: string): string {
  return `${prefix}-${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
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
 * The per-run namespaces registered in the namespaces dictionary by global-setup (and
 * removed by global-teardown). Catalog writes accept only defined namespaces, and the
 * dictionary PUT is a whole-document replace — parallel workers must never write it
 * concurrently, so the setup process is the one writer and specs just read these values
 * (namespaces.spec.ts, the sole in-run writer, appends/removes only its own entries).
 */
export function runNamespace(key: "kinds" | "render" | "roundTrip" | "hierarchy"): string {
  const value = process.env[`E2E_NS_${key.toUpperCase()}`];
  if (!value) throw new Error(`global-setup did not register the "${key}" run namespace`);
  return value;
}

/**
 * Pick a spec.type in the catalog form's Type Select (free text is not accepted — the
 * field offers only the kind's admin-defined type dictionary, seeded by V15 with the
 * well-known Backstage values). Same shape as [pickNamespace].
 */
export async function pickType(page: Page, type: string): Promise<void> {
  const select = page.getByRole("combobox", { name: "Type" });
  await select.click();
  await select.fill(type);
  // The FILTER Type Select groups options by kind and the same type may appear under
  // several kinds ("service" for Component AND System) — any of them sets the same bare
  // type, so the first match is always correct (the form's Select has unique options).
  await page.getByRole("option", { name: type, exact: true }).first().click();
  await expect(select).toHaveValue(type);
}

/**
 * Pick a spec.lifecycle in the catalog form's Lifecycle Select (free text is not accepted —
 * the field offers only the global lifecycles dictionary, seeded by V16 with the well-known
 * values). Same shape as [pickType].
 */
export async function pickLifecycle(page: Page, lifecycle: string): Promise<void> {
  const select = page.getByRole("combobox", { name: "Lifecycle" });
  await select.click();
  await select.fill(lifecycle);
  await page.getByRole("option", { name: lifecycle, exact: true }).click();
  await expect(select).toHaveValue(lifecycle);
}

/**
 * Drive a catalog-files list row's action through its Operations dropdown (the per-row
 * Edit/Download/Delete buttons are bundled under one menu button since the list reshape).
 */
export async function rowOperation(
  page: Page,
  name: string,
  operation: "Edit" | "Download" | "Delete" | "Sync from repo",
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
 * Pick a namespace in a namespace Select — the catalog form's field AND the list/render
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
