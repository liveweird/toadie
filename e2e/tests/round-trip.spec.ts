import { readFileSync } from "node:fs";
import { expect, login, openFilters, pickNamespace, runNamespace, test, uniqueText } from "./helpers";

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

  // Import the pasted documents — both rows report Created.
  await page.goto("/catalog-files/import");
  await page.getByRole("textbox", { name: "YAML content" }).fill(yaml);
  await expect(page.getByText("2 documents ready to import")).toBeVisible();
  await Promise.all([
    page.waitForResponse(
      (r) => r.url().endsWith("/api/v1/catalog-files/import") && r.ok(),
    ),
    page.getByRole("button", { name: "Import", exact: true }).click(),
  ]);
  await expect(page.getByText("Imported 2 of 2 documents.")).toBeVisible();
  const compResult = page.getByRole("row").filter({ hasText: comp });
  const teamResult = page.getByRole("row").filter({ hasText: team }).filter({ hasNotText: comp });
  await expect(compResult.getByText("Created")).toBeVisible();
  await expect(teamResult.getByText("Created")).toBeVisible();

  // Both files appear on the list under the namespace filter.
  await page.goto("/catalog-files");
  await openFilters(page);
  await pickNamespace(page, ns);
  await expect(page.getByRole("row").filter({ hasText: comp })).toBeVisible();
  await expect(
    page.getByRole("row").filter({ hasText: team }).filter({ hasNotText: comp }),
  ).toBeVisible();

  // Export the filtered namespace and read the downloaded multi-document file.
  const [download] = await Promise.all([
    page.waitForEvent("download"),
    page.getByRole("button", { name: "Export YAML" }).click(),
  ]);
  expect(download.suggestedFilename()).toBe("catalog-info.yaml");
  const exported = readFileSync((await download.path())!, "utf8");
  expect(exported).toContain(`name: ${comp}`);
  expect(exported).toContain(`name: ${team}`);
  expect(exported).toContain("\n---\n");

  // Re-import the export verbatim: every identity already exists — report & skip.
  // Counts by regex, not literals: a CI retry's export can carry the failed attempt's rows.
  await page.goto("/catalog-files/import");
  await page.getByRole("textbox", { name: "YAML content" }).fill(exported);
  await expect(page.getByText(/\d+ documents ready to import/)).toBeVisible();
  await Promise.all([
    page.waitForResponse(
      (r) => r.url().endsWith("/api/v1/catalog-files/import") && r.ok(),
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
