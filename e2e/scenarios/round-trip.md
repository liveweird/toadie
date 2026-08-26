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

1. The admin signs in and opens the import page (`/catalog-files/import`). They paste a
   two-document `catalog-info.yaml` — a Component and its owning Group, separated by `---`,
   both in the throwaway namespace.
   - *Expected*: the live parse summary reads "2 documents ready to import".
2. They click **Import**.
   - *Expected*: the results table reports both rows **Created** and the summary reads
     "Imported 2 of 2 documents.".
3. On the Catalog files list, filtered to the namespace, both files appear.
4. They click **Export YAML** while the namespace filter is active.
   - *Expected*: the browser downloads `catalog-info.yaml`; the file contains both entity
     names and a `---` document separator.
5. Back on the import page they paste the exported text verbatim and import again.
   - *Expected*: identity survived the trip intact — both rows report **Already exists**
     ("Imported 0 of 2 documents."); nothing is overwritten (report & skip).
6. They delete both files.

## Not covered here (and why)

- **Per-document INVALID/ERROR rows, the 200-document cap, and per-file independence** —
  server-tested exhaustively (`RoundTripTest`); the import page's rendering of those statuses
  is unit-tested (`ImportCatalogFiles.test.tsx`).
- **The strict parser's rejection rules (unknown keys, type mismatches, per-document YAML
  errors)** — pure client logic, unit-tested in `catalogImport.test.ts`.
- **Export ordering and soft-delete exclusion** — pinned by the server suite
  (`RoundTripTest`).
