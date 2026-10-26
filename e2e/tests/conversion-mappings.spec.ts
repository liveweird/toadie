import { expect, login, readyDialog, test, uniqueText, waitForApi } from "./helpers";

// The conversion-mapping registry journey (conversion phase 5a): the admin opens the Port
// world's Conversion mappings page from the sidebar, a mapping naming a blueprint that does not
// exist is REFUSED with its positioned finding shown beside the editor (nothing stored), the
// minimal valid document is created and appears with the Valid chip, is renamed through the
// editor, and is deleted through the confirm dialog. The registry is shared run-state (unique
// names, at most 20 active mappings): this spec only ever creates and deletes its own
// `e2e-cm-*` mappings and never touches a foreign row.
test("admin curates a conversion mapping: a bad document is refused with findings, a valid one is created, renamed and deleted", async ({
  page,
}) => {
  await login(page);
  const token = await page.evaluate(() => localStorage.getItem("toadie.auth.token"));
  expect(token !== null, "the admin login must provide cleanup authorization").toBe(true);
  const authHeaders = { Authorization: `Bearer ${token}` };

  const name = uniqueText("e2e-cm");
  const renamed = `${name}-renamed`;
  const missingBlueprint = uniqueText("e2e-cm-nope");

  try {
    // 1. Open the registry from the sidebar's Ontology section.
    await page.getByRole("link", { name: "Conversion mappings", exact: true }).click();
    await expect(page.getByRole("heading", { name: "Conversion mappings", exact: true })).toBeVisible();
    await expect(page.getByRole("link", { name: "New mapping" })).toBeVisible();
    await expect(page.getByRole("link", { name: "Errors report" })).toBeVisible();

    // 2. A document whose pair names a blueprint that does not exist is refused: the editor stays
    // on its route and the aside lists the finding with its JSON pointer.
    await page.getByRole("link", { name: "New mapping" }).click();
    await expect(page.getByRole("heading", { name: "New conversion mapping" })).toBeVisible();
    await page.getByRole("textbox", { name: "Name" }).fill(name);
    const badDocument = JSON.stringify({
      schemaVersion: 1,
      apiVersion: "backstage.io/v1alpha1",
      pairs: [{ blueprint: missingBlueprint, kind: "Component", rules: [] }],
    });
    await page.getByRole("textbox", { name: /^Mapping document/ }).fill(badDocument);
    const [refused] = await Promise.all([
      waitForApi(page, { method: "POST", path: "/api/v1/conversion-mappings" }),
      page.getByRole("button", { name: "Create" }).click(),
    ]);
    expect(refused.status()).toBe(400);
    await expect(page.getByText("The mapping was not saved — it has blocking findings")).toBeVisible();
    await expect(page.getByText("Unknown blueprint", { exact: true })).toBeVisible();
    await expect(page.getByText("/pairs/0/blueprint", { exact: true })).toBeVisible();
    await expect(page).toHaveURL(/\/ontology\/mappings\/new$/);

    // 3. Back to the minimal valid document: the create succeeds and the list shows the row as Valid.
    const validDocument = JSON.stringify({ schemaVersion: 1, apiVersion: "backstage.io/v1alpha1", pairs: [] });
    await page.getByRole("textbox", { name: /^Mapping document/ }).fill(validDocument);
    const [created] = await Promise.all([
      waitForApi(page, { method: "POST", path: "/api/v1/conversion-mappings" }),
      page.getByRole("button", { name: "Create" }).click(),
    ]);
    expect(created.status()).toBe(201);
    await expect(page).toHaveURL(/\/ontology\/mappings$/);
    const row = page.getByRole("row").filter({ hasText: name });
    await expect(row.getByText("Valid", { exact: true })).toBeVisible();

    // 4. Rename it through the editor.
    await page.getByRole("link", { name: `Edit ${name}` }).click();
    await expect(page.getByRole("heading", { name: "Edit conversion mapping" })).toBeVisible();
    const nameInput = page.getByRole("textbox", { name: "Name" });
    await expect(nameInput).toHaveValue(name);
    await nameInput.fill(renamed);
    const [saved] = await Promise.all([
      page.waitForResponse((r) => new URL(r.url()).pathname.startsWith("/api/v1/conversion-mappings/") && r.request().method() === "PUT"),
      page.getByRole("button", { name: "Save" }).click(),
    ]);
    expect(saved.status()).toBe(204);
    await expect(page.getByRole("row").filter({ hasText: renamed })).toBeVisible();

    // 5. Delete it through the confirm dialog.
    await page.getByRole("button", { name: `Delete ${renamed}` }).click();
    const dialog = await readyDialog(page, "Delete conversion mapping?");
    const [deleted] = await Promise.all([
      page.waitForResponse((r) => new URL(r.url()).pathname.startsWith("/api/v1/conversion-mappings/") && r.request().method() === "DELETE"),
      dialog.getByRole("button", { name: "Delete", exact: true }).click(),
    ]);
    expect(deleted.status()).toBe(204);
    await expect(page.getByRole("row").filter({ hasText: renamed })).toHaveCount(0);
  } finally {
    // Idempotent cleanup of anything the journey left behind (an aborted run), by name.
    const list = await page.request.get("/api/v1/conversion-mappings", { headers: authHeaders });
    if (list.ok()) {
      const items = ((await list.json()).items ?? []) as { id: number; name: string }[];
      for (const item of items.filter((m) => m.name === name || m.name === renamed)) {
        await page.request.delete(`/api/v1/conversion-mappings/${item.id}`, { headers: authHeaders });
      }
    }
  }
});
