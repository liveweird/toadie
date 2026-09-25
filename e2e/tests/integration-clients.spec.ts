import { createUserViaUi, deleteUserRow, expect, login, readyDialog, test, uniqueText, waitForApi } from "./helpers";

test("admin creates a client, its key reads GraphQL, non-admin access is hidden, and revoke rejects the key", async ({ page, request }) => {
  let clientId: number | null = null;
  let userId: number | null = null;

  try {
    await login(page);
    const regular = await createUserViaUi(page, "E2E Integration User");
    userId = regular.id;

    const clientName = uniqueText("e2e-graphql");
    await page.goto("/integration-clients");
    await page.getByRole("textbox", { name: "Client name" }).fill(clientName);
    await page.getByLabel("Scope", { exact: true }).click();
    await page.getByRole("option", { name: "Write" }).click();
    const [created] = await Promise.all([
      waitForApi(page, { method: "POST", path: "/api/v1/integration-clients" }),
      page.getByRole("button", { name: "Add client" }).click(),
    ]);
    expect(created.status()).toBe(201);
    const createdBody = (await created.json()) as { client: { id: number; scope: string }; apiKey: string };
    clientId = createdBody.client.id;
    expect(createdBody.apiKey).toMatch(/^toadie_int_/);
    expect(createdBody.client.scope).toBe("write");
    await page.getByRole("button", { name: "Show password" }).click();
    await expect(page.locator("code")).toHaveText(createdBody.apiKey);
    const desktop = page.viewportSize();
    await page.setViewportSize({ width: 390, height: 844 });
    await expect(page.getByRole("button", { name: "Copy API key", exact: true })).toBeInViewport();
    await expect(page.getByRole("button", { name: "Hide password", exact: true })).toBeInViewport();
    expect(await page.locator("code").evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true);
    if (desktop) await page.setViewportSize(desktop);

    const firstGraphql = await request.post("/integration/graphql", {
      headers: { Authorization: `Bearer ${createdBody.apiKey}` },
      data: { query: "query { blueprints { items { identifier } } }" },
    });
    expect(firstGraphql.status()).toBe(200);
    const graphBody = (await firstGraphql.json()) as {
      data?: { blueprints?: { items?: Array<{ identifier?: string }> } };
      errors?: unknown[];
    };
    expect(graphBody.errors).toBeUndefined();
    expect(Array.isArray(graphBody.data?.blueprints?.items)).toBe(true);

    await login(page, regular.email, regular.password);
    await expect(page.getByRole("link", { name: "Integration clients" })).toHaveCount(0);
    await page.goto("/integration-clients");
    await expect(page.getByRole("heading", { name: "Entity hierarchy", exact: true })).toBeVisible();

    await login(page);
    await page.goto("/integration-clients");
    await expect(page.getByText(/ total$/)).toBeVisible();
    await expect(page.getByText(createdBody.apiKey, { exact: true })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Show password" })).toHaveCount(0);
    const lastPage = page.getByRole("button", { name: "Last page" });
    if (await lastPage.isEnabled()) await lastPage.click();
    const row = page.getByRole("row").filter({ hasText: clientName });
    await expect(row.getByText("Write", { exact: true })).toBeVisible();
    await row.getByRole("button", { name: `Operations for ${clientName}` }).click();
    await page.getByRole("menuitem", { name: `Revoke API key for ${clientName}` }).click();
    const dialog = await readyDialog(page, "Revoke this API key?");
    const [revoked] = await Promise.all([
      waitForApi(page, { method: "POST", path: `/api/v1/integration-clients/${clientId}/revoke` }),
      dialog.getByRole("button", { name: "Revoke", exact: true }).click(),
    ]);
    expect(revoked.status()).toBe(204);
    await expect(dialog).not.toBeVisible();
    clientId = null;

    const rejectedGraphql = await request.post("/integration/graphql", {
      headers: { Authorization: `Bearer ${createdBody.apiKey}` },
      data: { query: "query { blueprints { items { identifier } } }" },
    });
    expect(rejectedGraphql.status()).toBe(401);

    await deleteUserRow(page, regular.name);
    userId = null;
  } finally {
    if (clientId !== null || userId !== null) {
      await login(page);
      const token = await page.evaluate(() => localStorage.getItem("toadie.auth.token"));
      if (!token) throw new Error("admin cleanup session has no access token");
      const headers = { Authorization: `Bearer ${token}` };
      if (clientId !== null) {
        const revoke = await request.post(`/api/v1/integration-clients/${clientId}/revoke`, { headers });
        expect([204, 409]).toContain(revoke.status());
      }
      if (userId !== null) {
        const remove = await request.delete(`/api/v1/users/${userId}`, { headers });
        expect([204, 404]).toContain(remove.status());
      }
    }
  }
});
