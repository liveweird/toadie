# Blueprint source references & the sync modal (2.10.0)

- **Spec**: [tests/blueprint-sync.spec.ts](../tests/blueprint-sync.spec.ts)
- **Fixture**: [fixtures/blueprint-sync-source.json](../fixtures/blueprint-sync-source.json) —
  the remote copy of the second scenario (below), fetched from raw.githubusercontent.com at a
  commit pinned in the spec (`FIXTURE_COMMIT`)
- **Actors**: the seed administrator (`admin@toadie.local`) — sync/fetch are ADMIN-only for
  blueprints (unlike entities), so the whole journey runs as the admin
- **Owns** (exclusive server-side state): one throwaway blueprint named `e2e-bpsync-…` per
  scenario, created and deleted within the scenario. The second scenario's blueprint uses the
  fixture's RESERVED identifier `e2e-bpsync-source` (no other spec may use it)

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

## Scenario: a blueprint synced from a pinned public source shows the diff, overwrites on confirm and stamps Last sync

The success path. The SSRF guard is unchanged and admits no fixture server the suite could host
(loopback and private addresses are always refused), so the journey reads a REAL public https
file the guard already accepts: the fixture above, at
`https://raw.githubusercontent.com/liveweird/toadie/<commit>/e2e/fixtures/blueprint-sync-source.json`.
The fetch happens server-side in the app container, so the app (not the test runner) needs
outbound https; `<commit>` is an immutable commit SHA (`FIXTURE_COMMIT` in the spec), never a
branch, so later edits to the fixture or the sample data cannot change what the journey sees.

1. The admin signs in. Before touching any state the spec asks the app to fetch the pinned URL
   (`POST /api/v1/blueprints/fetch`) and requires the body to equal the fixture file in the
   checkout.
   - *Expected*: 200 with the same document. **Failure modes**: the app cannot reach the host
     (DNS/connect failure, surfacing as the guard's uniform 400 or an upstream-less 502) is
     **skipped locally and FAILS when `CI` is set** (the Mailpit policy of `mfa.spec.ts`); an
     answering upstream that disagrees (HTTP 404 = the pin was never pushed or is mistyped, a
     different body) always fails with a message naming the URL.
2. Any leftover blueprint carrying the reserved identifier (a crashed earlier run's) is deleted,
   then the admin creates, via the API, a blueprint with that identifier whose `sourceUrl` is the
   pinned URL and which differs from the source in a known way: another title
   ("E2E Blueprint Sync (stored)") and one property fewer (only `label`; the source adds
   `source_note`).
3. They open the Blueprints list.
   - *Expected*: the row shows the stored title and **Never synced**.
4. They pick **Sync from source** from the row's Operations menu.
   - *Expected*: the modal opens, fetches through the real guard and shows the diff: the stored
     title line removed (`- …`), the source title line added (`+ …`), the `source_note` property
     added; **Overwrite stored copy** is enabled.
5. They confirm.
   - *Expected*: `POST /api/v1/blueprints/<id>/sync` answers 204 and the modal closes; the row now
     shows the source's title and no longer **Never synced**; the stored blueprint (read via the
     API) has the source's title and both properties, still carries the source URL and a non-zero
     `lastSyncedAt`.
6. They reopen the modal.
   - *Expected*: "Toadie and the source are in sync" and **Overwrite stored copy** disabled; they
     cancel. (The fixture spells out the four empty maps `relations`/`mirrorProperties`/
     `calculationProperties`/`aggregationProperties` on purpose: the stored copy always renders
     them, and a source that omits them is never "in sync" in the modal.)

The blueprint is deleted through the API in `finally`.

## Not covered here (and why)

- **The server half of the happy path** (sync-state stamping, the strict never-waives refusal,
  the `hierarchyRelations` keep-when-absent merge, identity conflicts and renames, the
  import-as-sync path) is pinned by `BlueprintSyncTest.kt` and `UrlFetchTest`'s blueprint
  fetch-route case against a local fixture server, and the modal's side-badges/pre-flight
  refusal by `SyncBlueprintModal.test.tsx` with stubbed transport; the success scenario above
  proves only the real-network click-through.
- **Entity and catalog-file sync success** — still loopback-refusal only (`entity-sync.md`,
  `source-sync.md`); they would need their own pinned public fixtures.
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
