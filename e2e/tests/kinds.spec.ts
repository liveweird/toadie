import { expect, login, openFilters, pickNamespace, pickType, rowOperation, runNamespace, test, uniqueText } from "./helpers";

async function fillIdentity(page: import("@playwright/test").Page, name: string, ns: string) {
  await page.getByRole("textbox", { name: "Name", exact: true }).fill(name);
  await pickNamespace(page, ns);
}

// The multi-kind journey in this run's throwaway namespace (registered in the namespaces
// dictionary by global-setup — the form accepts only defined namespaces): a Group and an API
// through the editor, a Component owned by the group — proving the org reference resolves
// end to end. Entity names stay unique per attempt, so retries never collide on identity.
test("a group, an API, and a component owned by the group are created and resolve", async ({
  page,
}) => {
  await login(page);
  const ns = runNamespace("kinds");
  const team = uniqueText("e2e-team");
  const api = uniqueText("e2e-api");
  const comp = uniqueText("e2e-comp");

  // A Group (children stays empty — Backstage requires the list, not entries).
  await page.goto("/catalog-files/new");
  await page.getByRole("combobox", { name: "Kind" }).click();
  await page.getByRole("option", { name: "Group" }).click();
  await fillIdentity(page, team, ns);
  await pickType(page, "team");
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
  await pickType(page, "openapi");
  await page.getByRole("combobox", { name: "Lifecycle" }).fill("production");
  await page.getByRole("combobox", { name: "Owner" }).fill(team);
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
  await pickType(page, "service");
  await page.getByRole("combobox", { name: "Lifecycle" }).fill("production");
  // The owner PICKER: the just-created group is offered as its full identity and picking
  // it inserts that `group:namespace/name` form.
  await page.getByRole("combobox", { name: "Owner" }).click();
  await page.getByRole("combobox", { name: "Owner" }).fill(team.slice(0, 12));
  await page.getByRole("option", { name: `group:${ns}/${team}` }).click();
  await expect(page.getByRole("combobox", { name: "Owner" })).toHaveValue(`group:${ns}/${team}`);
  await page.getByRole("combobox", { name: "Provides APIs" }).fill(api);
  await page.keyboard.press("Enter");
  await expect(page.getByText("All references resolve.")).toBeVisible();
  await Promise.all([
    page.waitForResponse(
      (r) => r.url().endsWith("/api/v1/catalog-files") && r.request().method() === "POST" && r.ok(),
    ),
    page.getByRole("button", { name: "Create" }).click(),
  ]);

  // The list shows the three kind badges for the namespace. Row-scoped asserts (the Kind
  // filter's hidden options also contain kind words, so a page-wide getByText would clash).
  await openFilters(page);
  await pickNamespace(page, ns);
  const apiRow = page.getByRole("row").filter({ hasText: api });
  const compRow = page.getByRole("row").filter({ hasText: comp });
  const teamRow = page.getByRole("row").filter({ hasText: team });
  await expect(apiRow.getByText("API", { exact: true })).toBeVisible();
  await expect(compRow.getByText("Component", { exact: true })).toBeVisible();
  await expect(teamRow.getByText("Group", { exact: true })).toBeVisible();

  // Cleanup: delete all three throwaway files.
  for (const name of [comp, api, team]) {
    await page.goto("/catalog-files");
    await openFilters(page);
    await page.getByLabel("Name", { exact: true }).fill(name);
    await rowOperation(page, name, "Delete");
    await Promise.all([
      page.waitForResponse((r) => r.request().method() === "DELETE" && r.ok()),
      page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click(),
    ]);
  }
});
