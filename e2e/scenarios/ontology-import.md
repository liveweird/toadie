# Ontology import & export (Phase 6, v1.28.0)

- **Spec**: [tests/ontology-import.spec.ts](../tests/ontology-import.spec.ts)
- **Actors**: the seed administrator (`admin@toadie.local`) only — blueprint import is
  ADMIN-gated but entity import carries no gate, and the whole journey needs an admin anyway to
  exercise both endpoints, so one signed-in session covers it.
- **Owns** (exclusive server-side state): two throwaway `e2e-oi-bp-*` blueprints (A — a mirror
  and an aggregation, both forward-referencing B, plus a relation forming a genuine cycle with
  B's own relation back to A — and B) and their `e2e-oi-ent-*` entities (`a1` of A, `b1` of B
  relating to it), created and removed entirely through the Import page and the API. The
  blueprint registry is shared run-state and **this spec is a FIFTH in-run writer**, alongside
  `blueprints.spec.ts`/`entities.spec.ts`/`entity-graph.spec.ts`/`entity-hierarchy.spec.ts` — it
  never edits or deletes a foreign row. The Export step downloads the WHOLE blueprint registry
  (the page's only mode), so the round trip at the end re-imports every blueprint currently
  active in the shared registry — harmless with `replaceExisting` off (every foreign row reports
  Already exists, unmodified) and asserted only on this spec's own two identifiers.

## Scenario: a mixed batch imports with a two-pass blueprint cycle, then round-trips through export

1. The admin signs in and opens **Import** from the Port Ontology nav.
   - *Expected*: the page renders with the Replace-existing switch off and Import disabled.
2. They paste a batch with two blueprints (A carrying `createdAt`/`_meta` noise, a relation, a
   mirror, and an aggregation that all forward-reference B, declared afterward — together with
   B's own relation back to A this is a genuine cycle) and three entities (`b1` of B relating to
   `a1`, listed BEFORE it; `a1` of A; a document naming an unknown blueprint).
   - *Expected*: the summary reads "2 blueprints, 3 entities ready"; the stripped-keys note
     names `_meta` and `createdAt`.
3. They click **Check**.
   - *Expected*: A and B both predict "Would be created" (the cycle resolves in two passes even
     as a dry run). The two entities targeting the not-yet-created A/B report **Invalid**
     "Unknown blueprint" — entity import resolves `blueprint` only against the ACTUAL stored
     registry, never a sibling `/blueprints/import` call's own pending batch (two separate
     requests; see `.claude/docs/persistence.md` "Reads are plain, uncoordinated snapshots") —
     exactly like the deliberately unknown-blueprint document, which is Invalid throughout.
     Nothing is in the registry yet (confirmed via `GET /api/v1/blueprints`).
4. They click **Import**.
   - *Expected*: A, B, and now both entities (their blueprints genuinely exist by the time the
     entity half runs) report **Created**, with links to their editors; the unknown-blueprint
     document is still Invalid. Blueprint A's `GET` carries the restored aggregation and
     relation (the pass-2 write landed); `b1`'s `GET` carries `relations.peer` naming `a1`.
5. They import the identical batch again with the switch off.
   - *Expected*: A, B, `a1`, and `b1` all report **Already exists** (gray); the unknown-blueprint
     document is still Invalid.
6. They change A's title in the pasted text, switch **Replace existing definitions** on, and
   import again.
   - *Expected*: A, B, `a1`, and `b1` all report **Updated**; blueprint A's `GET` shows the new
     title.
7. On the **Blueprints** page they click **Export JSON**.
   - *Expected*: the download parses as JSON; it contains A and B with none of the response-only
     keys (`id`, `system`, `createdAt`, `createdBy`, `updatedAt`, `creatorName`,
     `creatorDeleted`), and A's aggregation still targets B.
8. On the **Entities** page, picking blueprint A, they click **Export JSON**.
   - *Expected*: the download contains `a1` with none of `findings`/`blueprintId`/`id`, and its
     `properties` carry neither of A's computed ids (`siblingTitle`, `peerCount`) — a mirror or
     aggregation's evaluated value must never round-trip back into an import.
9. Back on Import, they paste the blueprints export and add the entities export as a picked
   file (`setInputFiles`), switch Replace-existing off, and import again.
   - *Expected*: the Source column appears (two sources); A, B, and `a1` all report **Already
     exists** — the round trip changed nothing.
10. Cleanup (API): PUT blueprint A back to its bare schema — dropping the relation, mirror, and
    aggregation that forward-reference B — BEFORE anything else, breaking the A-targets-B/
    B-targets-A cycle; then delete `b1`, then `a1`; then delete B, then A. Every status is
    asserted outside the request itself.

## Not covered here (and why)

- **The non-admin `FORBIDDEN` client-side rows and >200-document cross-chunk forward
  references** — unit-tested (`ontologyImport.test.ts`/`ImportOntology.test.tsx`); no browser
  journey needs a second role or a 200-document batch.
- **The full pure planner rule tables** (fixpoint cascades, cap handling, required-reference
  cycles) — pinned by `BlueprintImportPlanTest`/`EntityImportPlanTest`; this journey proves one
  real two-blueprint cycle end to end, not every rule in isolation.
- **The sample-ontology import recipe** (eleven blueprint files + 59 entities through this same
  page) — covered by `SampleBlueprintsTest`/`SampleEntitiesTest` and `sample-data/README.md`'s
  documented manual recipe, not by an automated browser journey.
