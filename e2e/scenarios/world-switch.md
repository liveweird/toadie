# The product world switch

- **Spec**: [tests/world-switch.spec.ts](../tests/world-switch.spec.ts)
- **Actors**: the seed administrator (`admin@toadie.local`)
- **Owns** (exclusive server-side state): nothing — read-only

## Scenario: the world switch follows the route, remembers the last world, and scopes the command palette

1. The admin signs in on a fresh browser context.
   - *Expected*: a fresh context has never remembered a world, so it lands on the Port world's
     **Entity hierarchy** home; the sidebar's "Product world" radiogroup shows **Port** checked,
     a Port nav leaf (**Blueprints**) is in the DOM, and a Backstage-only leaf (**Files**) is not.
2. They check **Backstage** in the sidebar's world switch.
   - *Expected*: the app navigates to `/hierarchy` (the catalog **Hierarchy** heading), and the
     nav leaves swap: **Files** appears, **Blueprints** is gone.
3. They open a deep link into the other world (`/blueprints`) directly.
   - *Expected*: the world is DERIVED from the route — the radio flips back to **Port** with no
     explicit switch.
4. They navigate to a global page that belongs to no world (`/changelog`).
   - *Expected*: the radio keeps showing the last-derived world (**Port**) rather than resetting.
5. They navigate to `/`.
   - *Expected*: it redirects to the remembered world's home — `/entity-hierarchy`.
6. They switch to **Backstage** again and navigate to `/`.
   - *Expected*: `/` now redirects to `/hierarchy` instead — the remembered world moved with the
     switch.
7. They click the **Toadie** brand link in the header.
   - *Expected*: it opens the current world's home; the URL stays on `/hierarchy` (no world
     change from following the brand link).
8. They open the command palette from the header trigger while in the Backstage world.
   - *Expected*: the actions list offers **New catalog file** (a Backstage-only action).
9. They close the palette, switch to **Port**, and reopen it.
   - *Expected*: the actions list now offers **New entity** and **Import ontology** instead, and
     the search box's placeholder reads "Search entities or jump to a page…" — the palette is
     scoped to the current world.

## Not covered here (and why)

- **The sidebar's per-world section/leaf CONTENTS** (which leaves live under Catalog vs.
  Ontology vs. Dictionaries) — pinned by `App.test.tsx` and the individual leaf journeys
  (`blueprints.spec.ts`, `labels.spec.ts`, …), which each open their own leaf after switching
  world where needed.
- **The command palette's page-jump and content-search behavior** — covered by
  `command-palette.spec.ts`; this journey only asserts that the offered actions and placeholder
  change with the world.
