# Fill-in tiers (2.18.0)

- **Spec**: [tests/tiers.spec.ts](../tests/tiers.spec.ts)
- **Actors**: the seed administrator (`admin@toadie.local`) seeds the throwaway registry state
  and drives the whole journey (blueprint editing is ADMIN-only; entities carry no admin gate)
- **Owns** (exclusive server-side state): two throwaway blueprints (`e2e-tier-*-bp-tiered`, two
  string properties `alpha`/`beta`, tiered through the editor in this journey;
  `e2e-tier-*-bp-plain`, one string property, never tiered) created via the API, and one
  `e2e-tier-*` entity of each — all removed at the end. No per-user document is written (the
  graph is only read, never dragged or folded). Focus choices persist in the browser context's
  localStorage only.

## Scenario: tiers mark blueprints and fields, and Focus narrows the lists, the entity editor and the graph

1. The admin signs in and seeds two throwaway blueprints via the API (the first with properties
   `alpha` and `beta`, the second with one property), then one entity of each — the tiered
   blueprint's entity carries only `beta`.
   - *Expected*: all four creations succeed (`201`).
2. The admin opens **Blueprints**, chooses **Edit** on the first blueprint's row, sets the
   "Fill-in tier" Select to Tier 1 and the "Tier for alpha" Select to Tier 1, and saves.
   - *Expected*: the live JSON preview shows a `tiers` member before saving; the `PUT` returns
     `204` and the list reopens.
3. On the Blueprints list the admin reads the two rows, then picks Focus "Tier 1".
   - *Expected*: the tiered row leads with a tier-1 dot and the plain row has none; with the
     Focus set, the plain row disappears and the tiered row stays.
4. The admin opens the tiered entity's editor.
   - *Expected*: the "E2E Alpha" label leads with a tier-1 dot and "E2E Beta" has none; the
     header badge reads "Tier 1 incomplete" because the tier-1 field is empty.
5. The admin picks Focus "Tier 1" in the editor.
   - *Expected*: the untiered "E2E Beta" field folds behind a "Show 1 more field" button and is
     hidden; opening it reveals the field still holding its stored value `filled`.
6. The admin types a value into "E2E Alpha" (without saving).
   - *Expected*: the badge moves live to "Filled through tier 4".
7. The admin opens **Entity graph**, narrows it to this run with the search filter, runs the
   query `MATCH (n) WHERE n.$fillTier < 1 AND n.$identifier STARTS WITH '<run>' RETURN n`.
   - *Expected*: before the query both entities are drawn; after it the graph request carries
     `query`, returns `200`, and only the tiered blueprint's entity remains (the untiered
     blueprint's entity has no `$fillTier`).
8. The admin clears the query and then picks Focus "Tier 1" in the graph's Filters.
   - *Expected*: both nodes return after Clear; with the Focus set the untiered blueprint's
     node disappears and the tiered blueprint's node stays.
9. Cleanup deletes both entities, then both blueprints.
   - *Expected*: every delete returns `204`, even when an earlier step failed.

## Not covered here (and why)

- Relation tiers, `r.$tier` and the edge thinning of the graph's Focus — covered by the server's
  `QueryTierEvaluatorTest` and the web's `entityGraph`/`EntityGraph` unit tests; the journey
  would need relations between two more throwaway blueprints for no extra browser risk.
- The Hierarchy and Errors pages' Focus — they share `useEntityGraphFilterState` with the graph,
  whose Focus is exercised here; their page tests pin the wiring.
