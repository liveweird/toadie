import { expect, login, switchWorld, test } from "./helpers";

// The world switch (v2.3.0): the sidebar shows one product world at a time, derived from the
// route (a deep link flips the sidebar) or remembered otherwise (global pages keep the last
// world, and a fresh browser context lands on Port). Read-only — owns no server-side state.
test("the world switch follows the route, remembers the last world, and scopes the command palette", async ({
  page,
}) => {
  await login(page);

  // A fresh context lands on Port: the Entity hierarchy home, the Port radio checked, Port
  // nav leaves in the DOM, Backstage leaves absent.
  await expect(page.getByRole("heading", { name: "Entity hierarchy", exact: true })).toBeVisible();
  await expect(page.getByRole("radio", { name: "Port" })).toBeChecked();
  await expect(page.getByRole("link", { name: "Blueprints" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Files" })).toHaveCount(0);

  // Checking Backstage navigates to its home and swaps the nav leaves.
  await switchWorld(page, "Backstage");
  await expect(page).toHaveURL(/\/hierarchy$/);
  await expect(page.getByRole("heading", { name: "Hierarchy", exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "Files" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Blueprints" })).toHaveCount(0);

  // A deep link into the other world flips the sidebar's radio without an explicit switch.
  await page.goto("/blueprints");
  await expect(page.getByRole("radio", { name: "Port" })).toBeChecked();

  // A global page (no world of its own) keeps the last-derived world.
  await page.goto("/changelog");
  await expect(page.getByRole("radio", { name: "Port" })).toBeChecked();

  // "/" redirects to the remembered world's home.
  await page.goto("/");
  await expect(page).toHaveURL(/\/entity-hierarchy$/);

  // Switch back to Backstage and confirm "/" now redirects there instead.
  await switchWorld(page, "Backstage");
  await page.goto("/");
  await expect(page).toHaveURL(/\/hierarchy$/);

  // The brand link opens the current world's home without changing it.
  await page.getByRole("link", { name: "Toadie" }).click();
  await expect(page).toHaveURL(/\/hierarchy$/);

  // The command palette is scoped to the current (Backstage) world.
  await page.getByRole("button", { name: "Search and jump to…" }).first().click();
  await expect(page.getByRole("button", { name: "New catalog file" })).toBeVisible();
  await page.keyboard.press("Escape");

  // Switching to Port rescopes the palette's actions and search placeholder.
  await switchWorld(page, "Port");
  await page.getByRole("button", { name: "Search and jump to…" }).first().click();
  await expect(page.getByRole("button", { name: "New entity" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Import ontology" })).toBeVisible();
  await expect(page.getByPlaceholder("Search entities or jump to a page…")).toBeVisible();
  await page.keyboard.press("Escape");
});
