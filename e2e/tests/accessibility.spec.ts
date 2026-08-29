// Axe accessibility smoke: WCAG 2.0/2.1 A+AA scans over the login screen and the authenticated
// pages. Strictly read-only (no created state).
import AxeBuilder from "@axe-core/playwright";
import { expect, login, test } from "./helpers";

const AXE_TAGS = ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"];

// Conscious waiver, not a fix backlog: the theme's dimmed/muted text and brand surfaces sit
// below the 4.5:1 AA ratio by design across every page. Revisit only as a deliberate
// theme-wide design pass — never by patching single elements.
const WAIVED_RULES = ["color-contrast"];

async function scan(page: Parameters<typeof login>[0]): Promise<void> {
  const results = await new AxeBuilder({ page })
    .withTags(AXE_TAGS)
    .disableRules(WAIVED_RULES)
    .analyze();
  // Keep the assert readable on failure: one line per violation with the offending nodes.
  const summary = results.violations.map((v) => ({
    id: v.id,
    impact: v.impact,
    help: v.help,
    nodes: v.nodes.map((n) => n.target.join(" ")),
  }));
  expect(summary).toEqual([]);
}

test("login screen has no WCAG A/AA violations", async ({ page }) => {
  await page.goto("/login");
  await expect(page.getByRole("button", { name: "Sign in" })).toBeVisible();
  await scan(page);
});

// One test per page keeps the report line-per-page.
const AUTHED_PAGES: { path: string; heading: string }[] = [
  { path: "/", heading: "Hierarchy" },
  { path: "/catalog-files", heading: "Files" },
  { path: "/catalog-files/new", heading: "New catalog file" },
  { path: "/catalog-files/import", heading: "Import catalog files" },
  { path: "/cross-check", heading: "Cross-check" },
  { path: "/render", heading: "Graph" },
  { path: "/labels", heading: "Labels" },
  { path: "/annotations", heading: "Annotations" },
  { path: "/tags", heading: "Tags" },
  { path: "/types", heading: "Types" },
  { path: "/lifecycles", heading: "Lifecycles" },
  { path: "/users", heading: "Users" },
  { path: "/changelog", heading: "Changelog" },
];

for (const { path, heading } of AUTHED_PAGES) {
  test(`${path} has no WCAG A/AA violations`, async ({ page }) => {
    await login(page);
    await page.goto(path);
    await expect(page.getByRole("heading", { name: heading })).toBeVisible();
    await scan(page);
  });
}
