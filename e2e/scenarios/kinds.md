# The editor's entity kinds

- **Spec**: [tests/kinds.spec.ts](../tests/kinds.spec.ts)
- **Actors**: the seed administrator (`admin@toadie.local`) — an ordinary user here (shared
  workspace)
- **Owns** (exclusive server-side state): three throwaway files (a Group, an API, a Component)
  in this run's throwaway namespace (`e2e-kns-…`, registered in the namespaces dictionary
  by global-setup — the form's namespace field offers only defined entries), deleted at the
  end; assertions are
  namespace-/name-anchored so nothing else in the shared database can interfere

## Scenario: a group, an API, and a component owned by the group are created and resolve

1. The admin signs in, opens the new-file form, and switches **Kind** to **Group** — the form
   swaps to the Group fields. They fill a unique name, pick the run namespace from the Namespace select, and type
   `team`, leaving the
   children list empty.
   - *Expected*: the YAML preview shows `children: []` (Backstage requires the list to be
     present, empty is fine); the file saves.
2. They create an **API** in the same namespace, pasting a definition into the required
   Definition textarea.
3. They create a **Component** in the same namespace, picking the owner from the reference
   picker — the just-created group is suggested as its full identity and inserts as
   `group:namespace/name` — and providing the API.
   - *Expected*: the editor's live References panel shows the all-clear — the owner and the
     provided API both resolve against the files just stored.
4. On the Catalog files list, filtered by the namespace, all three rows show their kind badges
   (Group, API, Component).
5. They delete all three files.

## Not covered here (and why)

- **The remaining kinds (System, Domain, Resource, User) and every per-kind rejection rule** —
  exhaustively covered by the server suite (`CatalogFileTest`) and the form unit tests; e2e
  proves one representative multi-kind journey through the real editor.
- **Kind changes on existing files and identity collisions** — server-tested
  (`kind can be changed by an update, and identity collisions still 409`).
