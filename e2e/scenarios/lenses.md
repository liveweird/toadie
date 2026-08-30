# Lenses (saved filter sets shared across the filterable views)

- **Spec**: [tests/lenses.spec.ts](../tests/lenses.spec.ts)
- **Actors**: the seed administrator (`admin@toadie.local`) — an ordinary user here (lenses
  carry no admin surface; cross-user visibility rules are pinned server-side by `LensTest`)
- **Owns** (exclusive server-side state): two throwaway unique-named Component files
  (`e2e-lens-a-…`, `e2e-lens-b-…`, deleted at the end) and its own unique-named lenses
  (`e2e-lens-…`, renamed to `e2e-lens-rn-…`, deleted mid-journey) — every assertion anchors
  on the run's unique names, so other users'/runs' lenses can never interfere

## Scenario: a saved lens applies the same filters on Hierarchy, Files, Graph, and Errors

1. The admin signs in and creates two components, A and B.
2. On the **Files** view they open the filter panel, filter by A's unique name, and — from
   the lens actions menu next to the new lens combo — **Save as new lens…** under a unique
   name (visibility stays Private).
   - *Expected*: the picker shows the fresh lens as selected.
3. They change the name filter to B.
   - *Expected*: the **Modified** badge appears — the current filters have diverged from
     the selected lens — and the list now shows B.
4. They open **Hierarchy** and pick the lens from its combo box.
   - *Expected*: the lens applies there too (lenses are shared between the views, the
     selection is per-view): A is visible, B is not.
5. They open **Graph** and pick the lens.
   - *Expected*: the canvas shows A's node and not B's.
6. They open **Errors** and pick the lens.
   - *Expected*: the report loads, and the opened filter panel shows A's name in the Name
     filter — the same nine slots every view declares.
7. From the lens actions menu they **Rename / visibility…** — a new unique name and the
   Public visibility.
   - *Expected*: the picker shows the new name, and the reopened dropdown lists it under
     the **Public** group.
8. They **Delete** the lens through the confirm modal.
   - *Expected*: the picker clears.
9. They delete both throwaway files from the filtered Files list.

## Not covered here (and why)

- **Cross-user visibility (a private lens invisible to others, a foreign public lens
  read-only — 404/403 split, ADMIN getting no special access), per-owner name uniqueness
  (409), payload validation, and the save-changes overwrite** — pinned exhaustively by the
  server suite (`LensTest`) and the `LensPicker` unit tests; e2e sticks to the single-actor
  save-apply-everywhere journey.
