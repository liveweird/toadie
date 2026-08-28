# Rendering the files together (the relationship graph)

- **Spec**: [tests/render.spec.ts](../tests/render.spec.ts)
- **Actors**: the seed administrator (`admin@toadie.local`) — an ordinary user here (shared
  workspace)
- **Owns** (exclusive server-side state): three throwaway files in a throwaway unique
  NAMESPACE (`e2e-rns-…`, this run's namespace, registered in the namespaces dictionary by
  global-setup) — one deleted mid-journey to create the missing node, the other two deleted
  at the end; the graph's namespace filter isolates the assertions from every other file in
  the shared database

## Scenario: the graph renders stored and missing nodes for a namespace

1. The admin signs in and creates three components in the run namespace (picked from the
   Namespace select): B, the doomed ghost, then A with **Depends on** entries for both —
   saves enforce reference resolution, so targets exist first.
2. They delete the ghost from the filtered Catalog files list.
   - *Expected*: deletion is allowed; A's reference to it is now dangling.
3. They open the **Render** page and filter by the namespace.
   - *Expected*: nodes for A and B (stored), the ghost (missing), and the stored shared
     owner group (`platform`) are all drawn on the canvas.
4. They toggle the **Depends on** relation chip off.
   - *Expected*: the orphaned virtual ghost node disappears; the stored nodes (B, the owner
     group) remain — stored nodes are never pruned.
5. They delete the two remaining throwaway files from the filtered Catalog files list.

## Not covered here (and why)

- **Layout positions, edge labels, family→field mapping, orphan pruning rules** — pure logic
  pinned by `graphLayout.test.ts`; node/edge construction semantics by the server suite
  (`GraphTest`). e2e verifies the real React Flow canvas actually draws them.
- **Stored-node click navigation** — unit-tested (`RenderGraph.test.tsx`); the canvas journey
  here stays read-only.
