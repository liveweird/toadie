# Blueprint source references & the sync modal (2.10.0)

- **Spec**: [tests/blueprint-sync.spec.ts](../tests/blueprint-sync.spec.ts)
- **Actors**: the seed administrator (`admin@toadie.local`) — sync/fetch are ADMIN-only for
  blueprints (unlike entities), so the whole journey runs as the admin
- **Owns** (exclusive server-side state): one throwaway blueprint named `e2e-bpsync-…`, created
  and deleted within the scenario

## Scenario: a source set in the blueprint editor turns on the Last sync column and the sync modal, which refuses a loopback source

1. The admin signs in and, via the API, creates a minimal throwaway blueprint (one string
   property) — **without** a source URL.
2. They open the Blueprints list (`/blueprints`).
   - *Expected*: the row's **Last sync** column reads **No source**, and its Operations menu
     offers **Sync from source** greyed out — offered-and-unavailable, never absent.
3. They open the blueprint in the editor and fill the **Source URL** field in the Source
   fieldset with `https://127.0.0.1/blueprint.json` (statically valid — absolute https; hosts
   are only probed at fetch time), then save.
   - *Expected*: the save goes through; back on the list the column reads **Never synced**.
4. They pick **Sync from source** from the row's Operations menu.
   - *Expected*: the sync modal opens titled "Sync from source — `<identifier>`", the
     server-side fetch of the loopback URL is refused by the SSRF guard, the modal shows the
     fixed "must be a public https address" error, and the **Overwrite stored copy** button
     stays disabled — nothing can be overwritten.
5. They cancel the modal.
   - *Expected*: the dialog closes.

## Not covered here (and why)

- **The fetch→diff→overwrite happy path** — deliberately not e2e: the SSRF guard blocks
  loopback fixture servers and the suite runs without external network (the `entity-sync.md`/
  `source-sync.md` precedent). The server half (overwrite, sync-state stamping, the strict
  never-waives refusal, the `hierarchyRelations` keep-when-absent merge, identity conflicts, the
  import-as-sync path) is pinned by `BlueprintSyncTest.kt` and `UrlFetchTest`'s blueprint
  fetch-route case against a local fixture server, and the modal's diff/side-badges/confirm flow
  by `SyncBlueprintModal.test.tsx` with stubbed transport.
- **The ADMIN-only fetch/sync guard in detail (a USER's 403)** — server-pinned by
  `BlueprintSyncTest.kt`; this journey only ever runs as an admin, so a non-admin path never
  renders here.
- **Fetching a private URL on the ontology import page** — already covered by
  `entity-sync.spec.ts`'s second, unowned journey (the fetch itself is shared machinery, and
  blueprints reuse the entities fetch on that page); not duplicated here.
- **Sorting by time-since-sync and the kebab's disabled/enabled wiring in detail** —
  `BlueprintSyncTest.kt` (server ordering) and `Blueprints.test.tsx` (the header/kebab wiring).
- **Import-from-URL setting the reference automatically and stamping every stored row synced** —
  the URL fetch itself cannot run here (above); the request wiring is unit-tested in
  `ImportOntology.test.tsx` and the server behavior in `BlueprintSyncTest.kt`.
