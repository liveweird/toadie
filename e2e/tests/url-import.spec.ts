import { expect, login, test } from "./helpers";

// The fetch-from-URL wiring, exercised WITHOUT external network: a loopback URL travels
// through the real UI to the real server-side SSRF guard, whose uniform 400 renders as the
// page's fixed-vocabulary error. The happy network path deliberately stays out of e2e (no
// external dependency in CI) — UrlFetchTest covers it against a local fixture server.
test("fetching a private URL is refused with the public-https message", async ({ page }) => {
  await login(page);
  await page.goto("/files/import");

  await page
    .getByRole("textbox", { name: "Fetch from URL" })
    .fill("https://127.0.0.1/catalog-info.yaml");
  await Promise.all([
    page.waitForResponse(
      (r) => r.url().endsWith("/api/v1/files/fetch") && r.status() === 400,
    ),
    page.getByRole("button", { name: "Fetch" }).click(),
  ]);

  await expect(
    page.getByText(
      "The URL must be a public https address (GitHub/GitLab links are converted automatically).",
    ),
  ).toBeVisible();
  // Nothing landed in the textarea — the flow stops at the guard.
  await expect(page.getByRole("textbox", { name: "YAML content" })).toHaveValue("");
});
