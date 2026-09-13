# Saved entity queries (Port migration phase 7, v2.1.0)

- **Spec**: [tests/entity-queries.spec.ts](../tests/entity-queries.spec.ts)
- **Actors**: the seed administrator (`admin@toadie.local`) seeds the throwaway registry state,
  runs the whole save/edit/rename/delete journey, and creates one throwaway regular user via the
  API; that throwaway user, signed in through a SECOND browser context, exercises the foreign
  public-query half (a saved query carries no admin gate — cross-user visibility rules are
  pinned server-side, the `LensTest`/`lenses.spec.ts` precedent one level down)
- **Owns** (exclusive server-side state): two throwaway blueprints (`e2e-eqs-*-bp-parent`, no
  relations; `e2e-eqs-*-bp-child`, a single `parent` relation to the first flagged as the
  `composition` entry of its `hierarchyRelations`) created via the API, and their three
  `e2e-eqs-*` entities (p1, c1 with `parent` -> p1, c2 with no `parent`) — the `entity-query.spec.ts`
  shape, under its own run marker (`uniqueText("e2e-eqs")`) so both specs' concurrent creates
  never collide; one throwaway saved entity query named `e2e-eqs-…` (renamed to `e2e-eqs-…-renamed`,
  deleted at the end); and one throwaway regular user (deleted at the end). All removed in `finally`.

## Scenario: a saved entity query applies on both canvases and stays creator-only

1. The admin signs in and seeds two throwaway blueprints via the API: a parent blueprint with no
   relations, and a child blueprint with a single `parent` relation to it flagged as the
   `composition` entry of its `hierarchyRelations`, plus three entities: p1 (parent blueprint),
   c1 (child blueprint, `parent` naming p1), and c2 (child blueprint, no `parent`).
   - *Expected*: every creation succeeds (`201`).
2. On **Entity graph**, narrowed to this run's own rows with the search filter, they type
   `` MATCH (a:`<child-blueprint>`)-[:parent]->(b:`<parent-blueprint>`) RETURN a, b `` into the
   "Entity query" editor and click **Run**.
   - *Expected*: exactly two nodes remain (c1 and p1); an "Applied" badge appears.
3. From **Saved query actions** they choose **Save as new query…**, name it a unique
   `e2e-eqs-…` value, leave visibility at Private, and Save.
   - *Expected*: `POST /api/v1/entity-queries` answers `201`; the "Saved query" combobox shows
     the new name selected, with no "Modified" badge.
4. They append ` LIMIT 1` to the editor text without re-running it.
   - *Expected*: a "Modified" badge appears (the draft has diverged from the selected saved
     query).
5. They open **Entity hierarchy**, re-apply the search filter, and pick the saved query from its
   own "Saved query" combobox.
   - *Expected*: a `GET …/entities/graph` request carrying `query=` answers `200`; the tree shows
     p1 with c1 nested under it, and c2 does not appear (the ` LIMIT 1` draft is discarded — the
     pick restores and re-runs the saved query's own stored text).
6. Still on Entity hierarchy, from **Saved query actions** they choose **Rename / visibility…**,
   rename it to `<name>-renamed`, flip visibility to Public, and Save.
   - *Expected*: `PUT /api/v1/entity-queries/{id}` answers `204`; the combobox shows the new name.
7. In a second, independent browser context, a throwaway regular user (created via the API) signs
   in, opens **Entity graph**, re-applies the search filter, and picks the now-public query from
   the combobox — rendered `<name>-renamed — Admin`, since a foreign public entry names its
   creator.
   - *Expected*: the graph request carries `query=` and narrows identically (c1 and p1 shown, c2
     not); opening **Saved query actions** offers only **Save as new query…** — no **Save
     changes**, **Rename / visibility…**, or **Delete** item is rendered at all for a query this
     user does not own (the `LensPicker` disclosure split: a foreign PUBLIC entry is visible and
     applicable, never mutable, from this menu).
8. Back in the first context, the admin (the query's creator) deletes it through **Saved query
   actions** → **Delete** → the confirm modal.
   - *Expected*: `DELETE /api/v1/entity-queries/{id}` answers `204`; the modal disappears and the
     combobox clears.
9. Cleanup (API): the saved query if somehow still present (tolerating a `404`), the entities
   (children before their target), the blueprints (child before parent), and the throwaway user.

## Not covered here (and why)

- **The full 404/403 existence-disclosure matrix for saved queries** (a foreign PRIVATE or
  unknown id answering `404` on replace/delete, a foreign PUBLIC id answering `403`, per-owner
  name uniqueness `409`, and payload validation) — pinned exhaustively by the server suite
  (the `entity-queries` counterpart of `LensTest`) and the picker's own unit tests; this journey
  sticks to the single most important cross-user behavior a UI regression could actually hide:
  the actions menu never OFFERING a mutation the API would refuse.
- **The query language's grammar and diagnostic catalog** — already covered by
  `entity-query.spec.ts` and the pure engine tests; this journey reuses one already-proven MATCH
  pattern purely as the payload a saved query carries.
