// The ADMIN telemetry panel (web/src/components/TelemetryPanel.tsx): opened from the account menu, it shows Toadie's own memory (a
// share of the container limit - the compose app runs in a 512 MB cgroup), the workspace's database size, and the pressure figures
// against their limits (the read memory budget, "n of 64 MB"); it remembers that it is open across a reload, and never appears for
// a regular user. The sampler's first tick comes seconds after the app starts, so every test that reads figures first waits (through
// the API, bounded) until a sample and a workspace measurement exist. Owns: one throwaway user in the fourth test (deleted by the
// test). The panel's open flag, expanded sections and range are device-level localStorage; `login` clears it for every test.
import type { Page } from "@playwright/test";
import { accountMenu, createUserViaUi, deleteUserRow, expect, expectNoAccessibilityViolations, login, signOut, test } from "./helpers";

/**
 * Waits until the most recently started live (non-stale) process has a sample and the workspace has been measured (the panel would
 * otherwise say "no sample yet" for up to a minute), and returns that process's instance name. A stack rebuilt within the last hour
 * still lists the previous container's process — live for three sample intervals after it stopped, stale after — so the panel can
 * hold two Memory tables: the locators are scoped to the newest one by name. The poll is slow on purpose: the read API shares a
 * per-user budget of 30 requests a minute with the panel itself.
 */
async function awaitFirstSamples(page: Page): Promise<string> {
  const token = await page.evaluate(() => localStorage.getItem("toadie.auth.token"));
  expect(token !== null, "the admin login must provide a token for the readiness poll").toBe(true);
  let instance = "";
  await expect
    .poll(
      async () => {
        const res = await page.request.get("/api/v1/telemetry/current", { headers: { Authorization: `Bearer ${token}` } });
        if (!res.ok()) return `status ${res.status()}`;
        const body = (await res.json()) as {
          processes: { instance: string; startedAt: number; stale: boolean; sampledAt: number | null }[];
          workspace: unknown;
        };
        const newest = body.processes.filter((p) => !p.stale).sort((a, b) => b.startedAt - a.startedAt)[0];
        const ready = newest !== undefined && newest.sampledAt !== null && body.workspace !== null;
        if (ready) instance = newest.instance;
        return ready ? "ready" : "waiting";
      },
      { message: "the sampler's first sample of the newest live process and a workspace measurement", timeout: 90_000, intervals: [2_000, 5_000] },
    )
    .toBe("ready");
  return instance;
}

// The readiness poll may wait up to 90 s on a stack that has only just started, beyond the suite's 60 s default.
test.describe.configure({ timeout: 150_000 });

async function openPanelFromMenu(page: Page) {
  await accountMenu(page).click();
  await page.getByRole("menuitem", { name: "Telemetry panel" }).click();
  const panel = page.getByRole("complementary", { name: "Telemetry" });
  await expect(panel).toBeVisible();
  return panel;
}

test("an admin opens the telemetry panel from the account menu and reads the Memory section", async ({ page }) => {
  await login(page);
  const instance = await awaitFirstSamples(page);
  const panel = await openPanelFromMenu(page);
  await expect(panel.getByRole("heading", { level: 2, name: "Telemetry" })).toBeVisible();
  // Memory is open by default; the compose stack gives the app a cgroup with a 512 MB limit.
  await expect(panel.getByRole("button", { name: "Memory" })).toHaveAttribute("aria-expanded", "true");
  const table = panel.getByRole("table", { name: `Memory of ${instance}`, exact: true });
  await expect(table).toBeVisible();
  await expect(table.getByRole("row", { name: /Container limit/ })).toContainText("512 MB");
  await expect(table.getByRole("row", { name: /Share of the limit/ })).toContainText(/\d%/);
  // The account menu item shows the toggle is on.
  await accountMenu(page).click();
  await expect(page.getByRole("menuitem", { name: "Telemetry panel" })).toHaveAttribute("aria-current", "true");
});

test("the Workspace and Pressure sections show the database size and the read budget against its limit", async ({ page }) => {
  await login(page);
  const instance = await awaitFirstSamples(page);
  const panel = await openPanelFromMenu(page);

  await panel.getByRole("button", { name: "Workspace" }).click();
  const sizes = panel.getByRole("table", { name: "Database size per table group" });
  await expect(sizes.getByRole("row", { name: /^Database/ })).toContainText(/\d (MB|GB)/);
  await expect(sizes.getByRole("row", { name: /^Catalog/ })).toBeVisible();
  await expect(panel.getByRole("table", { name: "Rows per table" }).getByRole("row", { name: /^Catalog files/ })).toBeVisible();

  await panel.getByRole("button", { name: "Pressure" }).click();
  const limits = panel.getByRole("table", { name: `Limits of ${instance}`, exact: true });
  await expect(limits.getByRole("row", { name: /^Read memory budget in use/ })).toContainText("of 64 MB");
  await expect(limits.getByRole("row", { name: /^Entity queries running/ })).toContainText(/\d of \d/);
});

test("the open telemetry panel survives a reload and closes with its own button", async ({ page }) => {
  await login(page);
  const panel = await openPanelFromMenu(page);
  await panel.getByRole("button", { name: "Workspace" }).click();
  await page.reload();
  const reopened = page.getByRole("complementary", { name: "Telemetry" });
  await expect(reopened).toBeVisible();
  // The expanded sections are remembered too.
  await expect(reopened.getByRole("button", { name: "Workspace" })).toHaveAttribute("aria-expanded", "true");

  await reopened.getByRole("button", { name: "Close", exact: true }).click();
  await expect(page.getByRole("complementary", { name: "Telemetry" })).toHaveCount(0);
  await page.reload();
  await expect(accountMenu(page)).toBeVisible();
  await expect(page.getByRole("complementary", { name: "Telemetry" })).toHaveCount(0);
});

test("a regular user has no telemetry item in the account menu", async ({ page }) => {
  await login(page);
  const user = await createUserViaUi(page, "E2E Telemetry Reader");
  await signOut(page);

  await login(page, user.email, user.password);
  await accountMenu(page).click();
  await expect(page.getByRole("menuitem", { name: "Change password" })).toBeVisible();
  await expect(page.getByRole("menuitem", { name: "Telemetry panel" })).toHaveCount(0);
  await page.keyboard.press("Escape");
  // Even a stored "open" flag shows a regular user nothing.
  await page.evaluate(() => localStorage.setItem("toadie.viewSettings.telemetry.panelOpen", "true"));
  await page.reload();
  await expect(accountMenu(page)).toBeVisible();
  await expect(page.getByRole("complementary", { name: "Telemetry" })).toHaveCount(0);
  await signOut(page);

  await login(page);
  await deleteUserRow(page, user.name);
});

test("the open telemetry panel has no WCAG A/AA violations", async ({ page }) => {
  await login(page);
  const instance = await awaitFirstSamples(page);
  const panel = await openPanelFromMenu(page);
  for (const section of ["Workspace", "Pressure", "Requests", "JVM"]) {
    await panel.getByRole("button", { name: section }).click();
  }
  await expect(panel.getByRole("table", { name: `Memory of ${instance}`, exact: true })).toBeVisible();
  await expect(panel.getByRole("group", { name: "Trend: Memory" })).toBeVisible();
  await expect(panel.getByRole("table", { name: "Database size per table group" })).toBeVisible();
  // The panel is the shell's aside, outside <main>: the scan includes both.
  await expectNoAccessibilityViolations(page, { include: ["main", "aside"] });
});
