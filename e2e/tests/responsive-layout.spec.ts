import type { Page } from "@playwright/test";
import { expect, login, openFilters, openQuery, test, uniqueText, waitForApi } from "./helpers";

async function expectContainedPage(page: Page) {
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(1);
}

const REGISTRIES = [
  { path: "/blueprints", title: "Blueprints", api: "/blueprints" },
  { path: "/labels", title: "Labels", api: "/labels" },
  { path: "/tags", title: "Tags", api: "/tag-categories" },
  { path: "/types", title: "Types", api: "/entity-types" },
  { path: "/annotations", title: "Annotations", api: "/annotation-keys" },
];

test("data lists fill the desktop canvas and contain scrolling on narrow screens", async ({ page }) => {
  await login(page);
  await page.setViewportSize({ width: 1920, height: 1080 });
  for (const screen of REGISTRIES) {
    const loaded = waitForApi(page, { method: "GET", path: `/api/v1${screen.api}` });
    await page.goto(screen.path);
    expect((await loaded).status()).toBe(200);
    await expect(page.getByRole("heading", { name: screen.title, exact: true })).toBeVisible();
    await expect(page.getByRole("table")).toBeVisible();
    // The main canvas excludes the sidebar and its own padding. A global overflow:hidden
    // workaround would not satisfy this width assertion or the row-action check below.
    await expect.poll(() => page.getByRole("table").evaluate((table) => {
      const main = document.querySelector("main")!;
      const style = getComputedStyle(main);
      const available = main.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight);
      return Math.abs(table.getBoundingClientRect().width - available);
    })).toBeLessThanOrEqual(2);
    await page.setViewportSize({ width: 390, height: 844 });
    await expectContainedPage(page);
    const action = page.getByRole("table").getByRole("button", { name: /^Edit / }).first();
    await action.scrollIntoViewIfNeeded();
    await expect(action).toBeInViewport();
    await expectContainedPage(page);
    await page.setViewportSize({ width: 1920, height: 1080 });
  }

  await page.setViewportSize({ width: 390, height: 844 });
  for (const screen of [
    { path: "/users", title: "Users", api: "/users" },
    { path: "/feature-flags", title: "Feature flags", api: "/users" },
    { path: "/errors", title: "Errors", api: "/files/errors" },
    { path: "/ontology/errors", title: "Errors", api: "/entities/errors" },
  ]) {
    const loaded = waitForApi(page, { method: "GET", path: `/api/v1${screen.api}` });
    await page.goto(screen.path);
    expect((await loaded).status()).toBe(200);
    await expect(page.getByRole("heading", { name: screen.title, exact: true })).toBeVisible();
    await expect(page.getByRole("table")).toBeVisible();
    await expectContainedPage(page);
  }
  await page.goto("/changelog");
  await expect(page.getByRole("heading", { name: "Changelog", exact: true })).toBeVisible();
  await expectContainedPage(page);
});

test("populated entity lists, deep hierarchy rows and query controls remain usable on mobile", async ({ page }) => {
  await login(page);
  const token = await page.evaluate(() => localStorage.getItem("toadie.auth.token"));
  expect(token).not.toBeNull();
  const headers = { Authorization: `Bearer ${token}` };
  const marker = uniqueText("e2e-layout");
  const entityIds: number[] = [];
  let blueprintId: number | undefined;
  const identifiers = Array.from({ length: 6 }, (_, i) => `${marker}-${"x".repeat(70)}-level-${i}`);
  const titles = identifiers.map((identifier) => `${identifier} service with a long readable title`);
  try {
    const response = await page.request.post("/api/v1/blueprints", {
      headers,
      data: {
        identifier: marker, title: "Responsive services",
        schema: { properties: Object.fromEntries(["type", "lifecycle", "exposure"].map((key) =>
          [key, { type: "string", title: key === "lifecycle" ? "LongPropertyHeading".repeat(5) : key }])), required: [] },
        relations: { parent: { title: "Parent", target: marker, many: false, required: false } },
        hierarchyRelations: { composition: "parent" },
        mirrorProperties: { parent_identifier: { title: "Parent identifier", path: "parent.$identifier" } },
      },
    });
    expect(response.status()).toBe(201);
    blueprintId = (await response.json()).id;
    for (const [i, identifier] of identifiers.entries()) {
      const created = await page.request.post("/api/v1/entities", {
        headers,
        data: {
          blueprint: marker, identifier, title: titles[i],
          properties: { type: "service", lifecycle: "experimental", exposure: "internal" },
          relations: i === 0 ? {} : { parent: identifiers[i - 1] },
        },
      });
      expect(created.status()).toBe(201);
      entityIds.unshift((await created.json()).id);
    }
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto(`/entities?blueprint=${marker}`);
    const row = page.getByRole("row").filter({ has: page.getByRole("link", { name: `Edit ${identifiers[1]}`, exact: true }) });
    await expect(row).toBeVisible();
    await expect(page.getByRole("columnheader")).toHaveCount(10);
    // Text must fit each cell even when the table scrolls: containment alone missed the
    // old fixed 640px table's overlapping identifier/title and nowrap header text.
    for (const cells of [page.getByRole("columnheader"), row.getByRole("cell")]) {
      await expect.poll(() => cells.evaluateAll((elements) => elements
        .filter((cell) => cell.scrollWidth > cell.clientWidth + 1)
        .map((cell) => ({ text: cell.textContent, width: cell.clientWidth, contentWidth: cell.scrollWidth })),
      )).toEqual([]);
    }
    const edit = row.getByRole("button", { name: `Edit ${identifiers[1]}`, exact: true });
    await edit.scrollIntoViewIfNeeded();
    await expect(edit).toBeInViewport();
    await expectContainedPage(page);

    await page.goto("/entity-hierarchy");
    await expect(page.getByRole("heading", { name: "Entity hierarchy", exact: true })).toBeVisible();
    await openFilters(page);
    await page.getByRole("textbox", { name: "Search", exact: true }).fill(marker);
    await page.getByRole("combobox", { name: "Hierarchy", exact: true }).click();
    await page.getByRole("option", { name: "composition", exact: true }).click();
    const deepest = page.getByRole("button", { name: `Operations for ${titles[5]}`, exact: true });
    await deepest.scrollIntoViewIfNeeded();
    await expect(deepest).toBeInViewport();
    await deepest.click();
    await expect(page.getByRole("menu")).toBeVisible();
    await page.keyboard.press("Escape");
    await expectContainedPage(page);

    for (const path of ["/entity-graph", "/entity-hierarchy"]) {
      await page.goto(path);
      await expect(page.getByRole("heading", { name: path === "/entity-graph" ? "Entity graph" : "Entity hierarchy", exact: true })).toBeVisible();
      await openQuery(page);
      const editor = page.getByRole("textbox", { name: "Entity query", exact: true });
      await editor.fill(`MATCH (n:\`${marker}\`) RETURN n`);
      await expect.poll(() => editor.evaluate((element) => element.getBoundingClientRect().width)).toBeGreaterThan(280);
      const run = page.getByRole("button", { name: "Run", exact: true });
      await expect(run).toBeEnabled();
      await expect.poll(async () => {
        const editorBox = await editor.boundingBox();
        const runBox = await run.boundingBox();
        return !!editorBox && !!runBox && runBox.y >= editorBox.y + editorBox.height;
      }).toBe(true);
      await expectContainedPage(page);
    }
  } finally {
    // Reserve cleanup time even when a browser assertion has exhausted the journey budget.
    test.setTimeout(test.info().timeout + 15_000);
    for (const id of entityIds) expect((await page.request.delete(`/api/v1/entities/${id}`, { headers })).status()).toBe(204);
    if (blueprintId !== undefined) expect((await page.request.delete(`/api/v1/blueprints/${blueprintId}`, { headers })).status()).toBe(204);
  }
});
