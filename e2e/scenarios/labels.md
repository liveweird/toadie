# Label registry

- **Spec**: [tests/labels.spec.ts](../tests/labels.spec.ts)
- **Actors**: the seed administrator (`admin@toadie.local`); one throwaway regular user created
  through the UI (email carries the `e2e` marker)
- **Owns** (exclusive server-side state): its one throwaway label (`e2e-lbl-*`, unique per
  attempt), the one Component file carrying it, and its throwaway user — all removed at the
  end. The label registry itself is SHARED state and **this spec is its only in-run writer**:
  it never edits or deletes entries it did not create, so the volume's curated labels survive.

## Scenario: admin curates the label registry; a regular user reads it; the editor enforces it

1. The admin signs in and opens **Labels** from the nav.
   - *Expected*: the registry table renders with the **New label** action (ADMIN-only).
2. They open the New-label modal and submit it empty.
   - *Expected*: the three field errors render inline (key grammar, "Add at least one allowed
     value", "Pick at least one kind"); no request reaches the server.
3. They fill the unique `e2e-lbl-…` key, two allowed values (`backend`, `frontend`), pick the
   **Component** kind, and save.
   - *Expected*: the modal POSTs and closes; the table row shows the key with its value and
     kind badges.
4. They edit the label, adding a third allowed value (`edge`), and save.
   - *Expected*: the modal PUTs to the label's id; the new value badge appears in the row.
5. A throwaway regular user (created via the one-time reveal flow) signs in and opens
   **Labels** from the nav.
   - *Expected*: the same table read-only — the label visible, but no **New label**, edit, or
     delete affordances (curation is ADMIN-only).
6. Back as the admin: a new Component file is created in the editor with a label row — the
   key picked from the registry-constrained Select (only labels allowed for the kind are
   offered), the value from the label's closed list.
   - *Expected*: the YAML preview shows the `key: backend` line and the create succeeds.
7. Cleanup: the file is deleted from the filtered list, the label from the Labels page (both
   through their confirm modals), and the throwaway user through the Users list.

## Not covered here (and why)

- **The server-side enforcement 400s** (unregistered key, kind-not-allowed, value-not-allowed,
  the strict no-grandfathering resave block, import INVALID rows) — pinned by the server suite
  (`CatalogFileTest`, `RoundTripTest`); the journey proves the pickers make those states
  unreachable from the editor.
- **The registry payload rules** (grammar/duplicate/cap 400s, the case-insensitive key 409,
  soft-delete freeing the key, kind canonicalization, audit events) — pinned by `LabelTest`.
- **The 403 on a non-admin mutation** — the read-only branch never renders a mutation path;
  the guard itself is server-pinned (`LabelTest`).
- **The stale-stored-label fallback rendering** (a stored file whose label was since removed
  still displays its rows) — pinned by the `CreateCatalogFile`/`EditCatalogFile` unit suites.
