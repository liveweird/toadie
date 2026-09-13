# Entity query bar (Port migration phase 7, entity query language)

- **Spec**: [tests/entity-query.spec.ts](../tests/entity-query.spec.ts)
- **Actors**: the seed administrator (`admin@toadie.local`) seeds the throwaway registry state
  and drives the whole journey (entities carry no admin gate)
- **Owns** (exclusive server-side state): two INDEPENDENT run fixtures, each its own pair of
  throwaway blueprints (`*-bp-parent`, no relations; `*-bp-child`, a single `parent` relation to
  the first flagged as the `composition` entry of its `hierarchyRelations`) created via the API,
  plus three entities each (p1, c1 with `parent` -> p1, c2 with no `parent`) — one fixture under
  `uniqueText("e2e-eq")` for the first scenario, one under `uniqueText("e2e-eq-ctx")` for the
  second (canvas context actions, v2.2.0). All removed at the end of their own test. Every
  identifier within a fixture shares its one run marker so both canvases can be narrowed to that
  run's own rows with the `q` filter.

## Scenario: the entity query narrows both canvases and reports a suggestion for a mistyped label

1. The admin signs in and seeds two throwaway blueprints via the API: a parent blueprint with
   no relations, and a child blueprint with a single `parent` relation to it flagged as the
   `composition` entry of its `hierarchyRelations`.
   - *Expected*: both creations succeed (`201`).
2. The admin seeds three entities via the API: p1 (parent blueprint), c1 (child blueprint,
   `parent` naming p1), and c2 (child blueprint, no `parent`).
   - *Expected*: all three creations succeed (`201`).
3. The admin opens **Entity graph** and narrows it to this run's own rows with the search
   filter (the workspace may carry other specs' blueprints/entities running in parallel).
   - *Expected*: p1, c1, and c2's identifiers are all visible; exactly three nodes render.
4. They type `MATCH (a:<child-blueprint>)-[:parent]->(b:<parent-blueprint>) RETURN a, b` into
   the "Entity query" editor and click **Run**.
   - *Expected*: the resulting `GET …/entities/graph` request carries both the existing `q`
     filter and the new `query` param, and answers `200`; exactly two nodes remain (c1 and
     p1, joined by the `parent` edge), c2 drops out, and an "Applied" badge appears.
5. They open **Entity hierarchy**, re-apply the same search filter, and look at the query bar
   and the tree without touching either.
   - *Expected*: the editor already shows the identical query text and the "Applied" badge
     (both canvases share one localStorage-backed draft/applied query); the tree shows p1 with
     c1 nested under it, and c2 does not appear.
6. Back on **Entity graph**, they select all the text in the editor and replace it with a
   mistyped label (`MATCH (a:<child-blueprint>x) RETURN a`).
   - *Expected*: the debounced `POST …/entities/query/check` answers `200`, and the rendered
     diagnostics include "Did you mean" followed by the real child blueprint identifier.
7. They click **Clear**.
   - *Expected*: the "Applied" badge disappears, and the next `GET …/entities/graph` request
     carries no `query` param.
8. Cleanup (API): c1 and c2, then p1, then the child blueprint, then the parent blueprint.

## Scenario: canvas context actions generate and run a query on both canvases

1. The admin signs in and seeds its own throwaway pair of blueprints (a parent with no
   relations, a child with a single `parent` relation to it flagged as the `composition` entry
   of its `hierarchyRelations`) and three entities (p1, c1 with `parent` naming p1, c2 with no
   `parent`) via the API, under its own run marker.
   - *Expected*: every creation succeeds (`201`).
2. The admin opens **Entity graph** and narrows it to this run's own rows with the search
   filter.
   - *Expected*: exactly three nodes render.
3. They right-click c1's node.
   - *Expected*: a menu opens named "Query actions for E2E EQ Ctx Child One", offering a
     "Query" group with "Expand 1 hop"/"2 hops"/"3 hops", "Ancestors in composition", and
     "Descendants in composition" (no "Owned by this team" — c1 is not a `_team` entity).
4. They click **Ancestors in composition**.
   - *Expected*: the resulting `GET …/entities/graph` request's `query` param contains
     `composition*1..10]->(a)` and answers `200`; the "Entity query" editor shows the generated
     MATCH/OPTIONAL MATCH text anchored on c1; exactly two nodes remain (c1 and p1 — its
     ancestor along the composition edge), c2 drops out, and an "Applied" badge appears.
5. They right-click p1's node and click **Descendants in composition**.
   - *Expected*: the request's `query` param contains `composition*1..10]-(d)` and answers
     `200`; the editor shows the generated text anchored on p1; exactly two nodes remain (p1 and
     c1 — its only descendant along the composition edge), c2 stays out.
6. They open **Entity hierarchy**, re-apply the same search filter, and see the same applied
   query and narrowing persist without re-running anything. They open p1's row operations menu
   (the "Operations for E2E EQ Ctx Parent One" kebab) and click its "Query" group's
   **Expand 1 hop**.
   - *Expected*: the resulting `GET …/entities/graph` request's `query` param contains
     `*1..1]-(m)` and answers `200`; the editor shows the generated 1-hop text anchored on p1,
     and the "Applied" badge is still present.
7. Cleanup (API): c1 and c2, then p1, then the child blueprint, then the parent blueprint.

## Not covered here (and why)

- **The query language's full grammar and diagnostic catalog** (every node/relation pattern,
  every diagnostic code, the evaluator's edge cases) — pinned by the pure engine tests (PR1);
  this journey proves one MATCH pattern narrowing both canvases and one `UNKNOWN_LABEL`
  suggestion end to end.
- **The 300 ms debounce timing itself** — the journey only awaits the resulting response, never
  a fixed frame count or elapsed-time assertion.
- **"Expand 2 hops"/"3 hops" and "Owned by this team"** — the second journey exercises one
  Expand hop count (1) plus both hierarchy actions; the other two Expand counts share the same
  builder (`EXPAND_HOPS`) and are pinned by `queryTemplates.test.ts`, and the ownership action
  needs a `_team` node, which neither fixture seeds — it is pinned by the same pure test.
