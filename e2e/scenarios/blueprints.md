# Blueprint registry

- **Spec**: [tests/blueprints.spec.ts](../tests/blueprints.spec.ts)
- **Actors**: the seed administrator (`admin@toadie.local`); one throwaway regular user created
  through the UI (email carries the `e2e` marker)
- **Owns** (exclusive server-side state): its two throwaway blueprints (`e2e-bp-*`, unique per
  attempt — one target, one dependent carrying a relation to it) and its throwaway user — all
  removed at the end. The blueprint registry is SHARED state and **this spec is its only in-run
  writer**: it never edits or deletes blueprints it did not create.

## Scenario: admin curates the blueprint registry; a rename cascades; a regular user reads it

1. The admin signs in and opens **Blueprints** from the nav's Port Ontology section.
   - *Expected*: the registry page renders with the **New blueprint** action (ADMIN-only).
2. They open the editor and submit it empty.
   - *Expected*: the identifier and title field errors render inline; no request reaches the
     server.
3. They fill the unique `e2e-bp-…` identifier and a title, add a string property with two enum
   values (one coloured), and a number property flagged required.
   - *Expected*: the newly added second row opens on its own, carrying the Required badge.
     Collapsing and re-expanding the first row remounts its fields with the typed values
     intact. Clearing the first row's title, collapsing it, and trying to save re-opens that
     row with the error instead of navigating away, and nothing is sent; refilling the title
     and picking the second row from the "Jump to…" Select focuses its header. They save.
   - *Expected*: the JSON preview beside the form shows the property under `"properties"` and
     the number property under `"required"` before saving; the POST succeeds; the list shows
     the row with its property count.
4. They create a second blueprint (`e2e-bp-…-dep`) whose relation targets the first (picked
   from the target Select) and save.
   - *Expected*: the POST succeeds; the list shows both rows.
5. They try to delete the first blueprint from the list.
   - *Expected*: the confirm modal's request is refused with the "referenced by other
     blueprints" message; the row stays.
6. They edit the first blueprint, change its identifier, and save; then open the second.
   - *Expected*: the PUT succeeds; the second blueprint's one stored relation — its family's
     first and only row — starts expanded; its target shows the NEW identifier (the rename
     cascaded server-side).
7. A throwaway regular user (created via the one-time reveal flow) signs in, opens
   **Blueprints**, and then navigates to `/blueprints/new` directly.
   - *Expected*: the same list read-only — both blueprints visible, no **New blueprint**, edit,
     or delete affordances; the editor route bounces them back to the list.
8. Cleanup: back as the admin, the dependent blueprint is deleted first, then the target (both
   through their confirm modals), and the throwaway user through the Users list.

## Not covered here (and why)

- **The validation rule table** (property type/format/enum/default agreement, the
  `required && many` rule, mirror/aggregation/ownership grammars, the 200 cap, the unknown-key
  400, the case-insensitive identifier 409) — pinned by `BlueprintValidationTest` and
  `BlueprintTest`; the journey proves the editor blocks the empty submit client-side.
- **The 403 on a non-admin mutation** — the read-only branch never renders a mutation path;
  the guard itself is server-pinned (`BlueprintTest`).
- **The concurrency proof** (a relation create racing its target's delete) — server-pinned by
  `BlueprintConcurrencyTest`; no browser journey can hold a database lock.
- **The advanced property families' editors** (mirror, calculation, aggregation, ownership)
  and every property type's widget — pinned by the co-located unit suites
  (`BlueprintPropertyRow.test.tsx`, `BlueprintFormFields.test.tsx`, `blueprintForm.test.ts`).
- **The foldable row list's own contract** (mount/unmount, badges, Expand/Collapse all, the
  jump Select's focus behaviour, the collapsed error dot, the first-row/reveal/revealErrors
  rules) — pinned by `EditorRowList.test.tsx`, `useBlueprintRowExpansion.test.tsx`, and
  `blueprintRowSummary.test.ts`; the journey proves the same behaviour once, end to end.
