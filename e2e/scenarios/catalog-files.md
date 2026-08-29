# Catalog-file CRUD (the visual creator)

- **Spec**: [tests/catalog-files.spec.ts](../tests/catalog-files.spec.ts)
- **Actors**: the seed administrator (`admin@toadie.local`) — acting as an ordinary user
  (the workspace is shared; no route is admin-gated)
- **Owns** (exclusive server-side state): its one throwaway catalog file, unique-named
  (`e2e-comp-…`) and deleted at the end; every list assertion is name-filter-anchored, so
  parallel runs and leftover residue never interfere

## Scenario: admin creates a component file, edits it, downloads the YAML, and deletes it

1. The admin signs in and opens the **New catalog file** form.
2. They fill the minimal Component — a unique grammar-valid **Name**, **Type** `service`,
   **Lifecycle** `production`, **Owner** `group:default/platform` — plus a **Title**.
   - *Expected*: the live **YAML preview** already shows `kind: Component` and the entity name
     before anything is saved.
3. They click **Create**.
   - *Expected*: the file is stored (a successful create round-trip) and the app lands on the
     **Files** list (`/files`); filtered by the unique name, the row shows the name and title.
4. Still on the filtered list, they add the **Type** filter `service` and pick the stored
   group's full reference in the **Owner** filter.
   - *Expected*: the row stays (its type matches, and its short-form owner RESOLVES to the
     picked entity). Switching the Type filter to `library` hides it ("No catalog files");
     clearing both filters brings it back.
5. They toggle the **Component** pill off in the always-visible Kind row above the table
   (no filter panel needed), then back on.
   - *Expected*: the pills are a visibility switch — every kind starts ON; with Component
     hidden the row disappears ("No catalog files"), and re-enabling it brings it back.
6. They open the row's **Edit** form through its **Operations** menu, change the title and
   set lifecycle `deprecated`, and click **Save**.
7. They reopen the edit form directly.
   - *Expected*: the new title and lifecycle persisted.
8. Back on the filtered list they pick **Download** from the row's **Operations** menu.
   - *Expected*: the browser receives a file named `catalog-info.yaml`.
9. They pick **Delete** from the row's **Operations** menu and confirm in the modal.
10. They load the list fresh and filter by the name again.
   - *Expected*: "No catalog files" — the file is gone.

## Not covered here (and why)

- **Validation rejections (400) and identity conflicts (409)** — exhaustively covered by the
  server suite (`CatalogFileTest`) and the form unit tests; e2e sticks to the happy journey.
- **Pagination/sorting mechanics** — unit- and server-tested; the shared database's row count
  is not this spec's to assume.
- **YAML content beyond the preview smoke** — `catalogYaml.test.ts` pins the full document
  shape; asserting downloaded file contents in e2e would re-test the same pure function.
