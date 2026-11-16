# Entity bulk change (one field across a blueprint's entities, 2.38.0)

- **Spec**: [tests/entity-bulk-change.spec.ts](../tests/entity-bulk-change.spec.ts)
- **Actors**: the seed administrator (`admin@toadie.local`) — an ordinary user here (entities
  carry no admin gate and neither does the bulk change)
- **Owns** (exclusive server-side state): per scenario, one throwaway parent blueprint
  `e2e-ebulk-…-parent` with one entity, and one throwaway child blueprint `e2e-ebulk-…-child`
  (an enum property "Stage" with `alpha`/`beta`, and a single `parent` relation to the parent
  blueprint, mapped in `hierarchyRelations` to the FIRST value of the hierarchies dictionary —
  read through the API, never written; if the dictionary were empty the relation stays unmapped
  and the children are ticked one by one) with three entities: two at `alpha`, one already at
  `beta`. All of it is created through the API and deleted by `finally` (children, parent, child
  blueprint, parent blueprint), plus the history events and audit lines the bulk write mints.

## Scenario: bulk-changing a field across a branch updates the differing entities and records the change in their history

1. The admin signs in and, via the API, seeds the parent and child blueprints and the four
   entities.
2. They open `/entities`, pick the child blueprint in the Blueprint select, and follow the
   **Bulk change** link.
   - *Expected*: the link's `href` carries the pick (the spec waits for it before clicking);
     the view opens at `/entities/bulk-change?…blueprint=<child>` with the child blueprint's pill
     chosen, "Entities shown: 3" and all three children listed (under the parent's context row
     when the hierarchy is mapped).
3. They tick the parent's group checkbox ("Select all 3 entities in the branch of …").
   - *Expected*: "Selected: 3".
4. They press **Confirm**, choose the **Stage** field, keep the **Set** operation, pick `beta`
   and press **Preview**.
   - *Expected*: the dry run answers `200`; the summary reads "Will be updated: 2, unchanged: 1,
     cannot be changed: 0."; the two alpha children read "Will update" (with "alpha → beta") and
     the beta one reads "Unchanged".
5. They press **Apply to 2 entities**.
   - *Expected*: the real run answers `200`; "Updated: 2, unchanged: 1, not changed: 0."; the
     rows read Updated / Updated / Unchanged. After **Close** the dialog is gone and "Selected: 0"
     (finished rows leave the selection).
6. The spec reads the three entities through the API.
   - *Expected*: all three hold `stage: beta`.
7. They open an updated child's editor and look at its **History**.
   - *Expected*: "Entity updated by a bulk change: Stage.", a "Stage: alpha → beta" line, and
     the creation entry below.
8. The spec deletes the children, the parent, then both blueprints (in a `finally`).

## Scenario: an entity edited between the preview and the apply comes back stale while the others update

1. The admin signs in and the same world is seeded through the API.
2. They open `/entities/bulk-change?blueprint=<child>`, tick the parent's group checkbox, and
   preview Stage → `beta` exactly as above.
   - *Expected*: 2 will update, 1 unchanged.
3. With the dialog still open, the spec replaces one of the two to-be-updated children through
   the API (`PUT`, a new title, Stage still `alpha`).
   - *Expected*: the PUT answers `204`.
4. They press **Apply to 2 entities**.
   - *Expected*: `200`; "Updated: 1, unchanged: 1, not changed: 1."; the edited child's row
     reads **Stale**, the other alpha child Updated, the beta one Unchanged; the dialog hints
     that the entities which were not changed stay selected.
5. The spec reads the entities through the API.
   - *Expected*: the edited child keeps its API-edited title and still holds `alpha`; the other
     child holds `beta`.
6. They close the dialog.
   - *Expected*: "Selected: 1" — only the stale entity stays selected for a retry.
7. The spec cleans up (in a `finally`).

## Not covered here (and why)

- **The operations other than Set, the other field kinds (relations, team), the per-row
  INVALID/ERROR verdicts, the 200-entity cap, request-level 400s, the ledger 400/429, the audit
  lines and the history origin vocabulary** — pinned by the server suite (`EntityBulkChangeTest`,
  `EntityBulkPlanTest`, `EntityAtomicHistoryTest`, `EntityEventsTest`/`EntityHistoryTest`) against
  owned fixtures; the SPA's tree pruning, tri-state selection arithmetic, request builder, the
  modal's three steps and the result tables are pinned by `EntityBulkChange.test.tsx`,
  `EntityBulkChangeModal.test.tsx`, `EntityBulkResults.test.tsx`, `BlueprintPickPills.test.tsx`,
  `BulkChangeButton.test.tsx`, `entityBulk.test.ts` and `entityLinks.test.ts`. E2E sticks to the
  two journeys that prove the wiring end to end.
- **The entry buttons on the Entity graph and Entity hierarchy toolbars** — unit-tested
  (`EntityGraph.test.tsx`, `EntityHierarchy.test.tsx`); the Entities page entry is the journey's.
- **A 200-entity apply against the SPA's 30 s request timeout** — a timing question for the
  capacity probe, not a browser journey.
- **Accessibility** — the view is swept (with a blueprint chosen) in `accessibility.spec.ts`.
