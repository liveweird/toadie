# The quick-view drawer

- **Spec**: [tests/quick-view.spec.ts](../tests/quick-view.spec.ts)
- **Actors**: the seed administrator (`admin@toadie.local`)
- **Owns** (exclusive server-side state): one throwaway System named `e2e-qv-…` in the
  `render` run namespace, created through the editor and deleted at the end

## Scenario: the quick-view drawer opens from a row, survives a reload, and hands over to the editor

1. The admin signs in and creates the throwaway System through the New catalog file form.
   - *Expected*: back on the Files list.
2. They filter the list to the file and pick **Quick view** from its row menu.
   - *Expected*: a drawer opens at the right with the file's name, its summary, its YAML
     (containing the name), and **Sync from source** disabled (no source reference); the
     address now carries `?file=<id>`.
3. They reload the page.
   - *Expected*: the drawer comes back open — it is part of the address.
4. An accessibility scan runs with the drawer open.
   - *Expected*: no WCAG A/AA violations.
5. They click **Edit** in the drawer.
   - *Expected*: the file's editor opens.
6. They go Back.
   - *Expected*: the list returns with the drawer open again.
7. They press Escape.
   - *Expected*: the drawer closes and `file=` leaves the address.
8. Cleanup: the System is deleted from the Files list through its row menu.

## Not covered here (and why)

- **The summary fields, the sourced-file Sync/Export/Overwrite actions, the not-found
  state, junk `?file` values** — pinned by `components/CatalogFileDrawer.test.tsx` and
  `hooks/useQuickViewParam.test.tsx`.
