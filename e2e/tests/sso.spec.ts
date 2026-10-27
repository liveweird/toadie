import {
  accountMenu,
  createUserViaUi,
  deleteUserRow,
  expect,
  login,
  openFilters,
  signOut,
  test,
  uniqueText,
} from "./helpers";
import type { APIRequestContext, Page } from "@playwright/test";

// Single sign-on through the stub identity provider (e2e/stub-idp, started by the compose overlay
// e2e/compose.sso.yaml). The stack must have SSO on; GET /api/v1/login/options says whether it
// does. Locally the journeys skip themselves on a stack without it; under CI they FAIL instead
// (the Mailpit pattern in mfa.spec.ts), so a workflow that loses the overlay cannot go green.
// The login-page strings below are the EN `auth.ssoSignIn` / `auth.ssoError.*` values.
const SIGN_IN_WITH_OKTA = "Sign in with Okta";
const CANCELLED_ALERT = "Sign-in was cancelled. Try again, or use your password.";
const NOT_ALLOWED_ALERT =
  "This account cannot sign in through single sign-on. Use your password, or contact your administrator.";

async function requireSso(request: APIRequestContext): Promise<void> {
  const response = await request.get("/api/v1/login/options");
  const sso = response.ok() ? ((await response.json()) as { sso: unknown }).sso : null;
  if (process.env.CI) expect(sso, "CI must run the stack with the SSO overlay (e2e/compose.sso.yaml)").not.toBeNull();
  test.skip(!sso, "SSO is not enabled on this stack (e2e/compose.sso.yaml) — the sign-in is unobservable");
}

/** A collision-free identity; the email carries the e2e marker like every throwaway account. */
function ssoIdentity(prefix: string): { name: string; email: string } {
  const name = uniqueText(prefix);
  return { name, email: `${name.toLowerCase().replace(/[^a-z0-9-]/g, "-")}@toadie.local` };
}

/** Press the SSO button on the sign-in page and wait for the stub's own sign-in form. */
async function openStubSignIn(page: Page): Promise<void> {
  await page.getByRole("button", { name: SIGN_IN_WITH_OKTA }).click();
  await expect(page.getByRole("heading", { name: "Stub identity provider" })).toBeVisible();
}

/** Complete the stub's form as the given identity; the browser returns through the app's callback. */
async function continueAsStubUser(page: Page, { name, email }: { name: string; email: string }): Promise<void> {
  await page.getByRole("textbox", { name: "Email" }).fill(email);
  await page.getByRole("textbox", { name: "Name" }).fill(name);
  await page.getByRole("button", { name: "Continue" }).click();
}

async function expectChangePasswordItem(page: Page, shown: boolean): Promise<void> {
  await accountMenu(page).click();
  // The menu's own Sign out item proves the dropdown is open before the absence is asserted.
  await expect(page.getByRole("menuitem", { name: "Sign out" })).toBeVisible();
  await expect(page.getByRole("menuitem", { name: "Change password" })).toHaveCount(shown ? 1 : 0);
  await page.keyboard.press("Escape");
}

/** The Users list narrowed to one throwaway account's row. */
async function usersRow(page: Page, name: string) {
  await page.goto("/users");
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(name);
  const row = page.getByRole("row").filter({ hasText: name });
  await expect(row).toBeVisible();
  return row;
}

test("a new email signs in through SSO onto its deep link, without a password", async ({ page, request }) => {
  await requireSso(request);
  const sso = ssoIdentity("E2E-Sso");

  // A protected deep link bounces to the sign-in page, which remembers where the user was going.
  await page.goto("/files");
  await expect(page).toHaveURL(/\/login$/);
  await openStubSignIn(page);
  await continueAsStubUser(page, sso);

  await expect(accountMenu(page)).toBeVisible({ timeout: 15_000 });
  await expect(page).toHaveURL(/\/files$/);
  // The account was created without a password, so there is nothing to change.
  await expectChangePasswordItem(page, false);
  await signOut(page);

  // The administrator sees the account marked as an SSO account.
  await login(page);
  const row = await usersRow(page, sso.name);
  await expect(row.getByText("SSO", { exact: true })).toBeVisible();
  await deleteUserRow(page, sso.name);
});

test("an SSO sign-in links to the password account with the same email, which keeps its password", async ({
  page,
  request,
}) => {
  await requireSso(request);
  await login(page);
  const user = await createUserViaUi(page, "E2E-SsoLink");
  await signOut(page);

  await page.goto("/login");
  await openStubSignIn(page);
  await continueAsStubUser(page, { name: "Name From The Provider", email: user.email });
  await expect(accountMenu(page)).toBeVisible({ timeout: 15_000 });
  // A linked account still has its password, so the menu offers to change it.
  await expectChangePasswordItem(page, true);
  await signOut(page);

  // The one-time password the admin handed out still works.
  await login(page, user.email, user.password);
  await signOut(page);

  // The administrator sees the linked account marked, under its original name.
  await login(page);
  const row = await usersRow(page, user.name);
  await expect(row.getByText("SSO", { exact: true })).toBeVisible();
  await deleteUserRow(page, user.name);
});

test("cancelling at the identity provider returns to the sign-in page with a message", async ({ page, request }) => {
  await requireSso(request);
  await page.goto("/login");
  await openStubSignIn(page);
  await page.getByRole("button", { name: "Cancel" }).click();

  await expect(page.getByText(CANCELLED_ALERT)).toBeVisible();
  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByRole("button", { name: "Sign in", exact: true })).toBeVisible();
  await expect(accountMenu(page)).toHaveCount(0);
});

test("an administrator account is refused SSO and told to use its password", async ({ page, request }) => {
  await requireSso(request);
  await login(page);
  const admin = await createUserViaUi(page, "E2E-SsoAdmin");
  await page.goto(`/users/${admin.id}/edit`);
  await page.getByLabel("Administrator").check();
  const saved = page.waitForResponse(
    (r) => r.url().endsWith(`/api/v1/users/${admin.id}`) && r.request().method() === "PUT",
  );
  await page.getByRole("button", { name: "Save" }).click();
  expect((await saved).ok()).toBe(true);
  await signOut(page);

  await page.goto("/login");
  await openStubSignIn(page);
  await continueAsStubUser(page, { name: admin.name, email: admin.email });

  await expect(page.getByText(NOT_ALLOWED_ALERT)).toBeVisible();
  await expect(page).toHaveURL(/\/login$/);
  await expect(accountMenu(page)).toHaveCount(0);

  // Its password still signs it in; then the admin spec account is removed.
  await login(page, admin.email, admin.password);
  await signOut(page);
  await login(page);
  await deleteUserRow(page, admin.name);
});
