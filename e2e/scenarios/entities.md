# Entities (Port compatibility, phase 2)

- **Spec**: [tests/entities.spec.ts](../tests/entities.spec.ts)
- **Actors**: the seed administrator (`admin@toadie.local`) only — entities carry no admin
  gate anywhere, so the whole journey runs as one signed-in user.
- **Owns** (exclusive server-side state): two throwaway blueprints (`e2e-ent-bp-*`, unique per
  attempt — one target carrying typed properties, one dependent carrying a required relation
  to it) created directly via the API, and their `e2e-ent-*` entities created through the UI —
  all removed at the end (entities first, then blueprints, dependent before target). The
  blueprint registry is SHARED state and **this spec is one of its two in-run writers**,
  alongside `blueprints.spec.ts` — it never edits or deletes rows it did not create.

## Scenario: an entity is created from a blueprint, a relation blocks its deletion, and a blueprint change makes it stale until fixed

1. The admin signs in and seeds two throwaway blueprints via the API: a target blueprint with
   a string property carrying a two-value enum (`tier`), a required number property
   (`replicas`), and a boolean property (`public`); and a dependent blueprint with a single,
   required relation (`parent`) targeting the first.
   - *Expected*: both creations succeed (`201`).
2. They open **Entities** from the nav's Port Ontology section and pick the target blueprint
   in the toolbar Select.
   - *Expected*: the URL carries `?blueprint=<target>`.
3. They open **New entity** and submit it empty.
   - *Expected*: the identifier, title, and required-property (`replicas`) errors render
     inline; the URL stays on the create route.
4. They fill a unique identifier and title, pick `tier = gold`, enter `replicas = 3`, and pick
   `public = True`, then save.
   - *Expected*: the JSON preview shows `"replicas": 3` before saving; the POST succeeds; the
     list shows the new row with its `tier`/`replicas`/`public` columns and no findings badge.
5. They pick the dependent blueprint, open **New entity**, fill a unique identifier and title,
   and pick the first entity as its required `parent` relation.
   - *Expected*: the relation Select offers `identifier — title`; the POST succeeds.
6. Back on the target blueprint's list, they try to delete the first entity.
   - *Expected*: the confirm modal's DELETE is refused (`409`) with the "still targeted by
     another entity's relation" message; the row stays.
7. They edit the target blueprint via the API to add a required string property (`owner`) to
   its schema.
   - *Expected*: the PUT succeeds (`204`); nothing about the existing entity changes yet — it
     is simply no longer valid against the current schema.
8. They reload the target blueprint's entity list.
   - *Expected*: the first entity's row now shows a findings badge reading "1 finding".
9. They open the first entity's editor.
   - *Expected*: an orange alert states the entity is out of date and lists `properties.owner`
     among the offending fields.
10. They fill the new `owner` field and save.
    - *Expected*: the PUT succeeds; back on the list, the entity's findings badge is gone.
11. Cleanup: the second (referring) entity is deleted first, then the first, then the
    dependent blueprint, then the target blueprint — all via the API.

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
