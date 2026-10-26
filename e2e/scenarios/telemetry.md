# The ADMIN telemetry panel

- **Spec**: [tests/telemetry.spec.ts](../tests/telemetry.spec.ts)
- **Actors**: the seed administrator (`admin@toadie.local`) and one throwaway regular user
- **Owns** (exclusive server-side state): one throwaway user (the fourth test, created through the
  UI and deleted by the test). Nothing else is written: the panel is read-only, and its open flag,
  expanded sections and range are DEVICE-level localStorage (`login` clears it for every test). Every
  test that reads figures first waits, through the API (bounded, 90 s, a slow poll because the read API
  shares a per-user budget of 30 requests a minute with the panel itself), until the sampler has taken
  its first sample of the most recently started live (non-stale) process and the first workspace
  measurement (the first tick comes seconds after the app starts); the file's timeout is 150 s to fit
  that wait. A stack rebuilt within the hour still lists the previous container's process (live for
  three sample intervals after it stopped, stale after), so the Memory and Pressure locators are
  scoped to the newest instance by name.

## Scenario: an admin opens the telemetry panel from the account menu and reads the Memory section

1. The admin signs in, waits for the first sample, opens the account menu and picks **Telemetry panel**.
   - *Expected*: an aside landmark named "Telemetry" appears beside the page with an h2 "Telemetry".
2. They read the **Memory** section (open by default).
   - *Expected*: the live instance's "Memory of <instance>" table whose Container limit row reads 512 MB
     (the compose app's cgroup) and whose Share of the limit row shows a percentage.
3. They open the account menu again.
   - *Expected*: the Telemetry panel item is marked as on (`aria-current`).

## Scenario: the Workspace and Pressure sections show the database size and the read budget against its limit

1. The admin waits for the first sample and workspace measurement and opens the panel.
2. They open **Workspace**.
   - *Expected*: the "Database size per table group" table has a Database row in MB or GB and a Catalog
     row; the "Rows per table" table has a Catalog files row.
3. They open **Pressure**.
   - *Expected*: the live instance's "Limits of <instance>" table reads the read memory budget "of 64 MB"
     and the entity queries "n of N".

## Scenario: the open telemetry panel survives a reload and closes with its own button

1. The admin opens the panel and expands **Workspace**, then reloads the page.
   - *Expected*: the panel is still open and Workspace is still expanded.
2. They press the panel's **Close** button and reload.
   - *Expected*: no panel before or after the reload.

## Scenario: a regular user has no telemetry item in the account menu

1. The admin creates a throwaway user and signs out; the user signs in and opens the account menu.
   - *Expected*: Change password is there, "Telemetry panel" is not.
2. They store the open flag by hand (`toadie.viewSettings.telemetry.panelOpen` = true) and reload.
   - *Expected*: still no panel.
3. The admin signs back in and deletes the user.

## Scenario: the open telemetry panel has no WCAG A/AA violations

1. The admin waits for the first sample, opens the panel and expands every section.
   - *Expected*: the Memory table, its trend chart group and the workspace size table are visible.
2. An axe WCAG A/AA scan of `<main>` and the panel's `<aside>` runs (the suite's documented
   `color-contrast` waiver applies).
   - *Expected*: no violations.
