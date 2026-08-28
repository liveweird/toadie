# Cross-checking references between files

- **Spec**: [tests/cross-check.spec.ts](../tests/cross-check.spec.ts)
- **Actors**: the seed administrator (`admin@toadie.local`) — an ordinary user here (shared
  workspace)
- **Owns** (exclusive server-side state): two throwaway unique-named files — a source
  (`e2e-xchk-src-…`) and its dependency target (`e2e-xchk-target-…`, deleted mid-journey and
  recreated) — both deleted at the end; every assertion is unique-name-anchored, so other
  files' findings (including residue) cannot interfere

## Scenario: an unresolved reference blocks saving; deleting a target creates the finding

1. The admin signs in and creates the target component (references must resolve at save
   time, so targets come first).
2. They fill a source component whose **Depends on** names a `component:` reference that
   doesn't exist, and try to create it.
   - *Expected*: the editor's live **References** panel lists the reference under "References
     that will block saving", and the submit is blocked inline ("does not resolve to a
     stored entity") — no save request is sent.
3. They replace the dangling entry with a reference to the real target.
   - *Expected*: the panel reads "All references resolve." and the create succeeds.
4. They delete the target from the filtered Catalog files list.
   - *Expected*: deletion is allowed — dangling references arise exactly this way.
5. They open the **Cross-check** page.
   - *Expected*: the report shows the now-missing reference, and its row links to the source
     file's editor.
6. They recreate the target and reload the Cross-check page.
   - *Expected*: the finding for that unique reference is gone.
7. They delete both throwaway files (source first) from the filtered Catalog files list.

## Not covered here (and why)

- **KIND_REQUIRED and WRONG_KIND statuses, contextual-namespace resolution, case-insensitive
  matching, the aggregated 400 detail** — pinned exhaustively by the server suite
  (`CrossCheckTest`, `CatalogFileTest`) and the page/panel unit tests; e2e sticks to the
  block-then-repair journey.
- **The server-side 400 on a direct API save** — the client validation blocks first in the
  editor; the raw-API path is server-pinned (`CatalogFileTest`).
