# The command palette

- **Spec**: [tests/command-palette.spec.ts](../tests/command-palette.spec.ts)
- **Actors**: the seed administrator (`admin@toadie.local`)
- **Owns** (exclusive server-side state): one throwaway System named `e2e-palette-…` in the
  `render` run namespace, created through the editor and deleted at the end

## Scenario: the command palette jumps to pages and opens a file by name

1. The admin signs in and creates the throwaway System through the New catalog file form.
   - *Expected*: back on the Files list.
2. They press **Ctrl K** (⌘K on a Mac).
   - *Expected*: the palette opens with its search box focused.
3. They type "Users" and press Enter.
   - *Expected*: the first match is the Users page, and the app navigates there.
4. They click the header's search-looking trigger, then type the throwaway's name.
   - *Expected*: after two characters the palette lists the matching catalog file (a
     server-side search by name), showing its kind and namespace.
5. They click the file result.
   - *Expected*: the file's editor opens with the name filled in.
6. Cleanup: the System is deleted from the Files list through its row menu.

## Not covered here (and why)

- **The admin-only pages hiding from a regular session, the two-character minimum, the
  debounce, Escape** — pinned by `components/CommandPalette.test.tsx`.
