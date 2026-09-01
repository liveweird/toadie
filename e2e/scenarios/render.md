# Rendering the files together (the relationship graph)

- **Spec**: [tests/render.spec.ts](../tests/render.spec.ts)
- **Actors**: the seed administrator (`admin@toadie.local`) — an ordinary user here (shared
  workspace)
- **Owns** (exclusive server-side state): four throwaway files sharing one per-attempt
  unique NAME stem, spread over this run's two throwaway NAMESPACES (`e2e-rns-…` and
  `e2e-rns2-…`, registered in the namespaces dictionary by global-setup) — a System and three
  components, one of the components deleted mid-journey to create the missing node, the other
  three files deleted at the end; the graph's NAME filter isolates the assertions from every
  other file in the shared database, and the second namespace is what puts two namespace
  frames on one canvas. Also **the seed admin's graph layout document** (the per-user V19 row
  behind the Auto/Manual modes, which since V24 also holds the collapsed node ids) — only
  this spec touches it, and the journey ends by restoring it to the pristine default (Expand
  all + Reset + Auto)

## Scenario: the graph renders stored and missing nodes for a namespace

1. The admin signs in and creates four files sharing one name stem (namespaces picked from
   the Namespace select): a System in the first run namespace, then three components — B in
   the second run namespace, the doomed ghost in the first, then A — also in the first, **in
   the System** — with **Depends on** entries for both; saves enforce reference resolution,
   so targets exist first.
2. They delete the ghost from the filtered Files list.
   - *Expected*: deletion is allowed; A's reference to it is now dangling.
3. They open the **Graph** page and filter by the shared name stem.
   - *Expected*: nodes for the System, A and B (stored) and the ghost (missing) are drawn.
     The shared owner group (`platform`) is NOT — the filters select which entities are
     shown, and it is not one of them; the ghost is judged on the same identity, so its
     matching name lets it in. Each node reads as its name plus its `spec.type` (A and B are
     `service`) — the namespace is NOT on the node face; it moved into the name's hover
     tooltip. Because two namespaces are on screen (the System, A and the ghost in one, B in
     the other), each is drawn inside its own labelled frame.
4. They toggle the **Depends on** relation chip off.
   - *Expected*: the orphaned missing ghost node disappears — its only edge was what made it
     knowable — while the stored nodes A and B remain: a relation chip governs which relations
     are drawn, never which entities are shown.
5. They toggle **Depends on** back on and **collapse** the System from the toggle on its
   node.
   - *Expected*: only the System offered a fold toggle (A, B and the ghost have nothing
     beneath them). A disappears — it belongs to the System — and the System's toggle now
     reads as an **Expand** pill counting one hidden entity. A's two Depends-on relations are
     NOT lost: two **dashed** `dependsOn` edges now leave the System, one to B and one to the
     ghost, standing in for the hidden A.
6. They reload the page.
   - *Expected*: the System is still collapsed and A still hidden — the collapsed set lives
     in the per-user layout document, server-side, not in the browser.
7. They click **Expand all**.
   - *Expected*: A is back with its own solid edges, no dashed edge remains, and the Expand
     all button disappears — nothing is collapsed any more.
8. They switch the layout to **Manual** and drag node B across the canvas.
   - *Expected*: the canvas stays painted while dragging (nodes never flicker away
     mid-gesture); the node moves and stays where dropped (the position save fires); the
     drag does NOT open B's editor — the page stays on the graph.
9. They reload the page.
   - *Expected*: the layout is still Manual and B still sits at the dragged position — the
     layout document persisted server-side per user, not in the browser.
10. They click **Reset layout**, then switch back to **Auto**.
    - *Expected*: B returns to a computed auto-layout spot; the Reset button disappears in
      Auto mode — the admin's layout document is back to the pristine default.
11. They delete the three remaining throwaway files from the filtered Files list.

## Not covered here (and why)

- **Layout positions, edge labels, family→field mapping, orphan pruning rules** — pure logic
  pinned by `graphLayout.test.ts`; node/edge construction semantics by the server suite
  (`GraphTest`); the layout endpoints' guard/validation matrix (the collapsed list's
  included) by `GraphLayoutTest`.
- **The fold rules** — which nodes a collapse hides, how a hidden node's relations
  re-attribute and merge (`dependsOn ×3`), the multi-parent User, nested collapses, stale
  ids — pure logic pinned by `graphFold.test.ts`; the page-level wiring (immediate save,
  Expand all clearing stale ids, Reset layout keeping the list) by `RenderGraph.test.tsx`.
- **Stored-node click navigation** — unit-tested (`RenderGraph.test.tsx`); here the canvas
  is exercised through the manual-layout journey instead (the drag deliberately must NOT
  navigate).
