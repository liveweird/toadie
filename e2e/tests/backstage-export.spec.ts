import { expect, login, openQuery, readyDialog, test, uniqueText, waitForApi } from "./helpers";

// The Export-to-Backstage journey (conversion phase 5b): a throwaway Port world (a team
// blueprint and a service blueprint whose `owner` relation targets it, one entity each, and a
// conversion mapping that sends them to a Group and a Component) is seeded through the API; the
// admin selects the service and its owning team with an entity query, converts, reads the report
// and the YAML preview, creates the two catalog files through the dry-run-then-confirm dialog,
// and converts again to see the merge find the stored files (the dry run then predicts no change).
// The dialog converts once when it opens and Confirm imports exactly that previewed batch: a file
// edited through the API meanwhile is refused as "Changed meanwhile", the untouched one stays Unchanged.
// Owns only its own `e2e-be-*` blueprints, entities, mapping and the two catalog files they
// produce (default namespace, names carrying the run marker) — every locator is anchored on the
// run marker, so parallel specs' rows can never make an assertion ambiguous.
test("an owned fixture exports to Backstage: rows and YAML, then the catalog files are created and re-converted onto", async ({
  page,
}) => {
  await login(page);
  const token = await page.evaluate(() => localStorage.getItem("toadie.auth.token"));
  expect(token !== null, "the admin login must provide setup/cleanup authorization").toBe(true);
  const authHeaders = { Authorization: `Bearer ${token}` };

  const run = uniqueText("e2e-be");
  const teamBlueprint = `${run}-grp`;
  const serviceBlueprint = `${run}-bp`;
  const teamId = `${run}-team`;
  const serviceId = `${run}-svc`;
  const mappingName = `${run}-map`;

  const created: { mapping?: number; service?: number; team?: number; serviceBp?: number; teamBp?: number } = {};

  try {
    // 0. Seed through the API: team blueprint, service blueprint (owner -> team), one entity each,
    // and the mapping (Component for services with a ref owner, Group for teams).
    const post = async (path: string, data: unknown) => {
      const response = await page.request.post(path, { headers: authHeaders, data });
      expect(response.status(), `${path}: ${await response.text()}`).toBe(201);
      return (await response.json()).id as number;
    };
    created.teamBp = await post("/api/v1/blueprints", {
      identifier: teamBlueprint,
      title: "E2E Export Team",
      schema: { properties: {}, required: [] },
      // A Group needs its `children` list written: the parent relation and its reverse feed it.
      relations: { parent: { title: "Parent", target: teamBlueprint, required: false, many: false } },
    });
    created.serviceBp = await post("/api/v1/blueprints", {
      identifier: serviceBlueprint,
      title: "E2E Export Service",
      schema: { properties: { type: { type: "string", enum: ["service"] } }, required: [] },
      relations: { owner: { title: "Owner", target: teamBlueprint, required: false, many: false } },
    });
    created.team = await post("/api/v1/entities", {
      blueprint: teamBlueprint,
      identifier: teamId,
      title: "E2E Export Team",
      properties: {},
      relations: {},
    });
    created.service = await post("/api/v1/entities", {
      blueprint: serviceBlueprint,
      identifier: serviceId,
      title: "E2E Export Service",
      properties: { type: "service" },
      relations: { owner: teamId },
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
              { port: { relation: "parent" }, backstage: { spec: "parent" }, codec: "ref" },
              {
                port: { reverse: { blueprint: teamBlueprint, relation: "parent" } },
                backstage: { spec: "children" },
                codec: "derive",
              },
            ],
          },
        ],
      },
    });

    // 1. Open Export to Backstage from the sidebar and pick the throwaway mapping explicitly (it is
    // the only default when it is alone in the registry, but other specs' mappings may exist).
    await page.getByRole("link", { name: "Export to Backstage", exact: true }).click();
    await expect(page.getByRole("heading", { name: "Export to Backstage", exact: true })).toBeVisible();
    await page.getByRole("combobox", { name: "Conversion mapping" }).click();
    await page.getByRole("option", { name: mappingName }).click();
    await expect(page.getByRole("button", { name: "Convert" })).toBeEnabled();

    // 2. Narrow the selection to this run's service and its team with an entity query, then convert.
    const query = `MATCH (a:\`${serviceBlueprint}\`) OPTIONAL MATCH (a)-[:owner]->(t:\`${teamBlueprint}\`) RETURN a, t`;
    await openQuery(page);
    const queryBox = page.getByRole("textbox", { name: "Entity query" });
    await queryBox.click();
    await queryBox.pressSequentially(query);
    await expect(queryBox).toContainText(query);
    await page.getByRole("button", { name: "Run" }).click();

    const [converted] = await Promise.all([
      page.waitForResponse((r) => {
        const url = new URL(r.url());
        return (
          url.pathname === `/api/v1/conversion-mappings/${created.mapping}/catalog-documents` &&
          r.request().method() === "GET" &&
          url.searchParams.has("query")
        );
      }),
      page.getByRole("button", { name: "Convert" }).click(),
    ]);
    expect(converted.status()).toBe(200);

    // 3. The report has exactly the two rows, each mapped to its Backstage identity, and the YAML
    // preview shows both documents with the owner rendered from the team's converted identity.
    const table = page.getByRole("table", { name: "Conversion rows" });
    await expect(table.getByRole("row")).toHaveCount(3); // header + two entities
    await expect(table.getByText(`${serviceBlueprint} / ${serviceId}`)).toBeVisible();
    await expect(table.getByText(`Component:default/${serviceId}`)).toBeVisible();
    await expect(table.getByText(`${teamBlueprint} / ${teamId}`)).toBeVisible();
    await expect(table.getByText(`Group:default/${teamId}`)).toBeVisible();
    const yaml = page.getByLabel("Backstage catalog YAML");
    await expect(yaml).toContainText(`name: ${serviceId}`);
    await expect(yaml).toContainText(`owner: group:default/${teamId}`);
    await expect(yaml).toContainText("kind: Group");

    // 4. Create the catalog files: the dialog first predicts, then Confirm stores them.
    await page.getByRole("button", { name: "Create / update catalog files" }).click();
    const dialog = await readyDialog(page, "Create / update catalog files");
    await expect(dialog.getByText("Would be created", { exact: true })).toHaveCount(2);
    const [stored] = await Promise.all([
      waitForApi(page, { method: "POST", path: "/api/v1/files/import" }),
      dialog.getByRole("button", { name: "Confirm" }).click(),
    ]);
    expect(stored.status()).toBe(200);
    await expect(dialog.getByText("Created", { exact: true })).toHaveCount(2);
    const fileLink = dialog.getByRole("link", { name: `Edit ${serviceId}` });
    await expect(fileLink).toBeVisible();
    await dialog.getByRole("button", { name: "Close" }).click();
    await expect(dialog).toHaveCount(0);

    // 5. Convert again: the merge finds the stored files, so the rows link to them and the dry
    // run predicts nothing to change; an edit made while the dialog is open turns one row stale.
    const [again] = await Promise.all([
      page.waitForResponse(
        (r) =>
          new URL(r.url()).pathname === `/api/v1/conversion-mappings/${created.mapping}/catalog-documents` &&
          r.request().method() === "GET",
      ),
      page.getByRole("button", { name: "Convert" }).click(),
    ]);
    expect(again.status()).toBe(200);
    await expect(table.getByRole("link", { name: `Edit Component:default/${serviceId}` })).toBeVisible();
    await page.getByRole("button", { name: "Create / update catalog files" }).click();
    const secondDialog = await readyDialog(page, "Create / update catalog files");
    await expect(secondDialog.getByText("Would stay unchanged", { exact: true })).toHaveCount(2);
    // While the dialog is open the Group file is edited through the API (a headerless PUT is
    // accepted): the previewed revision is then out of date, so Confirm refuses that row.
    const groups = await page.request.get(`/api/v1/files?name=${encodeURIComponent(teamId)}&pageSize=100`, {
      headers: authHeaders,
    });
    const group = ((await groups.json()).items ?? []).find((f: { name: string }) => f.name === teamId) as { id: number };
    const groupFile = await (await page.request.get(`/api/v1/files/${group.id}`, { headers: authHeaders })).json();
    const edited = await page.request.put(`/api/v1/files/${group.id}`, {
      headers: authHeaders,
      data: {
        kind: groupFile.kind,
        metadata: { ...groupFile.metadata, description: "edited meanwhile" },
        spec: groupFile.spec,
      },
    });
    expect(edited.status(), await edited.text()).toBe(204);
    const [confirmed] = await Promise.all([
      waitForApi(page, { method: "POST", path: "/api/v1/files/import" }),
      secondDialog.getByRole("button", { name: "Confirm" }).click(),
    ]);
    expect(confirmed.status()).toBe(200);
    await expect(secondDialog.getByRole("row").filter({ hasText: teamId }).getByText("Changed meanwhile", { exact: true })).toBeVisible();
    await expect(secondDialog.getByRole("row").filter({ hasText: serviceId }).getByText("Unchanged", { exact: true })).toBeVisible();
    const kept = await (await page.request.get(`/api/v1/files/${group.id}`, { headers: authHeaders })).json();
    expect(kept.metadata.description).toBe("edited meanwhile");
    await secondDialog.getByRole("button", { name: "Close" }).click();
    await expect(secondDialog).toHaveCount(0);

    // 6. The created file is a real catalog file: its editor opens from the report link.
    await table.getByRole("link", { name: `Edit Component:default/${serviceId}` }).click();
    await expect(page.getByRole("textbox", { name: "Name" }).first()).toHaveValue(serviceId);
  } finally {
    // Cleanup in dependency order: catalog files, entities (service before its team), mapping,
    // blueprints (service before the team it targets).
    // The Files list filters by `name` (a contains match); the explicit name check below keeps the
    // cleanup from ever deleting a foreign file should that filter be ignored.
    const files = await page.request.get(`/api/v1/files?name=${encodeURIComponent(run)}&pageSize=100`, { headers: authHeaders });
    if (files.ok()) {
      const ours = ((await files.json()).items ?? []) as { id: number; name: string }[];
      for (const file of ours.filter((f) => f.name.startsWith(`${run}-`))) {
        await page.request.delete(`/api/v1/files/${file.id}`, { headers: authHeaders });
      }
    }
    const remove = async (path: string, id: number | undefined) => {
      if (id !== undefined) await page.request.delete(`${path}/${id}`, { headers: authHeaders });
    };
    await remove("/api/v1/entities", created.service);
    await remove("/api/v1/entities", created.team);
    await remove("/api/v1/conversion-mappings", created.mapping);
    await remove("/api/v1/blueprints", created.serviceBp);
    await remove("/api/v1/blueprints", created.teamBp);
  }
});
