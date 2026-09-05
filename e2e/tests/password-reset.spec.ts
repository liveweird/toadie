import { createUserViaUi, deleteUserRow, expect, login, MAILPIT, signOut, test, uniqueText } from "./helpers";

// Self-service password reset: the "Forgot password?" flow on the login screen.
// The full email roundtrip needs the compose stack's Mailpit catcher (http://localhost:8026 —
// 8025 is Lettuce's); when it is unreachable (e.g. a dev stack on the log transport) that one
// test skips itself. The dev stack lifts the per-IP reset bucket (100/min, the login-bucket
// idiom), so back-to-back runs never trip it; the per-email throttle is exercised explicitly.


function uniqueEmail(prefix: string): string {
  return `${uniqueText(prefix).toLowerCase().replace(/[^a-z0-9-]/g, "-")}@toadie.local`;
}

test("the forgot-password link leads to the reset form; unknown emails get the neutral answer", async ({
  page,
}) => {
  await page.goto("/login");
  await page.getByRole("link", { name: "Forgot password?" }).click();
  await expect(page).toHaveURL(/\/reset-password$/);
  // Lazy route: wait for an element unique to the reset page before interacting.
  await expect(page.getByRole("button", { name: "Send reset link" })).toBeVisible();

  await page.getByRole("textbox", { name: "Email" }).fill(uniqueEmail("e2e-reset-nobody"));
  await page.getByRole("button", { name: "Send reset link" }).click();
  await expect(page.getByText(/if an account with this address exists/i)).toBeVisible();

  // A second request for the same address within a minute is throttled with a clear message.
  const throttled = uniqueEmail("e2e-reset-throttle");
  await page.goto("/reset-password");
  await page.getByRole("textbox", { name: "Email" }).fill(throttled);
  await page.getByRole("button", { name: "Send reset link" }).click();
  await expect(page.getByText(/if an account with this address exists/i)).toBeVisible();
  await page.goto("/reset-password");
  await page.getByRole("textbox", { name: "Email" }).fill(throttled);
  await page.getByRole("button", { name: "Send reset link" }).click();
  await expect(page.getByText(/only one reset request per minute/i)).toBeVisible();
});

test("a reset link preserves the old password until confirmation and cannot be reused", async ({ page }) => {
  const mailpitUp = await fetch(`${MAILPIT}/api/v1/messages`).then(
    (r) => r.ok,
    () => false,
  );
  if (process.env.CI) expect(mailpitUp, "CI must exercise the password-reset email roundtrip").toBeTruthy();
  test.skip(!mailpitUp, "Mailpit (compose stack) is not reachable — email roundtrip untestable");

  await login(page);
  const user = await createUserViaUi(page, "E2E-Reset");
  try {
    await signOut(page);
    await page.goto("/reset-password");
    await page.getByRole("textbox", { name: "Email" }).fill(user.email);
    await page.getByRole("button", { name: "Send reset link" }).click();
    await expect(page.getByText(/if an account with this address exists/i)).toBeVisible();

    let resetUrl: string | undefined;
    await expect.poll(async () => {
      const list = await fetch(`${MAILPIT}/api/v1/messages`).then((r) => r.json());
      const msg = list.messages?.find((m: { Subject: string; To?: { Address: string }[] }) =>
        m.Subject === "Reset your Toadie password" && m.To?.some((t) => t.Address === user.email),
      );
      if (!msg) return undefined;
      const text: string = (await fetch(`${MAILPIT}/api/v1/message/${msg.ID}`).then((r) => r.json())).Text;
      expect(text).toContain("Your password is unchanged");
      resetUrl = text.match(/https?:\/\/\S+\/reset-password\/confirm#token=[A-Za-z0-9_-]{43}/)?.[0];
      return resetUrl;
    }, { timeout: 15_000 }).toBeTruthy();

    // Requesting a reset preserves the password; the public confirmation page also works signed in.
    await login(page, user.email, user.password);
    const oldToken = await page.evaluate(() => localStorage.getItem("toadie.auth.token"));
    expect(oldToken).toBeTruthy();
    await page.goto(resetUrl!);
    await expect(page.getByRole("button", { name: "Set new password" })).toBeVisible();
    await expect(page).toHaveURL(/\/reset-password\/confirm$/);
    const stillActive = await page.request.get("/api/v1/users", {
      headers: { Authorization: `Bearer ${oldToken}` },
    });
    // Regular users cannot list users, but 403 proves the session survived opening the link.
    expect(stillActive.status()).toBe(403);

    const chosenPassword = "Reset-chosen-password-123";
    await page.getByRole("textbox", { name: "New password", exact: true }).fill(chosenPassword);
    await page.getByRole("textbox", { name: "Confirm new password", exact: true }).fill(chosenPassword);
    await page.getByRole("button", { name: "Set new password" }).click();
    await expect(page.getByText(/your password has been reset/i)).toBeVisible();
    expect((await page.request.get("/api/v1/users", {
      headers: { Authorization: `Bearer ${oldToken}` },
    })).status()).toBe(401);
    await login(page, user.email, chosenPassword);
    await signOut(page);

    await page.goto("/login");
    await page.getByRole("textbox", { name: "Email" }).fill(user.email);
    await page.getByRole("textbox", { name: "Password" }).fill(user.password);
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page.getByText("Invalid email or password")).toBeVisible();

    await page.goto(resetUrl!);
    await page.getByRole("textbox", { name: "New password", exact: true }).fill(chosenPassword);
    await page.getByRole("textbox", { name: "Confirm new password", exact: true }).fill(chosenPassword);
    await page.getByRole("button", { name: "Set new password" }).click();
    await expect(page.getByText(/this reset link is invalid/i)).toBeVisible();
    await expect(page.getByRole("link", { name: "Request another reset link" })).toBeVisible();
  } finally {
    await login(page);
    await deleteUserRow(page, user.name);
  }
});
