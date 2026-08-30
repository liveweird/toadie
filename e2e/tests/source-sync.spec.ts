import { expect, login, openFilters, pickLifecycle, pickType, rowOperation, test, uniqueText } from "./helpers";

// The source-reference journey on one throwaway unique-named component: created source-less
// (the Errors report flags it), the reference set after the fact in the editor (the flag
// clears, the list shows the sync state, the Sync operation appears), and the sync modal
// opened against an unreachable URL (the SSRF guard's uniform 400 renders as the fixed
// public-https message — the suite runs without external network, so the fetch→overwrite
// happy path is deliberately server-/unit-tested instead; see the scenario file).
test("a source reference set after creation clears the report flag and enables the sync modal", async ({
  page,
}) => {
  await login(page);
  const name = uniqueText("e2e-src");

  // A minimal component WITHOUT a source reference.
  await page.goto("/files/new");
  await page.getByRole("textbox", { name: "Name", exact: true }).fill(name);
  await pickType(page, "service");
  await pickLifecycle(page, "production");
  await page.getByRole("combobox", { name: "Owner" }).fill("group:default/platform");
  const [created] = await Promise.all([
    page.waitForResponse(
      (r) => r.url().endsWith("/api/v1/files") && r.request().method() === "POST" && r.ok(),
    ),
    page.getByRole("button", { name: "Create" }).click(),
  ]);
  const fileId: number = (await created.json()).id;

  // The Errors report flags the missing reference. Row-anchored: until the debounced name
  // filter lands, the unfiltered report shows EVERY source-less file's badge.
  await page.goto("/errors");
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(name);
  await expect(
    page.getByRole("row").filter({ hasText: name }).getByText("No source reference", { exact: true }),
  ).toBeVisible();

  // The list shows "No source" and the Operations menu holds no Sync item yet.
  await page.goto("/files");
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(name);
  await expect(
    page.getByRole("row").filter({ hasText: name }).getByText("No source", { exact: true }),
  ).toBeVisible();
  const trigger = page.getByRole("button", { name: `Operations for ${name}` });
  await trigger.click();
  await expect(page.getByRole("menuitem", { name: "Edit" })).toBeVisible();
  await expect(page.getByRole("menuitem", { name: "Sync from repo" })).toBeHidden();
  await page.keyboard.press("Escape");

  // Set the reference AFTER creation: the editor's Source section. The URL passes the
  // static write-time guards (absolute https); its host is only probed at fetch time.
  await rowOperation(page, name, "Edit");
  await page
    .getByRole("textbox", { name: "Source file URL" })
    .fill("https://127.0.0.1/catalog-info.yaml");
  await Promise.all([
    page.waitForResponse(
      (r) => r.url().includes(`/api/v1/files/${fileId}`) && r.request().method() === "PUT" && r.ok(),
    ),
    page.getByRole("button", { name: "Save" }).click(),
  ]);

  // The list now shows the never-synced state; the report flag is gone.
  await expect(page).toHaveURL(/\/files$/);
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(name);
  await expect(
    page.getByRole("row").filter({ hasText: name }).getByText("Never synced", { exact: true }),
  ).toBeVisible();
  await page.goto("/errors");
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(name);
  // Once the debounced filter narrows the report to this file, nothing is left to list.
  await expect(page.getByText("No errors — every file passes the checks.")).toBeVisible();

  // The sync modal opens from the Operations menu; fetching the loopback URL is refused by
  // the SSRF guard, so the modal shows the fixed public-https error and keeps the
  // overwrite disabled — the flow stops safely at the guard.
  await page.goto("/files");
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(name);
  await rowOperation(page, name, "Sync from repo");
  const modal = page.getByRole("dialog");
  await expect(modal.getByText(`Sync from repo — ${name}`)).toBeVisible();
  await expect(modal.getByText(/The URL must be a public https address/)).toBeVisible();
  await expect(modal.getByRole("button", { name: "Overwrite DB copy" })).toBeDisabled();
  await modal.getByRole("button", { name: "Cancel" }).click();
  await expect(page.getByRole("dialog")).toBeHidden();

  // Clean up through the UI, verified against a fresh load.
  await rowOperation(page, name, "Delete");
  await Promise.all([
    page.waitForResponse(
      (r) =>
        r.url().endsWith(`/api/v1/files/${fileId}`) && r.request().method() === "DELETE" && r.ok(),
    ),
    page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click(),
  ]);
  await page.goto("/files");
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(name);
  await expect(page.getByText("No catalog files")).toBeVisible();
});
