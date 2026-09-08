import {
  createUserViaUi,
  deleteUserRow,
  expect,
  login,
  readyDialog,
  signOut,
  test,
  uniqueText,
} from "./helpers";

// The blueprint registry journey (Port compatibility, phase 1): editor validation -> create a
// blueprint with a string property carrying an enum + colour and a required number property,
// watching the JSON preview -> a second blueprint relating to it -> the blocked delete of a
// targeted blueprint -> a rename that cascades into the dependent's relation target -> the
// regular user's read-only view and the editor-route bounce -> cleanup. The registry is shared
// run-state and THIS SPEC IS ITS ONLY IN-RUN WRITER — it only ever creates and deletes its own
// unique `e2e-bp-*` blueprints (the registry has no seed to protect).
test("admin curates the blueprint registry; a rename cascades; a regular user reads it", async ({
  page,
}) => {
  await login(page);

  // Two INDEPENDENT unique markers (never one derived as a substring of the other) — several
  // locators below match by accessible-name substring, and a derived identifier would make
  // "Edit <first>" ambiguously match "Edit <first>-dep" too.
  const firstIdentifier = uniqueText("e2e-bp");
  const depIdentifier = uniqueText("e2e-bp-dep");

  // 1. Open Blueprints from the nav's Port Ontology section.
  await page.getByRole("link", { name: "Blueprints" }).click();
  await expect(page.getByRole("heading", { name: "Blueprints" })).toBeVisible();
  await expect(page.getByRole("link", { name: "New blueprint" })).toBeVisible();

  // 2. Open the editor and submit it empty: the identifier/title errors render inline and
  // nothing navigates away.
  await page.getByRole("link", { name: "New blueprint" }).click();
  // Wait for an element unique to the editor page before interacting (the lazy-route fill race).
  const identifierInput = page.getByRole("textbox", { name: "Identifier" });
  await expect(identifierInput).toBeVisible();
  await page.getByRole("button", { name: "Create" }).click();
  await expect(
    page.getByText("Must be 1–100 characters of letters, digits, and [@_.:/=-], and not start with $"),
  ).toBeVisible();
  await expect(page.getByText("Required, up to 100 characters")).toBeVisible();
  await expect(page).toHaveURL(/\/blueprints\/new$/);

  // 3. Fill identity, add a string property with a two-value colourized enum, and a required
  // number property.
  await identifierInput.fill(firstIdentifier);
  await page.getByRole("textbox", { name: "Title" }).fill("E2E Blueprint");

  const propertiesGroup = page.getByRole("group", { name: "Properties" });

  await page.getByRole("button", { name: "Add property" }).click();
  const row0 = page.getByTestId("properties-row-0");
  await row0.getByRole("textbox", { name: "Property ID" }).fill("envTier");
  await row0.getByRole("textbox", { name: "Title" }).fill("Environment tier");
  const enumInput = row0.getByRole("combobox", { name: "Allowed values" });
  await enumInput.fill("backend");
  await page.keyboard.press("Enter");
  await enumInput.fill("frontend");
  await page.keyboard.press("Enter");
  const colorSelect = row0.getByRole("combobox", { name: 'Colour for value "backend"' });
  await colorSelect.click();
  await page.getByRole("option", { name: "blue", exact: true }).click();

  await page.getByRole("button", { name: "Add property" }).click();
  const row1 = page.getByTestId("properties-row-1");
  await row1.getByRole("textbox", { name: "Property ID" }).fill("priority");
  await row1.getByRole("combobox", { name: "Type" }).click();
  await page.getByRole("option", { name: "number", exact: true }).click();
  await row1.getByRole("textbox", { name: "Title" }).fill("Priority");
  await row1.getByRole("switch", { name: "Required" }).click();

  // A newly added row opens on its own (and would scroll into view), carrying the Required
  // badge once flagged.
  const row1Toggle = row1.getByRole("button", { name: "Toggle priority" });
  await expect(row1Toggle).toHaveAttribute("aria-expanded", "true");
  // The toggle's own Required OUTLINE badge — the row body also carries the switch labelled
  // "Required" that set the flag, so scope to the header button, not the whole row.
  await expect(row1Toggle.getByText("Required")).toBeVisible();

  // Collapse/expand round-trip: row0's body unmounts while collapsed and remounts with its
  // typed values intact (the form, not the DOM, owns the value).
  const row0Toggle = row0.getByRole("button", { name: "Toggle envTier" });
  await row0Toggle.click();
  await expect(row0.getByRole("textbox", { name: "Property ID" })).toHaveCount(0);
  await row0Toggle.click();
  await expect(row0.getByRole("textbox", { name: "Property ID" })).toHaveValue("envTier");

  // Blocked-save reveal: clear row0's title, collapse it, and try to save — the row re-opens
  // with the error instead of stranding it behind a collapsed header, and nothing is sent.
  await row0.getByRole("textbox", { name: "Title" }).fill("");
  await row0Toggle.click();
  await expect(row0.getByRole("textbox", { name: "Title" })).toHaveCount(0);
  await page.getByRole("button", { name: "Create" }).click();
  await expect(row0Toggle).toHaveAttribute("aria-expanded", "true");
  await expect(row0.getByRole("textbox", { name: "Title" })).toHaveAttribute("aria-invalid", "true");
  await expect(page).toHaveURL(/\/blueprints\/new$/);
  await row0.getByRole("textbox", { name: "Title" }).fill("Environment tier");

  // Jump to a row: the Select focuses the picked row's header.
  const jumpSelect = propertiesGroup.getByRole("combobox", { name: "Jump to a row in Properties" });
  await jumpSelect.click();
  await jumpSelect.fill("priority");
  await page.getByRole("option", { name: "priority — Priority" }).click();
  await expect(row1.getByRole("button", { name: "Toggle priority" })).toBeFocused();

  // The live JSON preview reflects both rows BEFORE saving.
  const preview = page.getByLabel("JSON preview");
  await expect(preview).toContainText('"envTier"');
  await expect(preview).toContainText('"priority"');
  await expect(preview).toContainText('"required": [');

  const [createResp] = await Promise.all([
    page.waitForResponse(
      (r) => r.url().endsWith("/api/v1/blueprints") && r.request().method() === "POST" && r.ok(),
    ),
    page.getByRole("button", { name: "Create" }).click(),
  ]);
  const firstId: number = (await createResp.json()).id;
  await expect(page).toHaveURL(/\/blueprints$/);
  const firstRow = page.getByRole("row").filter({ hasText: firstIdentifier });
  await expect(firstRow).toBeVisible();
  await expect(firstRow.getByRole("cell").nth(2)).toHaveText("2");

  // 4. Create a second blueprint whose relation targets the first.
  await page.getByRole("link", { name: "New blueprint" }).click();
  await expect(identifierInput).toBeVisible();
  await identifierInput.fill(depIdentifier);
  await page.getByRole("textbox", { name: "Title" }).fill("E2E Dependent Blueprint");

  await page.getByRole("button", { name: "Add relation" }).click();
  const relationsGroup = page.getByRole("group", { name: "Relations" });
  await relationsGroup.getByRole("textbox", { name: "Relation ID" }).fill("target");
  await relationsGroup.getByRole("textbox", { name: "Title" }).fill("Target");
  const targetSelect = relationsGroup.getByRole("combobox", { name: "Target blueprint" });
  await targetSelect.click();
  await targetSelect.fill(firstIdentifier);
  await page.getByRole("option", { name: firstIdentifier, exact: true }).click();
  await expect(targetSelect).toHaveValue(firstIdentifier);

  const [depResp] = await Promise.all([
    page.waitForResponse(
      (r) => r.url().endsWith("/api/v1/blueprints") && r.request().method() === "POST" && r.ok(),
    ),
    page.getByRole("button", { name: "Create" }).click(),
  ]);
  const depId: number = (await depResp.json()).id;
  await expect(page).toHaveURL(/\/blueprints$/);
  await expect(firstRow).toBeVisible();
  const depRow = page.getByRole("row").filter({ hasText: depIdentifier });
  await expect(depRow).toBeVisible();
  await expect(depRow.getByRole("cell").nth(3)).toHaveText("1");

  // 5. Deleting the first (targeted) blueprint is refused.
  await page.getByRole("button", { name: `Delete ${firstIdentifier}` }).click();
  await readyDialog(page, "Delete blueprint?");
  const [blockedDelete] = await Promise.all([
    page.waitForResponse(
      (r) => r.url().endsWith(`/api/v1/blueprints/${firstId}`) && r.request().method() === "DELETE",
    ),
    page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click(),
  ]);
  expect(blockedDelete.status()).toBe(409);
  await expect(
    page
      .getByRole("dialog")
      .getByText(
        "This blueprint is still referenced by another blueprint's relation or aggregation property — remove or retarget it first",
      ),
  ).toBeVisible();
  await page.getByRole("dialog").getByRole("button", { name: "Cancel", exact: true }).click();
  await expect(firstRow).toBeVisible();

  // 6. Rename the first blueprint's identifier; the second's relation target follows the
  // cascade server-side.
  await page.getByRole("button", { name: `Edit ${firstIdentifier}` }).click();
  await expect(identifierInput).toHaveValue(firstIdentifier);
  const renamedIdentifier = `${firstIdentifier}-renamed`;
  await identifierInput.fill(renamedIdentifier);
  await Promise.all([
    page.waitForResponse(
      (r) => r.url().endsWith(`/api/v1/blueprints/${firstId}`) && r.request().method() === "PUT" && r.ok(),
    ),
    page.getByRole("button", { name: "Save" }).click(),
  ]);
  await expect(page).toHaveURL(/\/blueprints$/);

  await page.getByRole("button", { name: `Edit ${depIdentifier}` }).click();
  await expect(identifierInput).toHaveValue(depIdentifier);
  // The dependent blueprint's one stored relation is the first (and only) row of its
  // family — it starts expanded on load, same as any other family.
  await expect(relationsGroup.getByRole("button", { name: "Toggle target" })).toHaveAttribute(
    "aria-expanded",
    "true",
  );
  const depTargetSelect = relationsGroup.getByRole("combobox", { name: "Target blueprint" });
  await expect(depTargetSelect).toHaveValue(renamedIdentifier);
  await page.getByRole("link", { name: "Back to blueprints" }).click();

  // 7. A throwaway regular user sees the same list read-only, and the editor route bounces
  // them back.
  const throwaway = await createUserViaUi(page, "E2E Bp Reader");
  await login(page, throwaway.email, throwaway.password);
  await page.getByRole("link", { name: "Blueprints" }).click();
  await expect(page.getByRole("heading", { name: "Blueprints" })).toBeVisible();
  await expect(page.getByText(renamedIdentifier)).toBeVisible();
  await expect(page.getByText(depIdentifier)).toBeVisible();
  await expect(page.getByRole("link", { name: "New blueprint" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: `Edit ${depIdentifier}` })).toHaveCount(0);
  await expect(page.getByRole("button", { name: `Delete ${depIdentifier}` })).toHaveCount(0);

  await page.goto("/blueprints/new");
  await expect(page).toHaveURL(/\/blueprints$/);
  await expect(page.getByRole("heading", { name: "Blueprints" })).toBeVisible();
  await signOut(page);

  // 8. Cleanup: back as the admin, the dependent first, then the (renamed) target, then the
  // throwaway user.
  await login(page);
  await page.goto("/blueprints");
  await page.getByRole("button", { name: `Delete ${depIdentifier}` }).click();
  await readyDialog(page, "Delete blueprint?");
  await Promise.all([
    page.waitForResponse(
      (r) => r.url().endsWith(`/api/v1/blueprints/${depId}`) && r.request().method() === "DELETE" && r.ok(),
    ),
    page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click(),
  ]);
  await expect(page.getByRole("row").filter({ hasText: depIdentifier })).toHaveCount(0);

  await page.getByRole("button", { name: `Delete ${renamedIdentifier}` }).click();
  await readyDialog(page, "Delete blueprint?");
  await Promise.all([
    page.waitForResponse(
      (r) => r.url().endsWith(`/api/v1/blueprints/${firstId}`) && r.request().method() === "DELETE" && r.ok(),
    ),
    page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click(),
  ]);
  await expect(page.getByRole("row").filter({ hasText: renamedIdentifier })).toHaveCount(0);

  await deleteUserRow(page, throwaway.name);
});
