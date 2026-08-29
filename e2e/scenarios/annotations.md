# Annotation keys

- **Spec**: [tests/annotations.spec.ts](../tests/annotations.spec.ts)
- **Actors**: the seed administrator (`admin@toadie.local`); one throwaway regular user created
  through the UI (email carries the `e2e` marker)
- **Owns** (exclusive server-side state): its one throwaway annotation key (`e2e-ann-*`,
  unique per attempt), the one Component file carrying the annotation, and its throwaway
  user — all removed at the end. The annotation-key registry is SHARED state and **this
  spec is its only in-run writer**: it never edits or deletes keys it did not create, so
  the volume's curated keys survive.

## Scenario: admin curates the annotation keys; a regular user reads them; the editor enforces them

1. The admin signs in and opens **Annotations** from the nav.
   - *Expected*: the key table renders with the **New annotation key** action (ADMIN-only).
2. They open the New-key modal and submit it empty.
   - *Expected*: the field errors render inline (the key-grammar rule, "Pick at least one
     kind"); no request reaches the server.
3. They fill the unique `e2e-ann-…` key, pick the **Component** kind, and save.
   - *Expected*: the modal POSTs and closes; the table row shows the key with its kind badge.
4. They edit the key, adding the **API** kind, and save.
   - *Expected*: the modal PUTs to the key's id; the new kind badge appears in the row.
5. A throwaway regular user (created via the one-time reveal flow) signs in and opens
   **Annotations** from the nav.
   - *Expected*: the same table read-only — the key visible, but no **New annotation key**,
     edit, or delete affordances (curation is ADMIN-only).
6. Back as the admin: a new Component file is created in the editor with an annotation whose
   KEY comes from the **registry Select** (only keys allowed for the kind are offered) and
   whose VALUE is free text.
   - *Expected*: the YAML preview shows the key and the create succeeds against the strict
     server check.
7. Cleanup: the file is deleted from the filtered list (via its Operations menu), the key
   from the Annotations page (both through their confirm modals), and the throwaway user
   through the Users list.

## Not covered here (and why)

- **The server-side enforcement 400s** (unregistered key, kind-not-allowed, the strict
  no-grandfathering resave block, import INVALID rows) — pinned by the server suite
  (`CatalogFileTest`); the journey proves the Select makes those states unreachable from
  the editor.
- **The registry payload rules** (key grammar, the server-written-key rejection, the
  case-insensitive key 409, soft-delete freeing the key, kind canonicalization, audit
  events) — pinned by `AnnotationKeyTest`.
- **The 403 on a non-admin mutation** — the read-only branch never renders a mutation path;
  the guard itself is server-pinned (`AnnotationKeyTest`).
- **The stale-stored-key fallback** (a stored file whose key was since removed still
  renders it in its own row's options) — pinned by the editor unit suites.
