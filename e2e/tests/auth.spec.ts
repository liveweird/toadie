import { accountMenu, ADMIN, expect, login, signOut, test } from "./helpers";

test("admin can log in and log out", async ({ page }) => {
  await login(page);
  await expect(page.getByRole("heading", { name: "Hierarchy" })).toBeVisible();

  await signOut(page);
  await expect(page.getByRole("button", { name: "Sign in" })).toBeVisible();
  await expect(page.getByText("You've been signed out.")).toBeVisible();
});

test("invalid credentials are rejected", async ({ page }) => {
  await page.goto("/login");
  await page.getByRole("textbox", { name: "Email" }).fill(ADMIN);
  await page.getByRole("textbox", { name: "Password" }).fill("definitely-wrong");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByText("Invalid email or password")).toBeVisible();
});

test("a deep link is guarded and lands back after signing in", async ({ page }) => {
  await page.goto("/some/deep/path");
  // Anonymous → bounced to the sign-in form.
  await expect(page.getByRole("button", { name: "Sign in" })).toBeVisible();

  await page.getByRole("textbox", { name: "Email" }).fill(ADMIN);
  await page.getByRole("textbox", { name: "Password" }).fill("changeme");
  await page.getByRole("button", { name: "Sign in" }).click();

  // Back at the requested path — inside the shell it renders the not-found page (no blank).
  await expect(page.getByRole("heading", { name: "Page not found" })).toBeVisible();
  await expect(accountMenu(page)).toBeVisible();
});
