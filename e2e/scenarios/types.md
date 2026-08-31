# Type dictionaries

- **Spec**: [tests/types.spec.ts](../tests/types.spec.ts)
- **Actors**: the seed administrator (`admin@toadie.local`); one throwaway regular user created
  through the UI (email carries the `e2e` marker)
- **Owns** (exclusive server-side state): the one unique `e2e-type-*` value it APPENDS to the
  **Domain** dictionary (removed again at the end — the dictionaries are per-kind singletons
  seeded by V15 and re-curated by V22, so there is no throwaway row to create), the one
  Domain file carrying that type, and its throwaway user. The type registry is SHARED state
  and **this spec is its only in-run writer**: it appends and removes only its own value, so
  the seeded types every other spec's forms rely on survive.

## Scenario: admin curates the type dictionaries; a regular user reads them; the editor enforces them

1. The admin signs in and opens **Types** from the nav.
   - *Expected*: the dictionary table renders the seeded per-kind rows (Component listing
     `service`, one of V22's curated values) with the **New dictionary** action (ADMIN-only).
2. They open the New-dictionary modal and submit it empty.
   - *Expected*: the field errors render inline ("Pick a type-bearing kind", "Add at least
     one type"); no request reaches the server. They cancel out.
3. They edit the **Domain** dictionary, appending the unique `e2e-type-…` value, and save.
   - *Expected*: the modal PUTs to the dictionary's id; the new type badge appears in the
     Domain row.
4. A throwaway regular user (created via the one-time reveal flow) signs in and opens
   **Types** from the nav.
   - *Expected*: the same table read-only — the appended type visible, but no
     **New dictionary**, edit, or delete affordances (curation is ADMIN-only).
5. Back as the admin: a new Domain file is created in the editor with the appended value
   picked from the **Type Select** (only the kind's dictionary values are offered — free
   text is not accepted).
   - *Expected*: the create succeeds against the strict server check.
6. Cleanup: the file is deleted from the filtered list (via its Operations menu), the
   appended type removed from the Domain dictionary again, and the throwaway user deleted
   through the Users list.

## Not covered here (and why)

- **The server-side enforcement 400s** (unregistered type, the no-dictionary block, the
  strict no-grandfathering resave block) — pinned by the server suite (`CatalogFileTest`);
  the journey proves the Select makes those states unreachable from the editor.
- **The registry payload rules and the one-dictionary-per-kind 409** (grammar/duplicate/cap
  400s, the User rejection, kind canonicalization, soft-delete freeing the kind, audit
  events) — pinned by `EntityTypesTest`.
- **The 403 on a non-admin mutation** — the read-only branch never renders a mutation path;
  the guard itself is server-pinned (`EntityTypesTest`).
- **Dictionary deletion** — deleting a seeded dictionary would break every parallel spec's
  catalog forms (shared run-state); the delete path is server-pinned and unit-tested.
