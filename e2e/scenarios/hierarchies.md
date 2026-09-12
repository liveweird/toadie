# Hierarchies dictionary

- **Spec**: [tests/hierarchies.spec.ts](../tests/hierarchies.spec.ts)
- **Actors**: the seed administrator (`admin@toadie.local`); one throwaway regular user created
  through the UI (email carries the `e2e` marker)
- **Owns** (exclusive server-side state): the one unique `e2e-hier-*` value it APPENDS to the
  hierarchies dictionary (removed again at the end — the migration-seeded well-known values
  the Entity hierarchy/graph pickers rely on is never touched) and its throwaway user. The hierarchies document is SHARED single-writer state
  (the whole-document PUT) and **this spec is its only in-run writer**.

## Scenario: admin curates the global hierarchies list; a regular user reads it

1. The admin signs in and opens **Hierarchies** from the nav.
   - *Expected*: the document editor renders the seeded values (`composition` among them) with
     no default radios — hierarchies have no default concept — and a disabled Save.
2. They add a row with a grammar-violating value and try to save.
   - *Expected*: the inline grammar error renders; no request reaches the server.
3. They correct the row to the unique `e2e-hier-…` value and save.
   - *Expected*: the PUT commits, the editor re-seeds, and the value appears in the list.
4. A throwaway regular user (created via the one-time reveal flow) signs in and opens
   **Hierarchies** from the nav.
   - *Expected*: the same list read-only — numbered rows with the appended value, no add,
     save, or remove affordances (curation is ADMIN-only).
5. Cleanup: back as the admin, the appended hierarchy is removed from the dictionary again
   and the throwaway user deleted through the Users list.

## Not covered here (and why)

- **The consumers of the dictionary** — the blueprint editor's per-hierarchy relation
  pickers and the Entity hierarchy/graph page selectors — belong to the entity-hierarchy and
  entity-graph journeys; this dictionary has no catalog-editor field (unlike lifecycles).
- **The dictionary payload rules** (folding, duplicates, the shared grammar, the
  default-flag rejection on hierarchies, whole-document replace semantics, soft-delete over
  removal, audit events) — pinned by `DictionaryTest`.
- **Reordering** — the same document machinery as namespaces, exercised there
  (`namespaces.spec.ts`); repeating it here would double-cover one implementation.
- **The 403 on a non-admin mutation** — the read-only branch never renders a mutation path;
  the guard itself is server-pinned (`DictionaryTest`).
