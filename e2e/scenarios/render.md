# Rendering the files together (the relationship graph)

- **Spec**: [tests/render.spec.ts](../tests/render.spec.ts)
- **Actors**: the seed administrator (`admin@toadie.local`) — an ordinary user here (shared
  workspace)
- **Owns** (exclusive server-side state): two throwaway files in a throwaway unique NAMESPACE
  (`e2e-rns-…`, this run's namespace, registered in the namespaces dictionary by
  global-setup), deleted at the end — the graph's namespace filter isolates the assertions from
  every other file in the shared database

## Scenario: the graph renders stored, missing, and external nodes for a namespace

1. The admin signs in and creates two components in the run namespace (picked from the
   Namespace select): B, then A with
   **Depends on** entries for `component:<ns>/<B>` (resolvable) and `component:<ns>/<ghost>`
   (missing).
2. They open the **Render** page and filter by the namespace.
   - *Expected*: nodes for A and B (stored), the ghost (missing), and the shared owner group
     (external) are all drawn on the canvas.
3. They toggle the **Owner** relation chip off.
   - *Expected*: the external owner node disappears; the ghost and the stored nodes remain.
4. They delete both throwaway files from the filtered Catalog files list.

## Not covered here (and why)

- **Layout positions, edge labels, family→field mapping, orphan pruning rules** — pure logic
  pinned by `graphLayout.test.ts`; node/edge construction semantics by the server suite
  (`GraphTest`). e2e verifies the real React Flow canvas actually draws them.
- **Stored-node click navigation** — unit-tested (`RenderGraph.test.tsx`); the canvas journey
  here stays read-only.
