# Hierarchy

- **Spec**: [tests/hierarchy.spec.ts](../tests/hierarchy.spec.ts)
- **Actors**: the seed administrator (`admin@toadie.local`)
- **Owns** (exclusive server-side state): its three throwaway files (`e2e-hier-*`, unique per
  attempt) in this run's `hierarchy` namespace (minted by global-setup, removed by teardown) —
  all deleted by the end. The Hierarchy page itself is a read view over the shared workspace;
  the namespace filter scopes every assertion to the run namespace, so parallel specs never
  collide.

## Scenario: the hierarchy nests the containment chain and carries the file operations

1. The admin signs in and creates, through the editor, a System, a Component in that System,
   and a second Component that is both in the System and a subcomponent of the first.
   - *Expected*: all three saves succeed (references resolve against the just-stored files).
2. They open **Hierarchy** (the root nav entry), expand the filter panel (the Files list's
   full filter set lives here too), and scope the namespace filter to the run namespace.
   - *Expected*: the System renders as a root with the Component nested under it and the
     subcomponent nested under the Component — most-specific placement: the subcomponent sits
     under its parent component, NOT directly under the System.
3. They add the **Type** filter `service`, then swap it for **Lifecycle** `experimental`,
   then clear both.
   - *Expected*: with Type `service` the components stay but the type-less System does NOT —
     the filters select which entities are SHOWN, so a hidden parent takes its containment
     edge with it and the components sit flat at the root; with Lifecycle `experimental`
     nothing matches at all and the tree empties; cleared, the chain returns.
4. They collapse the parent Component's branch, reopen it, then collapse the System's branch.
   - *Expected*: collapsing the Component hides only the subcomponent; collapsing the System
     hides the whole chain; expanding restores it.
5. They download the subcomponent's YAML from its row's **Operations** menu.
   - *Expected*: a `catalog-info.yaml` download starts — the same operation surface as the
     Files list.
6. They delete the System from its tree row (through the confirm modal).
   - *Expected*: the System stays visible as a dimmed MISSING placeholder (no Operations
     menu) with the components still nested under it — deletions create dangling references
     by design, and the tree shows them.
7. Cleanup: the subcomponent and the component are deleted from their tree rows.
   - *Expected*: with its last children gone, the placeholder disappears too; nothing owned
     by the spec remains.

## Not covered here (and why)

- **The placement/dedup rules** (most-specific priority, parent/children + members/memberOf
  dedupe, multi-group Users, cycle promotion, non-containment virtual nodes excluded) —
  pinned by the pure-util unit suite (`utils/hierarchy.test.ts`).
- **Expand all / Collapse all, error and empty states** — pinned by `Hierarchy.test.tsx`.
- **Edit from a tree row** — the same `RouterLink` as the Files list's menu item, covered by
  the shared `CatalogFileOperations` component through the Files journeys.
