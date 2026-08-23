import { createUserViaUi, expect, login, logoutButton, openFilters, test, uniqueText } from "./helpers";

// The whole account lifecycle on a throwaway user (its email carries the e2e marker):
// create via the one-time reveal → the new user's limited view → self password change →
// promotion → deletion → the dead login.
test("admin creates a user who signs in, changes their password, and is finally deleted", async ({
  page,
}) => {
  await login(page);

  // The admin's own row shows the You badge and offers Edit but no Delete/Reset.
  await page.goto("/users");
  await openFilters(page);
  await page.getByLabel("Email", { exact: true }).fill("admin@toadie.local");
  // Row-scoped asserts (the filter is debounced, and residue rows may share the page).
  const adminRow = page.getByRole("row").filter({ hasText: "admin@toadie.local" });
  await expect(adminRow.getByText("You")).toBeVisible();
  await expect(adminRow.getByRole("link", { name: /^Edit / })).toBeVisible();
  await expect(adminRow.getByRole("button", { name: /^Delete / })).toHaveCount(0);
  await expect(adminRow.getByRole("button", { name: /^Reset password/ })).toHaveCount(0);

  const throwaway = await createUserViaUi(page, "E2E Person");
  expect(throwaway.password).toMatch(/^[A-Za-z0-9_-]{16}$/);

  // The new user signs in: no Users nav, /users bounces home, Change password works.
  await login(page, throwaway.email, throwaway.password);
  await expect(page.getByRole("link", { name: "Users" })).toHaveCount(0);
  await page.goto("/users");
  await expect(page.getByRole("heading", { name: "Home" })).toBeVisible();
  const newPassword = `${uniqueText("self-pw")}`;
  await page.goto("/change-password");
  await page.getByLabel(/^Current password/).fill(throwaway.password);
  await page.getByLabel(/^New password/).fill(newPassword);
  await page.getByLabel(/^Confirm new password/).fill(newPassword);
  await Promise.all([
    page.waitForResponse((r) => r.url().includes("/password") && r.request().method() === "PUT" && r.ok()),
    page.getByRole("button", { name: "Save" }).click(),
  ]);
  await logoutButton(page).click();

  // Back as admin: promote, then delete; both against the name-filtered list.
  await login(page);
  await page.goto(`/users/${throwaway.id}/edit`);
  await page.getByLabel("Administrator").check();
  await Promise.all([
    page.waitForResponse(
      (r) => r.url().endsWith(`/api/v1/users/${throwaway.id}`) && r.request().method() === "PUT" && r.ok(),
    ),
    page.getByRole("button", { name: "Save" }).click(),
  ]);
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(throwaway.name);
  // Row-scoped: the seed admin's row, the role-filter options, etc. also say "Admin".
  await expect(
    page.getByRole("row").filter({ hasText: throwaway.name }).getByText("Admin", { exact: true }),
  ).toBeVisible();

  await page.getByRole("button", { name: `Delete ${throwaway.name}` }).click();
  await Promise.all([
    page.waitForResponse(
      (r) => r.url().endsWith(`/api/v1/users/${throwaway.id}`) && r.request().method() === "DELETE" && r.ok(),
    ),
    page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click(),
  ]);
  // Verify against a fresh load — the in-place list can lose a refetch race.
  await page.goto("/users");
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(throwaway.name);
  await expect(page.getByText("No users")).toBeVisible();

  // The deleted account can no longer sign in (their CHANGED password included).
  await logoutButton(page).click();
  // Fresh load: the logout notification can remount the login form and drop typed values.
  await page.goto("/login");
  await expect(page.getByRole("button", { name: "Sign in" })).toBeVisible();
  await page.getByRole("textbox", { name: "Email" }).fill(throwaway.email);
  await page.getByRole("textbox", { name: "Password" }).fill(newPassword);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByText(/invalid email or password/i)).toBeVisible();
});
