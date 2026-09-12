# Entities (Port compatibility, phase 2; + Phase 5 computed properties, v1.27.0)

- **Spec**: [tests/entities.spec.ts](../tests/entities.spec.ts)
- **Actors**: the seed administrator (`admin@toadie.local`) only — entities carry no admin
  gate anywhere, so the whole journey runs as one signed-in user.
- **Owns** (exclusive server-side state): three throwaway blueprints (`e2e-ent-bp-*`/
  `e2e-ent-inh-*`, unique per attempt — one target carrying typed properties and Direct
  ownership, one dependent carrying a required relation to it, and one INHERITED-ownership
  blueprint carrying an optional relation to the target) created directly via the API, their
  `e2e-ent-*` entities created through the UI/API, and one throwaway `e2e-ent-team-*` `_team`
  entity created via the API — all removed at the end (the inherited-ownership entity and its
  blueprint first, since its relation targets entity A; then the ordinary entities, then the
  team, then the remaining blueprints, dependent before target; the target blueprint's added
  computed properties are removed first — see step 19). The `_team`/`_user` system blueprints
  themselves are seeded and protected — this spec never edits or deletes them, only its own
  throwaway `_team` entity. The blueprint registry is SHARED state and **this spec is one of
  its two in-run writers**, alongside `blueprints.spec.ts` — it never edits or deletes rows it
  did not create.

## Scenario: an entity is created from a blueprint, a relation blocks its deletion, and a blueprint change makes it stale until fixed

1. The admin signs in and seeds via the API: a target blueprint with a string property
   carrying a two-value enum (`tier`), a required number property (`replicas`), a boolean
   property (`public`), and Direct ownership titled "Owned by"; a dependent blueprint with a
   single, required relation (`parent`) targeting the first; and one throwaway `_team` entity.
   - *Expected*: all three creations succeed (`201`).
2. They open **Entities** from the nav's Port Ontology section and pick the target blueprint
   in the toolbar Select.
   - *Expected*: the URL carries `?blueprint=<target>`.
3. They open **New entity** and submit it empty.
   - *Expected*: the identifier, title, and required-property (`replicas`) errors render
     inline; the URL stays on the create route.
4. They fill a unique identifier and title, pick `tier = gold`, enter `replicas = 3`, pick
   `public = True`, and pick the throwaway team in the "Owned by" MultiSelect, then save.
   - *Expected*: the JSON preview shows `"replicas": 3` before saving; the POST succeeds; the
     list shows the new row with its `tier`/`replicas`/`public` columns, a Team chip naming the
     picked team, and no findings badge.
5. They pick the team in the toolbar's Team filter, then try to delete it via the API.
   - *Expected*: the URL carries `?team=<team>`; the owned entity still shows; the DELETE is
     refused (`409`) with a detail naming the referring entity as `<target>/<entity>`.
6. Via the API (Inherited ownership, v1.30.0), they seed a throwaway blueprint carrying an
   optional `parent` relation to the target blueprint and `ownership: { type: "Inherited",
   path: "parent" }`, then one entity of it relating to the first entity — sending no `team` at
   all, since an Inherited entity never stores one.
   - *Expected*: both creations succeed (`201`).
7. They switch the toolbar's blueprint Select to the inherited blueprint — the `?team=` filter
   from step 5 stays set across the switch — then clear the Team filter.
   - *Expected*: the inherited entity's row is visible with its Team chip naming the throwaway
     team, exactly like the directly-owned row did in step 4; the row stays visible once the
     filter is cleared, since it was never gated on it.
8. They open the **Entity graph** page and set its own toolbar Team filter to the same
   throwaway team.
   - *Expected*: the first entity's node, the inherited entity's node, and the team's own node
     are all present — the graph's Team filter matches the same computed team as the list's.
9. They open the entity's editor, deselect the team pill in "Owned by", and save; then delete
   the team via the API again.
   - *Expected*: the PUT succeeds; the DELETE now succeeds (`204`).
10. They pick the dependent blueprint, open **New entity**, fill a unique identifier and title,
    and pick the first entity as its required `parent` relation.
    - *Expected*: the relation Select offers `identifier — title`; the POST succeeds.
11. Back on the target blueprint's list, they try to delete the first entity.
    - *Expected*: the confirm modal's DELETE is refused (`409`) with the "still targeted by
      another entity's relation" message; the row stays.
12. They type the first entity's identifier into the toolbar's free-text Search filter.
    - *Expected*: after the debounce, the URL carries `?q=`; the matching row stays visible —
      the filter is a deep-link URL slot too (D2), like `?blueprint=`/`?team=` above.
13. They edit the target blueprint via the API to add a required string property (`owner`) to
    its schema.
    - *Expected*: the PUT succeeds (`204`); nothing about the existing entity changes yet — it
      is simply no longer valid against the current schema.
14. They reload the target blueprint's entity list.
    - *Expected*: the first entity's row now shows a findings badge reading "1 finding".
15. They open the first entity's editor.
    - *Expected*: an orange alert states the entity is out of date and lists `properties.owner`
      among the offending fields.
16. They fill the new `owner` field and save.
    - *Expected*: the PUT succeeds; back on the list, the entity's findings badge is gone.
17. Via the API (computed properties, phase 5, v1.27.0), they PUT the target blueprint (its
    current schema, unchanged) adding a colorized calculation (`tier_badge`, reading its own
    `tier` property, colors gold/silver) and an aggregation (`dependents`, targeting the
    dependent blueprint, counting entities) — then PUT the dependent blueprint (its original
    definition, unchanged) adding a mirror (`parent_tier`, reading the target's `tier` through
    the existing `parent` relation).
    - *Expected*: both PUTs succeed (`204`).
18. They open the dependent blueprint's entity list, then the second entity's editor.
    - *Expected*: the mirrored tier is the list's first (and only) preview column, since the
      dependent has no schema properties of its own; the editor's read-only "Computed" section
      names it "Parent tier" with the Mirror badge and the mirrored value.
19. They open the first (target) entity's editor.
    - *Expected*: the "Computed" section shows "Tier badge" (Calculation badge, the entity's own
      tier value) and "Dependents" (Aggregation badge, a count of 1 — the second entity relates
      to it); the JSON preview contains neither computed id; saving succeeds (a
      leaked computed key in the request would `400`).
20. Cleanup: the target blueprint's computed properties are removed first (restoring its
    step-13 definition — an aggregation target blocks deletion exactly like a relation target,
    so the dependent blueprint could otherwise no longer be deleted); then the
    Inherited-ownership entity (its relation targets entity A) and its blueprint (its relation
    targets the target blueprint) — ahead of the ordinary cleanup, since entity A's own delete
    would otherwise be blocked by this referrer too; then the second (referring) entity, then
    the first, then the throwaway team (if not already removed), then the dependent blueprint,
    then the target blueprint — all via the API.

## Not covered here (and why)

- **The full value-validation rule table** (string/number/array/object type-by-type rules,
  formats, `labeled-url`, computed-property rejection, the identifier grammar's unicode/`.`/
  `..` edge cases) — pinned by `EntityValidationTest`; the journey proves the editor blocks an
  empty submit and a missing required property client-side.
- **The concurrency proofs** (a relation create racing its target's delete; a blueprint delete
  racing an entity create) — server-pinned by `EntityConcurrencyTest`; no browser journey can
  hold a database lock.
- **The rename-cascade rule on entity identifiers** and the **blueprint-delete-with-active-
  entities 409** — pinned by `EntityTest`/`EntityReferencesTest`; this journey's blueprints
  are removed only after their entities, so the cascade/refusal paths aren't exercised here.
- **Every property/relation widget** (yaml/markdown/proto textareas, date-time/timer hints,
  array-of-object and labeled-url JSON editing, MultiSelect relations) — pinned by the
  co-located unit suites (`EntityPropertyField.test.tsx`, `EntityRelationField.test.tsx`,
  `entityForm.test.ts`); the journey proves one string, one number, one boolean, and one
  single-relation widget end to end.
