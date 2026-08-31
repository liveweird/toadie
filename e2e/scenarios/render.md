# Rendering the files together (the relationship graph)

- **Spec**: [tests/render.spec.ts](../tests/render.spec.ts)
- **Actors**: the seed administrator (`admin@toadie.local`) — an ordinary user here (shared
  workspace)
- **Owns** (exclusive server-side state): three throwaway files in a throwaway unique
  NAMESPACE (`e2e-rns-…`, this run's namespace, registered in the namespaces dictionary by
  global-setup) — one deleted mid-journey to create the missing node, the other two deleted
  at the end; the graph's namespace filter isolates the assertions from every other file in
  the shared database. Also **the seed admin's graph layout document** (the per-user V19
  row behind the Auto/Manual modes) — only this spec touches it, and the journey ends by
  restoring it to the pristine default (Reset + Auto)

## Scenario: the graph renders stored and missing nodes for a namespace

1. The admin signs in and creates three components in the run namespace (picked from the
   Namespace select): B, the doomed ghost, then A with **Depends on** entries for both —
   saves enforce reference resolution, so targets exist first.
2. They delete the ghost from the filtered Files list.
   - *Expected*: deletion is allowed; A's reference to it is now dangling.
3. They open the **Graph** page and filter by the namespace.
   - *Expected*: nodes for A and B (stored), the ghost (missing), and the stored shared
     owner group (`platform`) are all drawn on the canvas. Each node reads as its name plus
     its `spec.type` (A and B are `service`) — the namespace is NOT on the node face; it
     moved into the name's hover tooltip.
4. They toggle the **Depends on** relation chip off.
   - *Expected*: the orphaned virtual ghost node disappears; the stored nodes (B, the owner
     group) remain — stored nodes are never pruned.
5. They switch the layout to **Manual** and drag node B across the canvas.
   - *Expected*: the canvas stays painted while dragging (nodes never flicker away
     mid-gesture); the node moves and stays where dropped (the position save fires); the
     drag does NOT open B's editor — the page stays on the graph.
6. They reload the page.
   - *Expected*: the layout is still Manual and B still sits at the dragged position — the
     layout document persisted server-side per user, not in the browser.
7. They click **Reset layout**, then switch back to **Auto**.
   - *Expected*: B returns to a computed auto-layout spot; the Reset button disappears in
     Auto mode — the admin's layout document is back to the pristine default.
8. They delete the two remaining throwaway files from the filtered Files list.

## Not covered here (and why)

- **Layout positions, edge labels, family→field mapping, orphan pruning rules** — pure logic
  pinned by `graphLayout.test.ts`; node/edge construction semantics by the server suite
  (`GraphTest`); the layout endpoints' guard/validation matrix by `GraphLayoutTest`.
- **Stored-node click navigation** — unit-tested (`RenderGraph.test.tsx`); here the canvas
  is exercised through the manual-layout journey instead (the drag deliberately must NOT
  navigate).
