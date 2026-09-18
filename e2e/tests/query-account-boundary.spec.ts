import {
  accountMenu,
  createUserViaUi,
  deleteUserRow,
  expect,
  login,
  openFilters,
  openQuery,
  signOut,
  test,
  uniqueText,
} from "./helpers";

async function signInWithoutClearingStorage(page: Parameters<typeof login>[0], email: string, password: string) {
  await expect(page.getByRole("button", { name: "Sign in" })).toBeVisible();
  await page.getByRole("textbox", { name: "Email" }).fill(email);
  await page.getByRole("textbox", { name: "Password" }).fill(password);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(accountMenu(page)).toBeVisible({ timeout: 15_000 });
}

function exactResponse(method: string, path: string) {
  return (response: import("@playwright/test").Response) =>
    new URL(response.url()).pathname === path && response.request().method() === method;
}

test("private entity query state stays with its account across logout and another login", async ({ page }) => {
  await login(page);
  const userA = await createUserViaUi(page, "E2E Query Owner A");
  const userB = await createUserViaUi(page, "E2E Query Owner B");
  const queryName = uniqueText("E2E private boundary query");
  const queryText = "MATCH (n) RETURN n";
  let queryId: number | undefined;

  try {
    await signOut(page);
    await signInWithoutClearingStorage(page, userA.email, userA.password);
    const tokenA = await page.evaluate(() => localStorage.getItem("toadie.auth.token"));
    expect(tokenA).not.toBeNull();

    const created = await page.request.post("/api/v1/entity-queries", {
      headers: { Authorization: `Bearer ${tokenA}` },
      data: { name: queryName, visibility: "PRIVATE", query: queryText },
    });
    expect(created.status()).toBe(201);
    queryId = (await created.json()).id;

    await page.goto("/entity-graph");
    await expect(page.getByRole("heading", { name: "Entity graph" })).toBeVisible();
    await openQuery(page);
    const pickerA = page.getByRole("combobox", { name: "Saved query", exact: true });
    const [applied] = await Promise.all([
      page.waitForResponse((response) => {
        const url = new URL(response.url());
        return exactResponse("GET", "/api/v1/entities/graph")(response) && url.searchParams.get("query") === queryText;
      }),
      (async () => {
        await pickerA.click();
        await page.getByRole("option", { name: queryName, exact: true }).click();
      })(),
    ]);
    expect(applied.status()).toBe(200);
    await expect(pickerA).toHaveValue(queryName);
    await expect(page.getByRole("textbox", { name: "Entity query" })).toHaveText(queryText);

    await page.evaluate(() => {
      localStorage.setItem("toadie.viewSettings.entityQuery.text", JSON.stringify("MATCH (legacySecret)"));
      localStorage.setItem("toadie.viewSettings.entityQuery.applied", JSON.stringify("MATCH (legacySecret)"));
      localStorage.setItem("toadie.viewSettings.entityQuery.picked", JSON.stringify("legacy-private-id"));
    });

    await signOut(page);
    await signInWithoutClearingStorage(page, userB.email, userB.password);
    await page.goto("/entity-graph");
    await expect(page.getByRole("heading", { name: "Entity graph" })).toBeVisible();
    const [userBQueries] = await Promise.all([
      page.waitForResponse(exactResponse("GET", "/api/v1/entity-queries")),
      openQuery(page),
    ]);
    expect(userBQueries.status()).toBe(200);
    const listedForB = (await userBQueries.json()).items as Array<{ id: number }>;
    expect(listedForB.some((query) => query.id === queryId)).toBe(false);
    const pickerB = page.getByRole("combobox", { name: "Saved query", exact: true });
    await expect(pickerB).toHaveValue("");
    await pickerB.click();
    await expect(page.getByRole("option", { name: queryName, exact: true })).toHaveCount(0);
    await page.keyboard.press("Escape");
    // CodeMirror renders this widget only when its document is empty. It is nested inside
    // the editable line, so textbox/line textContent includes the example rather than "".
    const emptyEditor = page.getByRole("textbox", { name: "Entity query" });
    await expect(emptyEditor.locator(".cm-placeholder")).toBeVisible();
    await expect(emptyEditor).not.toContainText(queryText);

    await openFilters(page);
    const marker = uniqueText("boundary-b");
    const [userBGraph] = await Promise.all([
      page.waitForResponse((response) => {
        const url = new URL(response.url());
        return exactResponse("GET", "/api/v1/entities/graph")(response) && url.searchParams.get("q") === marker;
      }),
      page.getByRole("textbox", { name: "Search" }).fill(marker),
    ]);
    expect(userBGraph.status()).toBe(200);
    expect(new URL(userBGraph.url()).searchParams.has("query")).toBe(false);
    const legacyState = await page.evaluate(() => ({
      text: localStorage.getItem("toadie.viewSettings.entityQuery.text"),
      applied: localStorage.getItem("toadie.viewSettings.entityQuery.applied"),
      picked: localStorage.getItem("toadie.viewSettings.entityQuery.picked"),
    }));
    expect(legacyState).toEqual({ text: null, applied: null, picked: null });

    await signOut(page);
    await signInWithoutClearingStorage(page, userA.email, userA.password);
    const [restored] = await Promise.all([
      page.waitForResponse((response) => {
        const url = new URL(response.url());
        return exactResponse("GET", "/api/v1/entities/graph")(response) &&
          url.searchParams.get("query") === queryText && url.searchParams.get("q") === marker;
      }),
      page.goto("/entity-graph"),
    ]);
    expect(restored.status()).toBe(200);
    await openQuery(page);
    await expect(page.getByRole("combobox", { name: "Saved query", exact: true })).toHaveValue(queryName);
    await expect(page.getByRole("textbox", { name: "Entity query" })).toHaveText(queryText);

    const currentTokenA = await page.evaluate(() => localStorage.getItem("toadie.auth.token"));
    const deleted = await page.request.delete(`/api/v1/entity-queries/${queryId}`, {
      headers: { Authorization: `Bearer ${currentTokenA}` },
    });
    expect(deleted.status()).toBe(204);
    queryId = undefined;
  } finally {
    if (await accountMenu(page).isVisible().catch(() => false)) await signOut(page);
    if (queryId !== undefined) {
      await signInWithoutClearingStorage(page, userA.email, userA.password);
      const cleanupToken = await page.evaluate(() => localStorage.getItem("toadie.auth.token"));
      const deleted = await page.request.delete(`/api/v1/entity-queries/${queryId}`, {
        headers: { Authorization: `Bearer ${cleanupToken}` },
      });
      expect(deleted.status()).toBe(204);
      await signOut(page);
    }
    await signInWithoutClearingStorage(page, "admin@toadie.local", "changeme");
    await deleteUserRow(page, userA.name);
    await deleteUserRow(page, userB.name);
  }
});
