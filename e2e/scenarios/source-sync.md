# Source references & the sync modal

- **Spec**: [tests/source-sync.spec.ts](../tests/source-sync.spec.ts)
- **Actors**: the seed administrator (`admin@toadie.local`) — an ordinary user here (shared
  workspace)
- **Owns** (exclusive server-side state): one throwaway component named `e2e-src-…` in the
  `default` namespace, created and deleted within the scenario

## Scenario: a source reference set after creation clears the report flag and enables the sync modal

1. The admin signs in and creates a minimal component (type `service`, lifecycle
   `production`, owner `group:default/platform`) **without** a source file URL.
2. They open the Errors report (`/errors`) and filter by the file's name.
   - *Expected*: the file is flagged **No source reference** (the report-only finding —
     the reference is optional on writes, never a save blocker).
3. They open the Files list (`/files`) and filter by the name.
   - *Expected*: the **Last sync** column reads **No source**, and the row's Operations
     menu offers Edit/Download/Delete but no **Sync from repo** yet.
4. They open the file in the editor and fill the **Source file URL** in the new Source
   section with `https://127.0.0.1/catalog-info.yaml` (statically valid — absolute https;
   hosts are only probed at fetch time), then save.
   - *Expected*: the save goes through; back on the filtered list the column reads
     **Never synced**, and the Errors report no longer flags the file.
5. They pick **Sync from repo** from the row's Operations menu.
   - *Expected*: the sync modal opens, the server-side fetch of the loopback URL is refused
     by the SSRF guard, the modal shows the fixed "must be a public https address" error,
     and the **Overwrite stored copy** button stays disabled — nothing can be overwritten.
6. They cancel the modal and delete the file, confirming in the dialog.
   - *Expected*: a fresh filtered load shows "No catalog files".

## Not covered here (and why)

- **The fetch→diff→overwrite happy path** — deliberately not e2e: the SSRF guard blocks
  loopback fixture servers and the suite runs without external network (the `url-import.md`
  precedent). The server half (overwrite, sync-state stamping, waived findings, identity
  conflicts, the import-as-sync path) is pinned by `SyncTest.kt` against the real DB, and
  the modal's diff/side-badges/confirm flow by `SyncCatalogFileModal.test.tsx` with stubbed
  transport.
- **Sorting by time-since-sync** — `SyncTest.kt` (server ordering) and
  `CatalogFiles.test.tsx` (the header wiring).
- **Import-from-URL setting the reference automatically** — the URL fetch itself cannot run
  here (above); the request wiring is unit-tested in `ImportCatalogFiles.test.tsx` and the
  server behavior in `SyncTest.kt`.
