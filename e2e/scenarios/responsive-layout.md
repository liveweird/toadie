# Responsive lists and Port canvases

- **Spec**: [tests/responsive-layout.spec.ts](../tests/responsive-layout.spec.ts)
- **Actors**: seeded administrator, with unchanged account preferences
- **Owns** (exclusive server-side state): one uniquely named `e2e-layout-*` blueprint and
  six entities forming a parent chain. The existing composition hierarchy is read-only.
  Delete children before parents and then the blueprint in `finally`. All other screens
  are read-only; query/filter state belongs only to this browser context.

## Scenario: data lists fill the desktop canvas and contain scrolling on narrow screens

1. Sign in and visit Blueprints, Labels, Tags, Types, and Annotations at 1920px width.
   - Expected: each loaded table fills the content canvas after sidebar and padding.
2. Resize each screen to 390px and scroll its first edit action into view.
   - Expected: the action remains reachable and the document does not scroll horizontally.
3. Visit Users, Feature flags, both Errors reports, and Changelog at 390px.
   - Expected: loaded content remains inside the viewport, including older changelog text.

## Scenario: populated entity lists, deep hierarchy rows and query controls remain usable on mobile

1. Create an owned blueprint with three stored preview properties, a computed parent identifier, a long property
   heading, a single parent relation, and
   six nested entities with long identifiers and titles.
2. At 390px, open that blueprint's Entities list.
   - Expected: all ten headers and populated cells fit within their columns; horizontal
     table scrolling reveals the edit action without making the document overflow.
3. Filter Entity hierarchy to the owned records and select composition.
   - Expected: the deepest entity's action menu can be reached and opened without document
     overflow.
4. Open Query on Entity graph and Entity hierarchy and enter a valid draft.
   - Expected: the editor is wider than 280px, enabled Run sits beneath it, and the page
     remains within the viewport. No query is saved and no graph layout is changed.

## Not covered here (and why)

Import-result variants and every form/modal are not exhaustively exercised by this journey;
their functional scenarios remain separate. The layout assertions target regressions observed
in real-browser auditing and supplement the existing accessibility and query-builder journeys.

## Desktop reference captures

Cropped to the two protected system-blueprint rows at a 1920px viewport:
[before](assets/responsive-layout-before.png) and
[after](assets/responsive-layout-after.png).
