# Backstage catalog import on the Ontology import page (conversion phase 5c)

- **Spec**: [tests/backstage-import.spec.ts](../tests/backstage-import.spec.ts)
- **Actors**: the seed administrator (`admin@toadie.local`) — an ordinary user here (the entity
  import carries no admin gate; the admin token is used for the API-seeded mapping and
  blueprints and for cleanup)
- **Owns** (exclusive server-side state): two throwaway blueprints (`e2e-bi-*-grp`, a team
  blueprint, and `e2e-bi-*-bp`, a service blueprint with a `type` enum and an `owner` relation to
  the team), one conversion mapping (`e2e-bi-*-map`: Component -> service, Group -> team) and the
  two entities the import creates (`e2e-bi-*-team`, `e2e-bi-*-svc`). All deleted at the end
  (entities, mapping, blueprints).

## Scenario: a Backstage catalog converts into Port entities: preview, check, import and re-import

1. The admin signs in and seeds the two blueprints and the mapping via the API.
   - *Expected*: every creation succeeds (`201`).
2. They open **Import** from the sidebar's Ontology section and switch the **Import source** to
   "Backstage catalog (YAML)".
   - *Expected*: the JSON textarea is replaced by the YAML textarea.
3. They paste a two-document catalog (a Group and a Component owned by it) and pick the mapping.
   - *Expected*: "2 documents ready to import".
4. They press **Convert**.
   - *Expected*: `POST …/entity-documents` answers `200`; the conversion table maps
     `Group:default/<team>` to `<team blueprint> / <team>` and `Component:default/<svc>` to
     `<service blueprint> / <svc>`. Nothing is stored.
5. They press **Check**.
   - *Expected*: the page converts again, then `POST /entities/import/check` answers `200`; both
     rows read "Would be created".
6. They press **Import**.
   - *Expected*: nothing is written yet — a confirm dialog ("Import into Port") opens, converts
     afresh and dry-runs: both rows read "Would be created" and no Merge-off warning shows.
7. They press **Confirm import**.
   - *Expected*: the dialog converts once more, then `POST /entities/import` answers `200`; both
     rows read "Created". After closing, reading the service back through the API shows its `owner`
     relation pointing at the imported team.
8. They press **Import** again, check the dialog and confirm.
   - *Expected*: the dialog predicts "Would be updated" for both, and after Confirm both read
     "Updated" — replace-existing is implicit for a conversion import.

## Not covered here (and why)

- The merge-off warning, a chunk failing part-way (stored rows and the stopped-after notice), the
  stale-mapping block, 409 answers, parse errors, disabled inputs and fetch-from-URL —
  pinned by `BackstageCatalogImport.test.tsx`; the conversion engine by the server's
  `ConversionImportRoutesTest`/`SampleImportConversionTest`.
