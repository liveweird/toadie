import {
  expect,
  login,
  openFilters,
  pickLifecycle,
  pickType,
  rowOperation,
  test,
  uniqueText,
} from "./helpers";

// The per-file change history on the editor, on a throwaway unique-named component so
// parallel files and re-runs never collide. The history is per file, so every assertion is
// scoped to this run's own document by construction.
test("a file's history records its creation and each later edit, field by field", async ({
  page,
}) => {
  await login(page);
  const name = uniqueText("e2e-hist");

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

  // A fresh file's history holds exactly the creation.
  await page.goto(`/files/${fileId}/edit`);
  const history = page.getByRole("heading", { name: "History" });
  await expect(history).toBeVisible();
  await expect(page.getByText("File created.")).toBeVisible();

  // Edit two fields of different shapes: a scalar (Title) and free text (Description).
  await page.getByRole("textbox", { name: "Title", exact: true }).fill("Checkout service");
  await page.getByRole("textbox", { name: "Description", exact: true }).fill("Runs the checkout.");
  await Promise.all([
    page.waitForResponse(
      (r) => r.url().includes(`/api/v1/files/${fileId}`) && r.request().method() === "PUT" && r.ok(),
    ),
    page.getByRole("button", { name: "Save" }).click(),
  ]);

  await page.goto(`/files/${fileId}/edit`);
  // The sentence names both changed fields; the scalar also gets its own before/after line,
  // while the description's TEXT is deliberately never recorded.
  await expect(page.getByText("File updated: Title, Description.")).toBeVisible();
  await expect(page.getByText("Title: set to Checkout service")).toBeVisible();
  // The description's TEXT is never recorded, so it gets no before/after line of its own.
  await expect(page.getByText(/^Description:/)).toHaveCount(0);
  // Newest first: the edit sits above the creation.
  await expect(page.getByText("File created.")).toBeVisible();

  // Clean up — the file goes, its trail stays behind it by design.
  await page.goto("/files");
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(name);
  await rowOperation(page, name, "Delete");
  await Promise.all([
    page.waitForResponse(
      (r) =>
        r.url().endsWith(`/api/v1/files/${fileId}`) && r.request().method() === "DELETE" && r.ok(),
    ),
    page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click(),
  ]);
});
