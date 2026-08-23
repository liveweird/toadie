import { expect, login, openFilters, test, uniqueText } from "./helpers";

async function fillIdentity(page: import("@playwright/test").Page, name: string, ns: string) {
  await page.getByRole("textbox", { name: "Name", exact: true }).fill(name);
  await page.getByRole("textbox", { name: "Namespace" }).fill(ns);
}

// The multi-kind journey in a throwaway unique namespace: a Group and an API through the
// editor, a Component owned by the group — proving the org reference resolves end to end.
test("a group, an API, and a component owned by the group are created and resolve", async ({
  page,
}) => {
  await login(page);
  const ns = uniqueText("e2e-kns");
  const team = uniqueText("e2e-team");
  const api = uniqueText("e2e-api");
  const comp = uniqueText("e2e-comp");

  // A Group (children stays empty — Backstage requires the list, not entries).
  await page.goto("/catalog-files/new");
  await page.getByRole("combobox", { name: "Kind" }).click();
  await page.getByRole("option", { name: "Group" }).click();
  await fillIdentity(page, team, ns);
  await page.getByRole("combobox", { name: "Type" }).fill("team");
  await expect(page.getByLabel("YAML preview")).toContainText("children: []");
  await Promise.all([
    page.waitForResponse(
      (r) => r.url().endsWith("/api/v1/catalog-files") && r.request().method() === "POST" && r.ok(),
    ),
    page.getByRole("button", { name: "Create" }).click(),
  ]);

  // An API with its pasted definition.
  await page.goto("/catalog-files/new");
  await page.getByRole("combobox", { name: "Kind" }).click();
  await page.getByRole("option", { name: "API", exact: true }).click();
  await fillIdentity(page, api, ns);
  await page.getByRole("combobox", { name: "Type" }).fill("openapi");
  await page.getByRole("combobox", { name: "Lifecycle" }).fill("production");
  await page.getByRole("textbox", { name: "Owner" }).fill(team);
  await page.getByRole("textbox", { name: "Definition" }).fill("openapi: 3.0.0");
  await Promise.all([
    page.waitForResponse(
      (r) => r.url().endsWith("/api/v1/catalog-files") && r.request().method() === "POST" && r.ok(),
    ),
    page.getByRole("button", { name: "Create" }).click(),
  ]);

  // A Component owned by the group and providing the API — the live panel stays clean
  // (every reference resolves against the two files just stored).
  await page.goto("/catalog-files/new");
  await fillIdentity(page, comp, ns);
  await page.getByRole("combobox", { name: "Type" }).fill("service");
  await page.getByRole("combobox", { name: "Lifecycle" }).fill("production");
  await page.getByRole("textbox", { name: "Owner" }).fill(team);
  await page.getByRole("combobox", { name: "Provides APIs" }).fill(api);
  await page.keyboard.press("Enter");
  await expect(page.getByText("All checkable references resolve.")).toBeVisible();
  await Promise.all([
    page.waitForResponse(
      (r) => r.url().endsWith("/api/v1/catalog-files") && r.request().method() === "POST" && r.ok(),
    ),
    page.getByRole("button", { name: "Create" }).click(),
  ]);

  // The list shows the three kind badges for the namespace. Row-scoped asserts: the owner
  // cells repeat the team name, and the Kind filter's hidden options also contain kind words.
  await openFilters(page);
  await page.getByLabel("Namespace", { exact: true }).fill(ns);
  const apiRow = page.getByRole("row").filter({ hasText: api });
  const compRow = page.getByRole("row").filter({ hasText: comp });
  const teamRow = page
    .getByRole("row")
    .filter({ hasText: team })
    .filter({ hasNotText: api })
    .filter({ hasNotText: comp });
  await expect(apiRow.getByText("API", { exact: true })).toBeVisible();
  await expect(compRow.getByText("Component", { exact: true })).toBeVisible();
  await expect(teamRow.getByText("Group", { exact: true })).toBeVisible();

  // Cleanup: delete all three throwaway files.
  for (const name of [comp, api, team]) {
    await page.goto("/catalog-files");
    await openFilters(page);
    await page.getByLabel("Name", { exact: true }).fill(name);
    await page.getByRole("button", { name: `Delete ${name}` }).click();
    await Promise.all([
      page.waitForResponse((r) => r.request().method() === "DELETE" && r.ok()),
      page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click(),
    ]);
  }
});
