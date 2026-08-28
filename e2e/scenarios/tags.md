# Tag categories

- **Spec**: [tests/tags.spec.ts](../tests/tags.spec.ts)
- **Actors**: the seed administrator (`admin@toadie.local`); one throwaway regular user created
  through the UI (email carries the `e2e` marker)
- **Owns** (exclusive server-side state): its one throwaway tag category (`e2e-tagcat-*` name
  with `e2e-tag-*` tags, unique per attempt), the one Component file carrying a tag, and its
  throwaway user — all removed at the end. The tag-category registry is SHARED state and
  **this spec is its only in-run writer**: it never edits or deletes categories it did not
  create, so the volume's curated categories survive.

## Scenario: admin curates the tag categories; a regular user reads them; the editor enforces them

1. The admin signs in and opens **Tags** from the nav.
   - *Expected*: the category table renders with the **New category** action (ADMIN-only).
2. They open the New-category modal and submit it empty.
   - *Expected*: the field errors render inline ("Add at least one tag", "Pick at least one
     kind", the name-length rule); no request reaches the server.
3. They fill the unique `e2e-tagcat-…` name, one tag, pick the **Component** kind, and save.
   - *Expected*: the modal POSTs and closes; the table row shows the category with its tag
     and kind badges.
4. They edit the category, adding a second tag, and save.
   - *Expected*: the modal PUTs to the category's id; the new tag badge appears in the row.
5. A throwaway regular user (created via the one-time reveal flow) signs in and opens
   **Tags** from the nav.
   - *Expected*: the same table read-only — the category visible, but no **New category**,
     edit, or delete affordances (curation is ADMIN-only).
6. Back as the admin: a new Component file is created in the editor with a tag picked from
   the **grouped tags picker** (only categories allowed for the kind are offered, options
   grouped under the category's name).
   - *Expected*: the YAML preview shows the tag's list line and the create succeeds.
7. Cleanup: the file is deleted from the filtered list, the category from the Tags page
   (both through their confirm modals), and the throwaway user through the Users list.

## Not covered here (and why)

- **The server-side enforcement 400s** (unregistered tag, kind-not-allowed, the strict
  no-grandfathering resave block, import INVALID rows) — pinned by the server suite
  (`CatalogFileTest`, `RoundTripTest`); the journey proves the picker makes those states
  unreachable from the editor.
- **The registry payload rules and the one-category-per-tag invariant** (grammar/duplicate/
  cap 400s, the name 409, the cross-category tag 409, move-in-two-saves, soft-delete freeing
  name and tags, kind canonicalization, audit events) — pinned by `TagCategoryTest`.
- **The 403 on a non-admin mutation** — the read-only branch never renders a mutation path;
  the guard itself is server-pinned (`TagCategoryTest`).
- **The stale-stored-tag fallback group** (a stored file whose tag was since removed still
  renders it under a "Not registered" group) — pinned by the editor unit suites.
