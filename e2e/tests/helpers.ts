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
