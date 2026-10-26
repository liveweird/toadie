# The YAML round-trip (import + export)

- **Spec**: [tests/round-trip.spec.ts](../tests/round-trip.spec.ts)
- **Actors**: the seed administrator (`admin@toadie.local`) — an ordinary user here (shared
  workspace)
- **Owns** (exclusive server-side state): two throwaway files (a Component and the Group that
  owns it) in this run's throwaway namespace (`e2e-rtns-…`, registered in the namespaces
  dictionary by global-setup — an undefined namespace would import as INVALID), deleted at
  the end;
  namespace-/name-anchored assertions keep the shared database out of the picture

## Scenario: two pasted documents import, export as one YAML, and re-import as conflicts

1. The admin signs in and opens the import page (`/files/import`). They paste a
   two-document `catalog-info.yaml` — a Component and its owning Group, separated by `---`,
   both in the throwaway namespace.
   - *Expected*: the live parse summary reads "2 documents ready to import".
2. They click **Check** first (the import's dry-run).
   - *Expected*: the same per-row report, predicted — both rows **Would be created**, the
     summary reads "2 of 2 documents would import.", and nothing is stored.
3. They click **Import** on the same batch.
   - *Expected*: the results table reports both rows **Created** and the summary reads
     "Imported 2 of 2 documents.".
4. On the Files list, filtered to the namespace, both files appear.
5. They pick **Export as YAML** from each row's **Operations** menu and stitch the two
   downloads into one `---`-separated stream (whole-workspace export is API-only).
   - *Expected*: the browser downloads `catalog-info.yaml`; the file contains both entity
     names and a `---` document separator.
6. Back on the import page they paste the exported text verbatim and import again.
   - *Expected*: identity survived the trip intact — both rows report **Already exists**
     ("Imported 0 of 2 documents."); nothing is overwritten (report & skip).
7. They delete both files.

## Scenario: a changed catalog-info.yaml re-imports as Updated with Replace existing files, and the history records it

- *Owns*: one more throwaway Component (`e2e-rp-comp-…`) in the same run namespace, deleted at
  the end.

1. The admin opens the import page and pastes a one-document `catalog-info.yaml` (a Component
   titled "First title") in the throwaway namespace, then clicks **Import**.
   - *Expected*: the row reports **Created**.
2. They change the title to "Second title" and import again with the **Replace existing files**
   switch still OFF.
   - *Expected*: the row reports **Already exists** — nothing is overwritten (the default).
3. They turn the switch ON and click **Check**.
   - *Expected*: the dry-run predicts **Would be updated** ("1 of 1 documents would import.").
4. They click **Import**.
   - *Expected*: the row reports **Updated** and the summary reads "Imported 1 of 1 documents.".
5. They click **Import** once more with the identical document.
   - *Expected*: the row reports **Unchanged** ("Imported 0 of 1 documents; 1 already matched.") —
     the ordinary replace's no-op.
6. They follow the row's link to the file's editor.
   - *Expected*: the History section shows "File created by import." and exactly one "File
     updated: Title." entry with "Title: First title → Second title" — the file was replaced in
     place (its history is continuous), and the unchanged pass recorded nothing.
7. They delete the file.

## Not covered here (and why)

- **`UPDATED_WITH_FINDINGS`, the `sourceUrl` keep/re-stamp rule, in-batch duplicates,
  soft-deleted identities, audit fields, revision bump, and rollback on a history failure** —
  server-tested (`CatalogImportReplaceTest`, `CatalogFileAtomicHistoryTest`); the page's
  rendering of the new statuses is unit-tested (`ImportCatalogFiles.test.tsx`).
- **Per-document INVALID/ERROR rows, the 200-document cap, and per-file independence** —
  server-tested exhaustively (`RoundTripTest`); the import page's rendering of those statuses
  is unit-tested (`ImportCatalogFiles.test.tsx`).
- **The strict parser's rejection rules (unknown keys, type mismatches, per-document YAML
  errors)** — pure client logic, unit-tested in `catalogImport.test.ts`.
- **Export ordering and soft-delete exclusion** — pinned by the server suite
  (`RoundTripTest`).
