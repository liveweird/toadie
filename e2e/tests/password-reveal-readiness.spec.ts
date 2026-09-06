import {
  accountMenu,
  expect,
  login,
  readyDialog,
  revealCreatedPassword,
  test,
  uniqueText,
} from "./helpers";

test("generated-password reads reject a click that leaves the password masked", async ({ page }) => {
  await login(page);
  const adminToken = await page.evaluate(() => localStorage.getItem("toadie.auth.token"));
  expect(adminToken !== null, "the admin login must provide cleanup authorization").toBe(true);

  const name = uniqueText("E2E Reveal Reader");
  const email = `${name.toLowerCase().replace(/[^a-z0-9-]/g, "-")}@toadie.local`;
  let userId: number | undefined;
  await page.goto("/users/new");

  try {
    await page.getByRole("textbox", { name: "Name" }).fill(name);
    await page.getByRole("textbox", { name: "Email" }).fill(email);
    const [created] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.url().endsWith("/api/v1/users") &&
          response.request().method() === "POST",
      ),
      page.getByRole("button", { name: "Create" }).click(),
    ]);
    expect(created.status()).toBe(201);
    userId = (await created.json()).id;

    const dialog = await readyDialog(page, "User created");
    const show = dialog.getByRole("button", { name: "Show password" });
    await show.evaluate((button) => {
      button.setAttribute("data-e2e-suppressed-clicks", "0");
      button.addEventListener(
        "click",
        (event) => {
          const count = Number(button.getAttribute("data-e2e-suppressed-clicks") ?? "0");
          button.setAttribute("data-e2e-suppressed-clicks", String(count + 1));
          event.stopImmediatePropagation();
        },
        { capture: true, once: true },
      );
    });

    await expect(revealCreatedPassword(page)).rejects.toThrow(/Hide password/);
    await expect(show).toHaveAttribute("data-e2e-suppressed-clicks", "1");
    await expect(show).toHaveAttribute("aria-pressed", "false");

    // The one-shot suppression is gone. This is a separate intentional reveal, and the
    // returned value must authenticate the account rather than merely look unmasked.
    const password = await revealCreatedPassword(page);
    await dialog.getByRole("button", { name: "Close", exact: true }).last().click();
    await expect(page).toHaveURL(/\/users$/);
    await login(page, email, password);
    await expect(accountMenu(page)).toBeVisible();
  } finally {
    if (userId !== undefined && adminToken !== null) {
      const deleted = await page.request.delete(`/api/v1/users/${userId}`, {
        headers: { Authorization: `Bearer ${adminToken}` },
      });
      expect(deleted.status()).toBe(204);
    }
  }
});
