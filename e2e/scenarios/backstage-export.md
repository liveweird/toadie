# Export to Backstage (conversion phase 5b)

- **Spec**: [tests/backstage-export.spec.ts](../tests/backstage-export.spec.ts)
- **Actors**: the seed administrator (`admin@toadie.local`) — an ordinary user here (conversion
  and catalog-file creation carry no admin gate; the admin token is used for the API-seeded
  mapping, blueprints and entities and for cleanup)
- **Owns** (exclusive server-side state): two throwaway blueprints (`e2e-be-*-grp`, a team
  blueprint with a self `parent` relation, and `e2e-be-*-bp`, a service blueprint with a `type`
  enum and an `owner` relation to the team), one entity of each (`e2e-be-*-team`, `e2e-be-*-svc`),
  one conversion mapping (`e2e-be-*-map`: service -> Component, team -> Group) and the two catalog
  files the journey creates (a Group and a Component named after those entities, default
  namespace). All deleted at the end (files, entities, mapping, blueprints).

## Scenario: an owned fixture exports to Backstage: rows and YAML, then the catalog files are created and re-converted onto

1. The admin signs in and seeds the two blueprints, the two entities and the mapping via the API.
   - *Expected*: every creation succeeds (`201`).
2. They open **Export to Backstage** from the sidebar's Ontology section and pick the throwaway
   mapping.
   - *Expected*: **Convert** is enabled.
3. They open the Query section, type a `MATCH … OPTIONAL MATCH … RETURN a, t` query naming the two
   throwaway blueprints, and press **Run**, then **Convert**.
   - *Expected*: the catalog-documents request (`200`) carries the applied `query`; the report has
     exactly two rows — the service as `Component:default/<svc>` and the team as
     `Group:default/<team>` — and the YAML preview shows both documents, the Component's `owner`
     rendered as `group:default/<team>`.
4. They press **Create / update catalog files**.
   - *Expected*: the dialog converts once (it never trusts the page's snapshot) and predicts
     "Would be created" for both rows; **Confirm** imports exactly that previewed batch and stores
     them (`POST /files/import`, `200`), the rows read "Created" and link to the new files.
5. They close the dialog and press **Convert** again, then **Create / update catalog files**.
   - *Expected*: the merge finds the stored files (the service row now links to its catalog
     file) and the dry run predicts "Would stay unchanged" for both.
   - While the dialog is open the admin edits the Group's catalog file through the API (a headerless
     `PUT /files/{id}` adding a description), then presses **Confirm**: `POST /files/import` answers
     `200`, the Group row reads "Changed meanwhile" (its previewed revision is out of date; the file
     keeps the edit) and the Component row reads "Unchanged".
6. They follow the service row's link.
   - *Expected*: the catalog file editor opens on the created file (its Name field carries the
     entity's identifier).

## Not covered here (and why)

- The merge switch off, a stale result dropped when the controls change, stale mappings, 409/429
  answers, the windowed rows table, a chunk failing part-way in the dialog and the YAML download — pinned by `ExportToBackstage.test.tsx`; the conversion semantics themselves by the
  server's `BackstageExportTest`/`ConversionRoutesTest`.
