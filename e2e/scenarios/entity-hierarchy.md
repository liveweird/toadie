# Entity hierarchy (Port migration phase 3)

- **Spec**: [tests/entity-hierarchy.spec.ts](../tests/entity-hierarchy.spec.ts)
- **Actors**: the seed administrator (`admin@toadie.local`) only — entities carry no admin
  gate anywhere, so the whole journey runs as one signed-in user.
- **Owns** (exclusive server-side state): two throwaway blueprints (`e2e-eh-*-bp-parent`;
  `e2e-eh-*-bp-child`, a single `parent` relation flagged as its `hierarchyRelation`), four
  `e2e-eh-*` entities (one parent, two children targeting it, one orphan child left
  parent-less but Direct-owned by the throwaway team below), and one throwaway `e2e-eh-*`-
  marked `_team` entity (never a foreign `_team`/`_user` row — that system blueprint is shared
  workspace-wide state this spec only ever adds its own row to) — all created via the API and
  removed at the end. Every identifier shares one run marker (`uniqueText("e2e-eh")`) so the
  tree can be narrowed to this run's own rows with the `q` filter once `_team` — a
  workspace-wide blueprint other specs also write to — joins the blueprint filter.

## Scenario: the entity hierarchy nests by the hierarchy relation, pins a subtree, and blocks a referenced delete

1. The admin signs in and seeds two throwaway blueprints via the API: a parent blueprint, and
   a child blueprint whose single `parent` relation (targeting the parent blueprint) is
   flagged as its `hierarchyRelation`.
   - *Expected*: both creations succeed (`201`).
2. They seed a throwaway `_team` entity, then four entities via the API: one parent (p1), two
   children (c1, c2) whose `parent` relation names p1, and one orphan child (c3) whose
   `parent` relation is left unset but whose `team` names the throwaway team.
   - *Expected*: all five creations succeed (`201`).
3. They open **Entity hierarchy** from the nav's Port Ontology section, expand the filter
   panel, pick both throwaway blueprints PLUS `_team` in the Blueprints MultiSelect, and set
   the search filter to the run's own marker (the workspace may carry other specs'
   blueprints/entities/teams running in parallel, so the unfiltered `_team` blueprint alone
   would not be isolated) — this page keeps its OWN filter state, independent of the Entity
   graph page's, though both render through the same shared filter controls.
   - *Expected*: all four entities' titles plus the throwaway team's are visible.
4. They collapse p1's branch from its row toggle, then expand it again.
   - *Expected*: collapsing hides only c1 and c2 (p1's hierarchy-relation children) while the
     orphan c3 and the throwaway team — both roots of their own, even though the team owns c3
     and `_team` carries its own seeded `hierarchyRelation` (ownership never nests) — stay
     visible; expanding restores c1/c2. This is the proof that c1/c2 nest under p1 and neither
     c3 nor the team does.
5. They **Pin** p1 from its row's Operations menu, then clear the pin.
   - *Expected*: pinning narrows the tree to p1 and its descendants (c1, c2 stay, c3
     disappears) behind a "Pinned: p1" badge; clearing it restores c3.
6. They try to **Delete** p1 from its row's Operations menu and confirm.
   - *Expected*: the request is refused (`409`); the confirm dialog names the reason ("This
     entity is still targeted by another entity's relation") since c1/c2's `parent` relation
     still targets it; they cancel and p1 remains.
7. Cleanup (API): the three children, then p1, then the throwaway team (now unreferenced),
   then the child blueprint, then the parent blueprint.

## Not covered here (and why)

- **The forest-shaping rule matrix** (multiple hierarchy roots, cycle-island promotion, a
  stale array value on a now-single relation) — pinned by `entityGraph.test.ts`; the journey
  proves one two-child parent plus one orphan root, plus a third ownership-only root, end to
  end.
- **Expand all / Collapse all, load error, and empty states** — pinned by
  `EntityHierarchy.test.tsx`.
- **The pin's persistence and auto-unpin when its entity leaves the filtered payload** —
  pinned by `EntityHierarchy.test.tsx`, the `Hierarchy.test.tsx` idiom applied to entities.
- **Edit from a tree row** — the same `RouterLink` the Entities list's row already exercises
  through `entities.spec.ts`.
- **Inherited ownership and the `TEAM_TARGET_MISSING`/`TEAM_NOT_ALLOWED` findings** — pinned by
  `EntityOwnershipTest`; this journey proves one Direct-owned orphan's third-root behavior end
  to end.
