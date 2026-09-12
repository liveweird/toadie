# Entity graph (Port migration phase 3)

- **Spec**: [tests/entity-graph.spec.ts](../tests/entity-graph.spec.ts)
- **Actors**: the seed administrator (`admin@toadie.local`) seeds the throwaway registry state
  and user; one throwaway regular user owns every layout interaction
- **Owns** (exclusive server-side state): two throwaway blueprints (`e2e-eg-*-bp-parent`, a
  `peer` MANY self-relation; `e2e-eg-*-bp-child`, a single `parent` relation to the first
  flagged as the `composition` entry of its `hierarchyRelations`) created via the API, their
  four `e2e-eg-*` entities
  (two parents, two children, one parent Direct-owned by the throwaway team below), one
  throwaway `e2e-eg-*`-marked `_team` entity (never a foreign `_team`/`_user` row — that
  system blueprint is shared workspace-wide state this spec only ever adds its own row to),
  and one throwaway user who owns `/users/{id}/entity-graph-layout` — never the seed admin's
  layout. All removed at the end. Every identifier shares one run marker
  (`uniqueText("e2e-eg")`) so the graph can be narrowed to this run's own rows with the `q`
  filter once `_team` — a workspace-wide blueprint other specs also write to — joins the
  blueprint filter.

## Scenario: the entity graph filters by blueprint, folds hierarchy, and persists a manual layout

1. The admin signs in and seeds two throwaway blueprints via the API: a parent blueprint with
   a `peer` many self-relation, and a child blueprint with a single `parent` relation to the
   parent blueprint flagged as the `composition` entry of its `hierarchyRelations`.
   - *Expected*: both creations succeed (`201`).
2. The admin seeds five entities via the API: a throwaway `_team` entity, two parents (p1, p2,
   with p1's `peer` relation naming p2 and its `team` naming the throwaway team), and two
   children (c1, c2, both with `parent` naming p1).
   - *Expected*: all five creations succeed (`201`).
3. A throwaway user is created and signs in, then opens **Entity graph** from the nav's Port
   Ontology section.
   - *Expected*: the page renders its own heading.
4. They expand the filter panel, pick both throwaway blueprints PLUS `_team` in the
   Blueprints MultiSelect, and set the search filter to the run's own marker (the workspace
   may carry other specs' blueprints/entities/teams running in parallel, so the unfiltered
   `_team` blueprint alone would not be isolated).
   - *Expected*: exactly the four seeded entities' identifiers plus the throwaway team's are
     visible, and three blueprint frames appear, labelled by each blueprint's TITLE (the third
     reading "Team").
5. They toggle the `peer` relation chip off, then back on.
   - *Expected*: the graph's edge count drops from four (p1--peer-->p2, c1--parent-->p1,
     c2--parent-->p1, and the ownership edge p1--$team-->team) to three while the chip is off,
     and returns to four once it is back on; the five nodes themselves are unaffected — a
     relation chip governs edges, not entities.
6. They toggle the `$team` relation chip off, then back on.
   - *Expected*: the same drop from four edges to three and back, this time removing only the
     ownership edge.
7. They set the toolbar's Team filter to the throwaway team.
   - *Expected*: p1 (the entity it owns) and the team entity itself (the server's
     self-match rule) both stay visible; they clear the filter afterwards.
8. They collapse p1 via its own fold toggle, then expand it again.
   - *Expected*: collapsing hides c1 and c2 (p1's hierarchy-relation descendants — the
     admin-picked `parent` relation) and the pill names the hidden count (2); the throwaway
     team stays visible throughout (ownership never nests); expanding restores c1/c2.
9. They switch the layout to Manual and drag p1 to a new canvas position.
   - *Expected*: the drag produces exactly one `PUT /api/v1/users/{id}/entity-graph-layout`
     whose body's `positions` map contains the node id `<parent-blueprint>|<p1 identifier>`,
     asserted `204` outside the response predicate.
10. They reload the page.
    - *Expected*: Manual stays selected and p1's dragged position is restored — both from the
      re-fetched server document, confirmed via the node's canvas transform and the raw GET
      response.
11. Cleanup (API): the two children, then p1, then p2 (p1 is the referrer, deleted before its
    own peer target), then the throwaway team (now unreferenced), then the child blueprint,
    then the parent blueprint, then the throwaway user.

## Not covered here (and why)

- **The full filter/graph-endpoint rule table** (the `q` substring filter, unknown-identifier
  folding to an empty graph, stale/dropped relation targets) — pinned by `EntityGraphTest` and
  `EntityTest`; the journey proves the blueprint MultiSelect narrows the shown workspace.
- **The layout persistence lifecycle** (loading/error/retry states, debounced vs immediate
  saves, coalesced queued edits) — pinned by `graph-persistence.spec.ts`'s equivalent journey
  over the Backstage Graph page's twin document and by `useGraphLayout.test.ts`; this journey
  proves one real drag→save→reload round trip on the Entity graph's OWN document/endpoint.
- **The fold rule matrix** (multi-placement forests, folded/merged edge counting and labels) —
  pinned by `graphFold.test.ts` and `entityGraph.test.ts`; the journey proves one collapse/
  expand cycle end to end.
- **Inherited ownership and the `TEAM_TARGET_MISSING`/`TEAM_NOT_ALLOWED` findings** — pinned by
  `EntityOwnershipTest`; this journey proves one Direct-owned entity's ownership edge end to
  end.
