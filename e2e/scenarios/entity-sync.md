# Entity source references & the sync modal (2.9.0)

- **Spec**: [tests/entity-sync.spec.ts](../tests/entity-sync.spec.ts)
- **Actors**: the seed administrator (`admin@toadie.local`) — an ordinary user here (entities
  carry no admin gate)
- **Owns** (exclusive server-side state): one throwaway blueprint named `e2e-esync-…` and one
  entity of it, created and deleted within the first scenario; the second scenario owns nothing

## Scenario: a source set in the entity editor turns on the Last sync column and the sync modal, which refuses a loopback source

1. The admin signs in and, via the API, creates a minimal throwaway blueprint (one string
   property) and one entity of it — **without** a source URL.
2. They open that blueprint's Entities list (`/entities?blueprint=…`).
   - *Expected*: the row's **Last sync** column reads **No source**, and its Operations menu
     offers **Sync from source** greyed out — offered-and-unavailable, never absent.
3. They open the entity in the editor and fill the **Source URL** field in the Source
   fieldset with `https://127.0.0.1/entity.json` (statically valid — absolute https; hosts
   are only probed at fetch time), then save.
   - *Expected*: the save goes through; back on the list the column reads **Never synced**.
4. They pick **Sync from source** from the row's Operations menu.
   - *Expected*: the sync modal opens titled "Sync from source — `<identifier>`", the
     server-side fetch of the loopback URL is refused by the SSRF guard, the modal shows the
     fixed "must be a public https address" error, and the **Overwrite stored copy** button
     stays disabled — nothing can be overwritten.
5. They cancel the modal.
   - *Expected*: the dialog closes.

## Scenario: fetching a private URL on the ontology import page is refused with the public-https message

1. The admin signs in and opens the ontology import page (`/ontology/import`).
2. They paste `https://127.0.0.1/entities.json` into the **Fetch from URL** field and click
   **Fetch**.
   - *Expected*: the server's SSRF guard answers a uniform `400`; the page shows the fixed
     "must be a public https address" error.

## Not covered here (and why)

- **The fetch→diff→overwrite happy path** — deliberately not e2e: the SSRF guard blocks
  loopback fixture servers and the suite runs without external network (the `source-sync.md`/
  `url-import.md` precedent). The server half (overwrite, sync-state stamping, the strict
  never-waives refusal, identity conflicts, the import-as-sync path) is pinned by
  `EntitySyncTest.kt` and `UrlFetchTest`'s entity fetch-route case against a local fixture
  server, and the modal's diff/side-badges/confirm flow by `SyncEntityModal.test.tsx` with
  stubbed transport.
- **Sorting by time-since-sync and the kebab's disabled/enabled wiring in detail** —
  `EntitySyncTest.kt` (server ordering) and `Entities.test.tsx` (the header/kebab wiring).
- **Import-from-URL setting the reference automatically and stamping every stored row synced** —
  the URL fetch itself cannot run here (above); the request wiring is unit-tested in
  `ImportOntology.test.tsx` and the server behavior in `EntitySyncTest.kt`.
