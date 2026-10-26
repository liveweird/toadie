import { expect, login, readyDialog, test, uniqueText, waitForApi } from "./helpers";

// The Backstage-catalog source of the Ontology import page (conversion phase 5c): a throwaway
// Port world (a team blueprint and a service blueprint whose `owner` relation targets it, plus a
// conversion mapping that sends Group -> team and Component -> service) is seeded through the API;
// the admin switches the import to "Backstage catalog (YAML)", pastes a two-document catalog
// (a Group and a Component it is owned by), picks the mapping, previews the conversion, runs the
// dry-run Check, imports through the confirm dialog (it converts once and dry-runs; Confirm imports
// exactly that previewed batch), and imports once more (replace-existing is implicit, so the second
// import reports Updated) — this time with the team edited through the API while the dialog is open:
// that row is refused as "Changed meanwhile" and the re-opened dialog converts afresh. Owns only
// its own `e2e-bi-*` blueprints, entities and mapping — every locator is anchored on the run marker.
test("a Backstage catalog converts into Port entities: preview, check, import and re-import", async ({
  page,
}) => {
  await login(page);
  const token = await page.evaluate(() => localStorage.getItem("toadie.auth.token"));
  expect(token !== null, "the admin login must provide setup/cleanup authorization").toBe(true);
  const authHeaders = { Authorization: `Bearer ${token}` };

  const run = uniqueText("e2e-bi");
  const teamBlueprint = `${run}-grp`;
  const serviceBlueprint = `${run}-bp`;
  const teamName = `${run}-team`;
  const serviceName = `${run}-svc`;
  const mappingName = `${run}-map`;

  const created: { mapping?: number; serviceBp?: number; teamBp?: number } = {};

  try {
    // 0. Seed the blueprints and the mapping through the API (no entities: the import creates them).
    const post = async (path: string, data: unknown) => {
      const response = await page.request.post(path, { headers: authHeaders, data });
      expect(response.status(), `${path}: ${await response.text()}`).toBe(201);
      return (await response.json()).id as number;
    };
    created.teamBp = await post("/api/v1/blueprints", {
      identifier: teamBlueprint,
      title: "E2E Import Team",
      schema: { properties: {}, required: [] },
    });
    created.serviceBp = await post("/api/v1/blueprints", {
      identifier: serviceBlueprint,
      title: "E2E Import Service",
      schema: { properties: { type: { type: "string", enum: ["service"] } }, required: [] },
      relations: { owner: { title: "Owner", target: teamBlueprint, required: false, many: false } },
    });
    created.mapping = await post("/api/v1/conversion-mappings", {
      name: mappingName,
      document: {
        schemaVersion: 1,
        apiVersion: "backstage.io/v1alpha1",
        pairs: [
          {
            blueprint: serviceBlueprint,
            kind: "Component",
            rules: [
              { port: { property: "type" }, backstage: { spec: "type" }, codec: "copy" },
              { backstage: { spec: "lifecycle" }, codec: "constant", value: "production" },
              { port: { relation: "owner" }, backstage: { spec: "owner" }, codec: "ref" },
              { port: { meta: "title" }, backstage: { metadata: "title" }, codec: "copy" },
            ],
          },
          {
            blueprint: teamBlueprint,
            kind: "Group",
            rules: [
              { backstage: { spec: "type" }, codec: "constant", value: "team" },
              { port: { meta: "title" }, backstage: { metadata: "title" }, codec: "copy" },
            ],
          },
        ],
      },
    });

    // 1. Open the Ontology import page and switch to the Backstage catalog source.
    await page.getByRole("link", { name: "Import", exact: true }).click();
    await expect(page.getByRole("heading", { name: "Import ontology", exact: true })).toBeVisible();
    await expect(page.getByRole("textbox", { name: "JSON content" })).toBeVisible();
    // Mantine hides the native radios: click the visible label inside the source radiogroup, after
    // its own `data-initialized` readiness marker (the `switchWorld` helper's posture).
    const source = page.getByRole("radiogroup", { name: "Import source" });
    await expect(source).toHaveAttribute("data-initialized", "true");
    await source.getByText("Backstage catalog (YAML)", { exact: true }).click();
    const yamlBox = page.getByRole("textbox", { name: "YAML content" });
    await expect(yamlBox).toBeVisible();
    await expect(page.getByRole("textbox", { name: "JSON content" })).toHaveCount(0);

    // 2. Paste a small catalog (a Group and a Component it owns) and pick the mapping.
    await yamlBox.fill(
      [
        "apiVersion: backstage.io/v1alpha1",
        "kind: Group",
        "metadata:",
        `  name: ${teamName}`,
        "spec:",
        "  type: team",
        "  children: []",
        "---",
        "apiVersion: backstage.io/v1alpha1",
        "kind: Component",
        "metadata:",
        `  name: ${serviceName}`,
        "spec:",
        "  type: service",
        "  lifecycle: production",
        `  owner: group:default/${teamName}`,
        "",
      ].join("\n"),
    );
    await expect(page.getByText("2 documents ready to import")).toBeVisible();
    await page.getByRole("combobox", { name: "Conversion mapping" }).click();
    await page.getByRole("option", { name: mappingName }).click();

    // 3. Convert previews the per-file report and stores nothing.
    const [converted] = await Promise.all([
      waitForApi(page, { method: "POST", path: `/api/v1/conversion-mappings/${created.mapping}/entity-documents` }),
      page.getByRole("button", { name: "Convert" }).click(),
    ]);
    expect(converted.status()).toBe(200);
    const conversion = page.getByRole("table", { name: "Conversion rows" });
    await expect(conversion.getByText(`Group:default/${teamName}`)).toBeVisible();
    await expect(conversion.getByText(`${teamBlueprint} / ${teamName}`)).toBeVisible();
    await expect(conversion.getByText(`Component:default/${serviceName}`)).toBeVisible();
    await expect(conversion.getByText(`${serviceBlueprint} / ${serviceName}`)).toBeVisible();

    // 4. Check dry-runs the entity import (a fresh conversion first): both would be created.
    const [checked] = await Promise.all([
      waitForApi(page, { method: "POST", path: "/api/v1/entities/import/check" }),
      page.getByRole("button", { name: "Check" }).click(),
    ]);
    expect(checked.status()).toBe(200);
    const results = page.getByRole("table", { name: "Import results" });
    await expect(results.getByText("Would be created", { exact: true })).toHaveCount(2);

    // 5. Import never fires from a bare click: it opens a confirm dialog that converts afresh and
    // dry-runs first (nothing stored yet), and only Confirm writes.
    await page.getByRole("button", { name: "Import", exact: true }).click();
    const dialog = await readyDialog(page, "Import into Port");
    await expect(dialog.getByText("Would be created", { exact: true })).toHaveCount(2);
    await expect(dialog.getByText(/Merge is off/)).toHaveCount(0);
    const [imported] = await Promise.all([
      waitForApi(page, { method: "POST", path: "/api/v1/entities/import" }),
      dialog.getByRole("button", { name: "Confirm import" }).click(),
    ]);
    expect(imported.status()).toBe(200);
    await expect(dialog.getByText("Created", { exact: true })).toHaveCount(2);
    await dialog.getByRole("button", { name: "Close" }).click();
    await expect(dialog).toHaveCount(0);
    const stored = await page.request.get(`/api/v1/entities?blueprint=${encodeURIComponent(serviceBlueprint)}`, {
      headers: authHeaders,
    });
    expect(stored.status()).toBe(200);
    const [service] = ((await stored.json()).items ?? []) as { identifier: string; relations: Record<string, unknown> }[];
    expect(service.identifier).toBe(serviceName);
    expect(service.relations.owner).toBe(teamName);

    // 6. Importing again goes through the dialog again (a conversion merging over the stored
    // entities). While it is open the team is edited through the API: its previewed token is then
    // out of date, so Confirm refuses that row ("Changed meanwhile") and still replaces the service.
    await page.getByRole("button", { name: "Import", exact: true }).click();
    const again = await readyDialog(page, "Import into Port");
    await expect(again.getByText("Would be updated", { exact: true })).toHaveCount(2);
    const teams = await page.request.get(`/api/v1/entities?blueprint=${encodeURIComponent(teamBlueprint)}`, {
      headers: authHeaders,
    });
    const [team] = ((await teams.json()).items ?? []) as { id: number; identifier: string }[];
    expect(team.identifier).toBe(teamName);
    const edited = await page.request.put(`/api/v1/entities/${team.id}`, {
      headers: authHeaders,
      data: { blueprint: teamBlueprint, identifier: teamName, title: `${run} edited meanwhile`, properties: {}, relations: {} },
    });
    expect(edited.status(), await edited.text()).toBe(204);
    const [reimported] = await Promise.all([
      waitForApi(page, { method: "POST", path: "/api/v1/entities/import" }),
      again.getByRole("button", { name: "Confirm import" }).click(),
    ]);
    expect(reimported.status()).toBe(200);
    await expect(again.getByText("Changed meanwhile", { exact: true })).toHaveCount(1);
    await expect(again.getByText("Updated", { exact: true })).toHaveCount(1);
    await again.getByRole("button", { name: "Close" }).click();
    await expect(again).toHaveCount(0);
    const kept = await page.request.get(`/api/v1/entities/${team.id}`, { headers: authHeaders });
    expect((await kept.json()).title).toBe(`${run} edited meanwhile`);

    // 7. Opening the dialog again converts afresh, so it carries fresh tokens: both would update.
    await page.getByRole("button", { name: "Import", exact: true }).click();
    const fresh = await readyDialog(page, "Import into Port");
    await expect(fresh.getByText("Would be updated", { exact: true })).toHaveCount(2);
    await fresh.getByRole("button", { name: "Cancel" }).click();
    await expect(fresh).toHaveCount(0);
  } finally {
    // Cleanup in dependency order: entities (service before its team), mapping, blueprints.
    for (const blueprint of [serviceBlueprint, teamBlueprint]) {
      const list = await page.request.get(`/api/v1/entities?blueprint=${encodeURIComponent(blueprint)}&pageSize=100`, {
        headers: authHeaders,
      });
      if (list.ok()) {
        const ours = ((await list.json()).items ?? []) as { id: number; identifier: string }[];
        for (const entity of ours.filter((e) => e.identifier.startsWith(`${run}-`))) {
          await page.request.delete(`/api/v1/entities/${entity.id}`, { headers: authHeaders });
        }
      }
    }
    const remove = async (path: string, id: number | undefined) => {
      if (id !== undefined) await page.request.delete(`${path}/${id}`, { headers: authHeaders });
    };
    await remove("/api/v1/conversion-mappings", created.mapping);
    await remove("/api/v1/blueprints", created.serviceBp);
    await remove("/api/v1/blueprints", created.teamBp);
  }
});
