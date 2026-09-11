# Entities (Port compatibility, phase 2; + Phase 5 computed properties, v1.27.0)

- **Spec**: [tests/entities.spec.ts](../tests/entities.spec.ts)
- **Actors**: the seed administrator (`admin@toadie.local`) only — entities carry no admin
  gate anywhere, so the whole journey runs as one signed-in user.
- **Owns** (exclusive server-side state): two throwaway blueprints (`e2e-ent-bp-*`, unique per
  attempt — one target carrying typed properties and Direct ownership, one dependent carrying
  a required relation to it) created directly via the API, their `e2e-ent-*` entities created
  through the UI, and one throwaway `e2e-ent-team-*` `_team` entity created via the API — all
  removed at the end (entities first, then the team, then blueprints, dependent before
  target; the target blueprint's added computed properties are removed first — see step 16).
  The `_team`/`_user` system blueprints themselves are seeded and protected — this spec never
  edits or deletes them, only its own throwaway `_team` entity. The blueprint
  registry is SHARED state and **this spec is one of its two in-run writers**, alongside
  `blueprints.spec.ts` — it never edits or deletes rows it did not create.

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
6. They open the entity's editor, deselect the team pill in "Owned by", and save; then delete
   the team via the API again.
   - *Expected*: the PUT succeeds; the DELETE now succeeds (`204`).
7. They pick the dependent blueprint, open **New entity**, fill a unique identifier and title,
   and pick the first entity as its required `parent` relation.
   - *Expected*: the relation Select offers `identifier — title`; the POST succeeds.
8. Back on the target blueprint's list, they try to delete the first entity.
   - *Expected*: the confirm modal's DELETE is refused (`409`) with the "still targeted by
     another entity's relation" message; the row stays.
9. They edit the target blueprint via the API to add a required string property (`owner`) to
   its schema.
   - *Expected*: the PUT succeeds (`204`); nothing about the existing entity changes yet — it
     is simply no longer valid against the current schema.
10. They reload the target blueprint's entity list.
    - *Expected*: the first entity's row now shows a findings badge reading "1 finding".
11. They open the first entity's editor.
    - *Expected*: an orange alert states the entity is out of date and lists `properties.owner`
      among the offending fields.
12. They fill the new `owner` field and save.
    - *Expected*: the PUT succeeds; back on the list, the entity's findings badge is gone.
13. Via the API (computed properties, phase 5, v1.27.0), they PUT the target blueprint (its
    current schema, unchanged) adding a colorized calculation (`tier_badge`, reading its own
    `tier` property, colors gold/silver) and an aggregation (`dependents`, targeting the
    dependent blueprint, counting entities) — then PUT the dependent blueprint (its original
    definition, unchanged) adding a mirror (`parent_tier`, reading the target's `tier` through
    the existing `parent` relation).
    - *Expected*: both PUTs succeed (`204`).
14. They open the dependent blueprint's entity list, then the second entity's editor.
    - *Expected*: the mirrored tier is the list's first (and only) preview column, since the
      dependent has no schema properties of its own; the editor's read-only "Computed" section
      names it "Parent tier" with the Mirror badge and the mirrored value.
15. They open the first (target) entity's editor.
    - *Expected*: the "Computed" section shows "Tier badge" (Calculation badge, the entity's own
      tier value) and "Dependents" (Aggregation badge, a count of 1 — the second entity relates
      to it); the JSON preview contains neither computed id; saving succeeds (a
      leaked computed key in the request would `400`).
16. Cleanup: the target blueprint's computed properties are removed first (restoring its
    step-9 definition — an aggregation target blocks deletion exactly like a relation target,
    so the dependent blueprint could otherwise no longer be deleted), then the second
    (referring) entity is deleted, then the first, then the throwaway team (if not already
    removed), then the dependent blueprint, then the target blueprint — all via the API.

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
