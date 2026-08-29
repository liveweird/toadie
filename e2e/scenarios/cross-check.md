# Cross-checking references between files

- **Spec**: [tests/cross-check.spec.ts](../tests/cross-check.spec.ts)
- **Actors**: the seed administrator (`admin@toadie.local`) — an ordinary user here (shared
  workspace)
- **Owns** (exclusive server-side state): two throwaway unique-named files — a source
  (`e2e-xchk-src-…`) and its dependency target (`e2e-xchk-target-…`, deleted mid-journey and
  recreated) — both deleted at the end; every assertion is unique-name-anchored, so other
  files' findings (including residue) cannot interfere

## Scenario: an unresolved reference asks for confirmation; saving anyway lands it on the cross-check report

1. The admin signs in and creates the target component (the repaired reference will point
   at it later).
2. They fill a source component whose **Depends on** names the source ITSELF, and try to
   create it.
   - *Expected*: the live **Findings** panel flags the self-reference under "Findings —
     saving will ask for confirmation", and the strict save opens the **Save with
     findings?** modal listing it — an entity may never reference itself, saved or not.
     They cancel; nothing is saved.
3. They replace the self entry with a `component:` reference that doesn't exist, and try
   again.
   - *Expected*: the panel lists the reference, the modal opens again — and **Save anyway**
     stores the file through the `allowInvalid` waiver, returning them to the Files list.
4. They open the **Cross-check** page.
   - *Expected*: the report shows the waived dangling reference, and its row links to the
     source file's editor.
5. They follow the Edit link and point the reference at the real target.
   - *Expected*: the panel reads "No findings — the document passes every check." and the
     save goes through strict, with no modal.
6. They delete the target from the filtered Files list.
   - *Expected*: deletion is allowed — dangling references also arise this way.
7. They reload the Cross-check page.
   - *Expected*: the report shows the now-missing target reference.
8. They recreate the target and reload the Cross-check page.
   - *Expected*: the finding for that unique reference is gone.
9. They delete both throwaway files (source first) from the filtered Files list.

## Not covered here (and why)

- **KIND_REQUIRED and WRONG_KIND statuses, the registry finding statuses
  (LABEL/ANNOTATION/TAG/TYPE/LIFECYCLE_NOT_ALLOWED), contextual-namespace resolution,
  case-insensitive matching, the aggregated strict 400 detail, the hard checks the waiver
  never lifts** — pinned exhaustively by the server suite (`CrossCheckTest`,
  `CatalogFileTest`) and the page/panel unit tests; e2e sticks to the
  confirm-waive-repair journey.
- **Import's always-waived storage (`CREATED_WITH_FINDINGS` rows)** — server-pinned
  (`RoundTripTest`) and unit-pinned (`ImportCatalogFiles.test.tsx`); the round-trip spec
  keeps its clean-batch journey.
