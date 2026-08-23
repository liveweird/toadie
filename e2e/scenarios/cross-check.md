# Cross-checking references between files

- **Spec**: [tests/cross-check.spec.ts](../tests/cross-check.spec.ts)
- **Actors**: the seed administrator (`admin@toadie.local`) — an ordinary user here (shared
  workspace)
- **Owns** (exclusive server-side state): two throwaway unique-named files — a source
  (`e2e-xchk-src-…`) and its initially-missing dependency target (`e2e-xchk-ghost-…`) — both
  deleted at the end; every assertion is unique-name-anchored, so other files' findings
  (including residue) cannot interfere

## Scenario: a dangling reference is flagged, then resolves once the target file exists

1. The admin signs in and creates a source component whose **Depends on** names a
   `component:` reference that doesn't exist yet.
   - *Expected*: before saving, the editor's live **References** panel already lists the
     reference under "Unresolved references".
2. They save the file and open the **Cross-check** page.
   - *Expected*: the default (Problems) view shows the missing reference, and its row links to
     the source file's editor.
3. They create the missing target component.
4. They reload the Cross-check page.
   - *Expected*: the finding for that unique reference is gone.
5. They delete both throwaway files from the filtered Catalog files list.

## Not covered here (and why)

- **KIND_REQUIRED and UNVERIFIABLE tiers, contextual-namespace resolution, case-insensitive
  matching** — pinned exhaustively by the server suite (`CrossCheckTest`) and the page/panel
  unit tests; e2e sticks to the resolve-a-dangling-reference journey.
- **The filter Select's three views** — unit-tested (`CrossCheck.test.tsx`); the journey uses
  only the default Problems view.
