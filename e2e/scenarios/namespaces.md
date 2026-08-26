# Namespaces dictionary

- **Spec**: [tests/namespaces.spec.ts](../tests/namespaces.spec.ts)
- **Actors**: the seed administrator (`admin@toadie.local`); one throwaway regular user created
  through the UI (email carries the `e2e` marker)
- **Owns** (exclusive server-side state): its two throwaway namespace entries (`e2e-ns-*`,
  unique per run) and its throwaway user — all removed at the end. The namespaces dictionary
  itself is SHARED append-only state: the spec never replaces or reorders entries it did not
  create (its reorder swaps its own two adjacent rows), so the volume's curated list — the
  seeded `default` included — survives.

## Scenario: admin curates the ordered namespaces list; a regular user reads it only

1. The admin signs in and opens **Namespaces** from the nav.
   - *Expected*: the document editor renders with **Save** disabled (nothing dirty yet), and
     exactly one row carries the checked **Default** radio (the flagged entry blank
     catalog-file namespaces resolve to).
2. They add a row holding a grammar-violating value (`Bad_Value`) and try to save.
   - *Expected*: the row is flagged inline ("Must be 1–63 lowercase alphanumeric characters…");
     no request reaches the server.
3. They correct the row to a unique `e2e-ns-a-…` value, add a second `e2e-ns-b-…` row, and save.
   - *Expected*: the save PUTs the whole document and re-seeds from the server (Save returns
     to disabled); both entries appear, `a` before `b` — payload order is the stored order.
4. They move the `b` row up one position and save; a fresh page load shows `b` before `a`.
5. A throwaway regular user (created via the one-time reveal flow) signs in and opens
   **Namespaces** from the nav.
   - *Expected*: the read-only numbered list (the `b` entry visible) with exactly one
     **Default** badge, and no **Add namespace** / **Save** — editing is ADMIN-only.
6. Back as the admin: both throwaway entries are removed in one save, and the throwaway user
   is deleted through the Users list's confirm modal.

## Not covered here (and why)

- **The whole-document replace semantics** (soft-delete-first reconcile, foreign/duplicate id
  400s, the swap-in-one-save 409, audit counts) — pinned exhaustively by the server suite
  (`DictionaryTest`); the journey exercises the editor's real add/reorder/remove/save cycle.
- **The 403 on a non-admin PUT** — the read-only branch never renders a save path; the guard
  itself is server-pinned (`DictionaryTest`).
- **Flipping which entry is the DEFAULT** — deliberately never done here: the flag is shared
  run-state (parallel specs create blank-namespace files that resolve against it). The flip —
  radio semantics, the exactly-one 400s, and blank-namespace resolution — is pinned by
  `DictionaryTest`/`CatalogFileTest` and the `Namespaces.test.tsx` unit suite.
