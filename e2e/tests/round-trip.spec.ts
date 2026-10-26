import { readFileSync } from "node:fs";
import { expect, login, openFilters, pickNamespace, rowOperation, runNamespace, test, uniqueText } from "./helpers";

// The YAML round-trip end to end: paste-import two documents in this run's throwaway
// namespace (registered by global-setup — imports of undefined namespaces report INVALID),
// export the namespace back as one multi-document file, and prove identity survived the
// trip — re-importing the export conflicts on every row (report & skip). The export-side
// counts are regexes: a retried attempt shares the run namespace with its own residue.
test("two pasted documents import, export as one YAML, and re-import as conflicts", async ({
  page,
}) => {
  await login(page);
  const ns = runNamespace("roundTrip");
  const comp = uniqueText("e2e-rt-comp");
  const team = uniqueText("e2e-rt-team");
  const yaml = [
    "apiVersion: backstage.io/v1alpha1",
    "kind: Component",
    "metadata:",
    `  name: ${comp}`,
    `  namespace: ${ns}`,
    "spec:",
    "  type: service",
    "  lifecycle: production",
    `  owner: ${team}`,
    "---",
    "kind: Group",
    "metadata:",
    `  name: ${team}`,
    `  namespace: ${ns}`,
    "spec:",
    "  type: team",
    "  children: []",
    "",
  ].join("\n");

  // The dry-run first: the same per-row report, predicted — nothing is stored yet.
  await page.goto("/files/import");
  await page.getByRole("textbox", { name: "YAML content" }).fill(yaml);
  await expect(page.getByText("2 documents ready to import")).toBeVisible();
  await Promise.all([
    page.waitForResponse((r) => r.url().endsWith("/api/v1/files/import/check") && r.ok()),
    page.getByRole("button", { name: "Check", exact: true }).click(),
  ]);
  await expect(page.getByText("2 of 2 documents would import.")).toBeVisible();
  await expect(page.getByText("Would be created").first()).toBeVisible();

  // The real import of the same batch — both rows report Created.
  await Promise.all([
    page.waitForResponse(
      (r) => r.url().endsWith("/api/v1/files/import") && r.ok(),
    ),
    page.getByRole("button", { name: "Import", exact: true }).click(),
  ]);
  await expect(page.getByText("Imported 2 of 2 documents.")).toBeVisible();
  const compResult = page.getByRole("row").filter({ hasText: comp });
  const teamResult = page.getByRole("row").filter({ hasText: team }).filter({ hasNotText: comp });
  await expect(compResult.getByText("Created")).toBeVisible();
  await expect(teamResult.getByText("Created")).toBeVisible();

  // Both files appear on the list under the namespace filter.
  await page.goto("/files");
  await openFilters(page);
  await pickNamespace(page, ns);
  await expect(page.getByRole("row").filter({ hasText: comp })).toBeVisible();
  await expect(
    page.getByRole("row").filter({ hasText: team }).filter({ hasNotText: comp }),
  ).toBeVisible();

  // Export each file from its Operations menu, then stitch the two documents back into one
  // multi-document stream — whole-workspace export is an API-only capability now, so the
  // round trip is exercised per file (which is what the UI actually offers).
  const documents: string[] = [];
  for (const name of [comp, team]) {
    const [download] = await Promise.all([
      page.waitForEvent("download"),
      rowOperation(page, name, "Export as YAML"),
    ]);
    expect(download.suggestedFilename()).toBe("catalog-info.yaml");
    documents.push(readFileSync((await download.path())!, "utf8"));
  }
  const exported = documents.join("---\n");
  expect(exported).toContain(`name: ${comp}`);
  expect(exported).toContain(`name: ${team}`);
  expect(exported).toContain("\n---\n");

  // Re-import the export verbatim: every identity already exists — report & skip.
  // Counts by regex, not literals: a CI retry's export can carry the failed attempt's rows.
  await page.goto("/files/import");
  await page.getByRole("textbox", { name: "YAML content" }).fill(exported);
  await expect(page.getByText(/\d+ documents ready to import/)).toBeVisible();
  await Promise.all([
    page.waitForResponse(
      (r) => r.url().endsWith("/api/v1/files/import") && r.ok(),
    ),
    page.getByRole("button", { name: "Import", exact: true }).click(),
  ]);
  await expect(page.getByText(/Imported 0 of \d+ documents\./)).toBeVisible();
  await expect(
    page.getByRole("row").filter({ hasText: comp }).getByText("Already exists", { exact: true }),
  ).toBeVisible();
  await expect(
    page
      .getByRole("row")
      .filter({ hasText: team })
      .filter({ hasNotText: comp })
      .getByText("Already exists", { exact: true }),
  ).toBeVisible();

  // Cleanup: delete both throwaway files.
  for (const name of [comp, team]) {
    await page.goto("/files");
    await openFilters(page);
    await page.getByLabel("Name", { exact: true }).fill(name);
    await rowOperation(page, name, "Delete");
    await Promise.all([
      page.waitForResponse((r) => r.request().method() === "DELETE" && r.ok()),
      page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click(),
    ]);
  }
});

// Replace existing files (the catalog import's `replaceExisting`): one throwaway Component is
// imported, re-imported changed WITHOUT the switch (still "Already exists"), then with the
// switch on — the dry-run predicts "Would be updated", the import reports Updated, an identical
// third pass reports Unchanged, and the file's own History shows the import-origin creation
// plus the field-level update (so it was replaced in place, never deleted and re-created).
test("a changed catalog-info.yaml re-imports as Updated with Replace existing files, and the history records it", async ({
  page,
}) => {
  await login(page);
  const ns = runNamespace("roundTrip");
  const comp = uniqueText("e2e-rp-comp");
  const document = (title: string) =>
    [
      "kind: Component",
      "metadata:",
      `  name: ${comp}`,
      `  namespace: ${ns}`,
      `  title: ${title}`,
      "spec:",
      "  type: service",
      "  lifecycle: production",
      "  owner: group:default/platform",
      "",
    ].join("\n");
  const importButton = page.getByRole("button", { name: "Import", exact: true });
  const checkButton = page.getByRole("button", { name: "Check", exact: true });
  const row = page.getByRole("row").filter({ hasText: comp });
  const runImport = () =>
    Promise.all([
      page.waitForResponse((r) => r.url().endsWith("/api/v1/files/import") && r.ok()),
      importButton.click(),
    ]);

  await page.goto("/files/import");
  const yaml = page.getByRole("textbox", { name: "YAML content" });
  await yaml.fill(document("First title"));
  await expect(page.getByText("1 document ready to import")).toBeVisible();
  await runImport();
  await expect(row.getByText("Created", { exact: true })).toBeVisible();

  // The changed document with the switch OFF: the identity clash stands, nothing is overwritten.
  await yaml.fill(document("Second title"));
  await expect(page.getByText("1 document ready to import")).toBeVisible();
  await runImport();
  await expect(row.getByText("Already exists", { exact: true })).toBeVisible();

  // Switch ON: the dry-run predicts the replace, the import performs it.
  await page.getByRole("switch", { name: /Replace existing files/ }).check();
  await Promise.all([
    page.waitForResponse((r) => r.url().endsWith("/api/v1/files/import/check") && r.ok()),
    checkButton.click(),
  ]);
  await expect(row.getByText("Would be updated", { exact: true })).toBeVisible();
  await expect(page.getByText("1 of 1 documents would import.")).toBeVisible();
  await runImport();
  await expect(row.getByText("Updated", { exact: true })).toBeVisible();
  await expect(page.getByText("Imported 1 of 1 documents.")).toBeVisible();

  // The identical document again: the ordinary replace's no-op — nothing recorded.
  await runImport();
  await expect(row.getByText("Unchanged", { exact: true })).toBeVisible();
  await expect(page.getByText("Imported 0 of 1 documents; 1 already matched.")).toBeVisible();

  // The result row links to the file; its History holds the import creation and the one update.
  await row.getByRole("link", { name: `Edit ${comp}` }).click();
  await expect(page.getByRole("heading", { name: "History" })).toBeVisible();
  await expect(page.getByText("File created by import.")).toBeVisible();
  await expect(page.getByText("File updated: Title.")).toBeVisible();
  await expect(page.getByText("Title: First title → Second title")).toBeVisible();
  await expect(page.getByText("File updated: Title.")).toHaveCount(1);

  // Cleanup: delete the throwaway file.
  await page.goto("/files");
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(comp);
  await rowOperation(page, comp, "Delete");
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "DELETE" && r.ok()),
    page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click(),
  ]);
});
