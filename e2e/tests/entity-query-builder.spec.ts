import { expect, login, openFilters, openQuery, readyDialog, test, uniqueText } from "./helpers";

test("the guided query builder preserves drafts and runs the generated query on both canvases", async ({ page }) => {
  await login(page);
  const token = await page.evaluate(() => localStorage.getItem("toadie.auth.token"));
  expect(token, "the admin login must provide fixture authorization").not.toBeNull();
  const headers = { Authorization: `Bearer ${token}` };
  const marker = uniqueText("e2e-query-builder");
  const parent = `${marker}-parent`;
  const child = `${marker}-child`;
  const parentTitle = `${marker} Parent`;
  const childTitle = `${marker} Service`;
  const entityIds: number[] = [];
  const blueprintIds: number[] = [];

  try {
    const parentResponse = await page.request.post("/api/v1/blueprints", {
      headers,
      data: { identifier: parent, title: parentTitle, schema: { properties: {}, required: [] } },
    });
    expect(parentResponse.status()).toBe(201);
    blueprintIds.unshift((await parentResponse.json()).id);
    const childResponse = await page.request.post("/api/v1/blueprints", {
      headers,
      data: {
        identifier: child,
        title: childTitle,
        schema: { properties: { order: { type: "string", title: "Lifecycle", enum: ["production", "experimental"] } }, required: [] },
        relations: { parent: { title: "Parent", target: parent, many: false, required: false } },
      },
    });
    expect(childResponse.status()).toBe(201);
    blueprintIds.unshift((await childResponse.json()).id);

    for (const body of [
      { blueprint: parent, identifier: `${marker}-p1`, title: "Builder parent" },
      { blueprint: child, identifier: `${marker}-c1`, title: "Builder production service", properties: { order: "production" }, relations: { parent: `${marker}-p1` } },
      { blueprint: child, identifier: `${marker}-c2`, title: "Builder experimental service", properties: { order: "experimental" }, relations: { parent: `${marker}-p1` } },
    ]) {
      const response = await page.request.post("/api/v1/entities", { headers, data: body });
      expect(response.status()).toBe(201);
      entityIds.unshift((await response.json()).id);
    }

    await page.goto("/entity-graph");
    await expect(page.getByRole("heading", { name: "Entity graph", exact: true })).toBeVisible();
    await openFilters(page);
    await page.getByRole("textbox", { name: "Search", exact: true }).fill(marker);
    await expect(page.locator(".react-flow__node")).toHaveCount(3);
    await openQuery(page);
    const editor = page.getByRole("textbox", { name: "Entity query", exact: true });
    const original = `MATCH (n:\`${parent}\`) RETURN n`;
    await editor.fill(original);

    await page.getByRole("button", { name: "Build query", exact: true }).click();
    let dialog = await readyDialog(page, "Build query");
    await dialog.getByRole("button", { name: "Cancel", exact: true }).click();
    await expect(dialog).toBeHidden();
    await expect(editor).toContainText(original);

    await page.getByRole("button", { name: "Build query", exact: true }).click();
    dialog = await readyDialog(page, "Build query");
    await dialog.getByLabel("Entity type", { exact: true }).click();
    await page.getByRole("option", { name: childTitle, exact: true }).click();
    await dialog.getByRole("button", { name: "Add condition", exact: true }).click();
    await dialog.getByLabel("Property", { exact: true }).click();
    await page.getByRole("option", { name: "Lifecycle", exact: true }).click();
    await dialog.getByLabel("Value", { exact: true }).click();
    await page.getByRole("option", { name: "production", exact: true }).click();
    await dialog.getByLabel("Add connection", { exact: true }).check();
    await dialog.getByLabel("Relation", { exact: true }).click();
    await page.getByRole("option", { name: "Parent", exact: true }).click();
    await dialog.getByLabel("Connected entity identifier", { exact: true }).fill(`${marker}-p1`);
    await dialog.getByLabel("Results", { exact: true }).click();
    await page.getByRole("option", { name: "Both", exact: true }).click();
    await page.setViewportSize({ width: 390, height: 900 });
    await expect.poll(() => dialog.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true);
    await expect.poll(() => dialog.evaluate((element) => {
      const bounds = element.getBoundingClientRect();
      return bounds.left >= 0 && bounds.right <= window.innerWidth;
    })).toBe(true);
    await expect.poll(() => dialog.getByLabel("Entity type", { exact: true }).evaluate((element) =>
      element.getBoundingClientRect().right <= window.innerWidth,
    )).toBe(true);
    await dialog.getByRole("button", { name: "Use query", exact: true }).scrollIntoViewIfNeeded();
    await expect(dialog.getByRole("button", { name: "Use query", exact: true })).toBeInViewport();
    await page.setViewportSize({ width: 1280, height: 720 });
    await dialog.getByRole("button", { name: "Use query", exact: true }).click();
    await expect(dialog).toBeHidden();

    const generated = await editor.innerText();
    expect(generated).toContain("n.`order` = 'production'");
    expect(generated).toContain("RETURN n, m");
    await expect(page.locator(".react-flow__node")).toHaveCount(3);
    await expect(page.getByTestId("entityQuery-applied")).toHaveCount(0);

    const responsePromise = page.waitForResponse((response) => {
      const url = new URL(response.url());
      return response.request().method() === "GET" && url.pathname === "/api/v1/entities/graph"
        && url.searchParams.get("query") === generated && url.searchParams.get("q") === marker;
    });
    await page.getByRole("button", { name: "Run", exact: true }).click();
    expect((await responsePromise).status()).toBe(200);
    await expect(page.locator(".react-flow__node")).toHaveCount(2);
    await expect(page.locator(".react-flow__node", { hasText: "Builder production service" })).toBeVisible();
    await expect(page.locator(".react-flow__node", { hasText: "Builder parent" })).toBeVisible();

    await page.goto("/entity-hierarchy");
    await expect(page.getByRole("heading", { name: "Entity hierarchy", exact: true })).toBeVisible();
    await openQuery(page);
    await expect(page.getByRole("textbox", { name: "Entity query", exact: true })).toContainText(generated);
    await expect(page.getByText("Builder production service", { exact: true })).toBeVisible();
    await expect(page.getByText("Builder parent", { exact: true })).toBeVisible();
    await expect(page.getByText("Builder experimental service", { exact: true })).toHaveCount(0);
    await page.getByRole("button", { name: "Build query", exact: true }).click();
    dialog = await readyDialog(page, "Build query");
    await dialog.getByRole("button", { name: "Cancel", exact: true }).click();
    await expect(dialog).toBeHidden();
    await expect(page.getByRole("textbox", { name: "Entity query", exact: true })).toContainText(generated);
  } finally {
    for (const id of entityIds) {
      expect((await page.request.delete(`/api/v1/entities/${id}`, { headers })).status()).toBe(204);
    }
    for (const id of blueprintIds) {
      expect((await page.request.delete(`/api/v1/blueprints/${id}`, { headers })).status()).toBe(204);
    }
  }
});
