import { type ReactNode } from "react";
import { Group } from "@mantine/core";
import type { useEntityGraphFilterState } from "../hooks/useEntityGraphFilterState";
import EntityGraphFilterControls from "./EntityGraphFilterControls";
import FilterPanel from "./FilterPanel";

/**
 * The Entity graph/hierarchy pages' toolbar (the `CatalogToolbar.tsx` shape, scaled down to
 * the two-slot filter set): the Filters toggle + active count opens the blueprint/search
 * controls, the entity query bar (phase 7, v2.0.0) rides its own full-width row right below,
 * and a view's own secondary controls (the Entity graph's relation chips/layout, the Entity
 * hierarchy's expand/collapse) ride `children` as a third row.
 */
export default function EntityGraphToolbar({
  viewKey,
  filters,
  query,
  children,
}: {
  /** The per-view storage key ("entityGraph" | "entityHierarchy"). */
  viewKey: string;
  filters: ReturnType<typeof useEntityGraphFilterState>;
  /** The entity query bar (`EntityQueryBar`) — its own row, since a compact `Group` meant for
   *  chips/buttons cannot host an editor + diagnostics list. */
  query?: ReactNode;
  children?: ReactNode;
}) {
  return (
    <>
      <FilterPanel activeFilterCount={filters.activeFilterCount} storageKey={viewKey}>
        <EntityGraphFilterControls controls={filters.controls} />
      </FilterPanel>
      {query}
      {children && (
        <Group gap="sm" wrap="wrap" align="center">
          {children}
        </Group>
      )}
    </>
  );
}
