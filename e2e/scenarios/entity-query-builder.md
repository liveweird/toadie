# Guided entity query building

- **Spec**: [tests/entity-query-builder.spec.ts](../tests/entity-query-builder.spec.ts)
- **Actors**: seed admin
- **Owns** (exclusive server-side state): two `e2e-query-builder-*` blueprints and three
  entities created by this journey; deletes its instances before its dependent blueprint
  and then its target blueprint. Never changes a shared dictionary or graph layout.

## Scenario: the guided query builder preserves drafts and runs the generated query on both canvases

1. Create a parent blueprint and a service blueprint with a single parent relation and
   a Lifecycle enum property whose identifier is the reserved word `order`. Create one
   parent and production/experimental services linked to it.
2. Open Entity graph, filter to the owned fixture, and enter a hand-written query draft.
   Open Build query and cancel.
   - *Expected*: all three entities remain visible and the original draft is unchanged.
3. Reopen Build query. Select the service type, Lifecycle equals production, a required
   Parent connection to the owned parent identifier, and both entities in the results.
   Check the completed dialog at a narrow mobile viewport, restore the desktop viewport,
   and choose Use query.
   - *Expected*: the dialog has no horizontal overflow and its primary action remains
     reachable by vertical scrolling.
   - *Expected*: the generated draft quotes the reserved property name; the graph still
     shows all three entities because no query has run yet.
4. Run the generated query and observe the exact graph response.
   - *Expected*: HTTP 200 and only the production service and its parent are displayed.
5. Switch to Entity hierarchy, open the query section, and open/cancel Build query.
   - *Expected*: the generated text and its applied results survive the page switch;
     cancelling the builder preserves them.
6. Remove only this journey's entities and blueprints in dependency order, also on failure.

## Not covered here (and why)

Primitive operator combinations, optional-match generation, ownership/hierarchy directions,
schema invalidation, input limits, and dialog state are covered by focused frontend tests.
This journey exercises the generated query against the real backend parser and evaluator.
