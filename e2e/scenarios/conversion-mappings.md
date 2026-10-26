# Conversion mappings registry (conversion phase 5a)

- **Spec**: [tests/conversion-mappings.spec.ts](../tests/conversion-mappings.spec.ts)
- **Actors**: the seed administrator (`admin@toadie.local`)
- **Owns** (exclusive server-side state): one throwaway conversion mapping named `e2e-cm-*`
  (created, renamed to `e2e-cm-*-renamed`, deleted by the journey; a `finally` deletes either name
  by id if an aborted run left it behind). The registry is shared run-state (unique names, at most
  20 active mappings) — this spec never touches a foreign mapping.

## Scenario: admin curates a conversion mapping: a bad document is refused with findings, a valid one is created, renamed and deleted

1. The admin signs in (a fresh context lands in the Port world) and opens **Conversion mappings**
   from the sidebar's Ontology section.
   - *Expected*: the page heading, the **New mapping** link and the **Errors report** link are visible.
2. They open **New mapping**, name it, and submit a document whose only pair names a blueprint that
   does not exist.
   - *Expected*: the create is refused (`400`); the editor stays on `/ontology/mappings/new`; the
     aside shows "The mapping was not saved — it has blocking findings", the localized
     "Unknown blueprint" code and the JSON pointer `/pairs/0/blueprint`. Nothing is stored.
3. They replace the document with the minimal valid one (`pairs: []`) and submit again.
   - *Expected*: `201`, the list opens, and the mapping's row carries the **Valid** status chip.
4. They open the row's editor through its name link, rename the mapping and save.
   - *Expected*: `204`; the list shows the renamed row.
5. They delete the mapping from its row and confirm in the dialog.
   - *Expected*: `204`; the row is gone.

## Not covered here (and why)

- The regular user's read-only view and the editor-route bounce — pinned by the page tests
  (`ConversionMappings.test.tsx`, `CreateConversionMapping.test.tsx`).
- Stale mappings (blocking findings that appear after a blueprint edit) and drift chips — needs
  a second writer on the blueprint registry; covered by the page tests and the server's
  `ConversionMappingTest`.
