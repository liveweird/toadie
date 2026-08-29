# Lifecycles dictionary

- **Spec**: [tests/lifecycles.spec.ts](../tests/lifecycles.spec.ts)
- **Actors**: the seed administrator (`admin@toadie.local`); one throwaway regular user created
  through the UI (email carries the `e2e` marker)
- **Owns** (exclusive server-side state): the one unique `e2e-lc-*` value it APPENDS to the
  lifecycles dictionary (removed again at the end — the migration-seeded well-known values
  every other spec's forms rely on are never touched), the one Component file carrying that
  lifecycle, and its throwaway user. The lifecycles document is SHARED single-writer state
  (the whole-document PUT) and **this spec is its only in-run writer**.

## Scenario: admin curates the global lifecycles list; a regular user reads it; the editor enforces it

1. The admin signs in and opens **Lifecycles** from the nav.
   - *Expected*: the document editor renders the seeded values (`production` among them) with
     no default radios — lifecycles have no default concept — and a disabled Save.
2. They add a row with a grammar-violating value and try to save.
   - *Expected*: the inline grammar error renders; no request reaches the server.
3. They correct the row to the unique `e2e-lc-…` value and save.
   - *Expected*: the PUT commits, the editor re-seeds, and the value appears in the list.
4. A throwaway regular user (created via the one-time reveal flow) signs in and opens
   **Lifecycles** from the nav.
   - *Expected*: the same list read-only — numbered rows with the appended value, no add,
     save, or remove affordances (curation is ADMIN-only).
5. Back as the admin: a new Component file is created in the editor with the appended value
   picked from the **Lifecycle Select** (only dictionary values are offered — free text is
   not accepted).
   - *Expected*: the create succeeds against the strict server check.
6. Cleanup: the file is deleted from the filtered list (via its Operations menu), the
   appended lifecycle removed from the dictionary again, and the throwaway user deleted
   through the Users list.

## Not covered here (and why)

- **The server-side enforcement 400s** (unregistered lifecycle, the strict no-grandfathering
  resave block) — pinned by the server suite (`CatalogFileTest`); the journey proves the
  Select makes those states unreachable from the editor.
- **The dictionary payload rules** (folding, duplicates, the shared grammar, the
  default-flag rejection on lifecycles, whole-document replace semantics, soft-delete over
  removal, audit events) — pinned by `DictionaryTest`.
- **Reordering** — the same document machinery as namespaces, exercised there
  (`namespaces.spec.ts`); repeating it here would double-cover one implementation.
- **The 403 on a non-admin mutation** — the read-only branch never renders a mutation path;
  the guard itself is server-pinned (`DictionaryTest`).
