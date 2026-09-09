import { type ReactNode } from "react";
import { Group } from "@mantine/core";
import type { useEntityGraphFilterState } from "../hooks/useEntityGraphFilterState";
import EntityGraphFilterControls from "./EntityGraphFilterControls";
import FilterPanel from "./FilterPanel";

/**
 * The Entity graph/hierarchy pages' toolbar (the `CatalogToolbar.tsx` shape, scaled down to
 * the two-slot filter set): the Filters toggle + active count opens the blueprint/search
 * controls, and a view's own secondary controls (the Entity graph's relation chips/layout,
 * the Entity hierarchy's expand/collapse) ride `children` as a second row.
 */
export default function EntityGraphToolbar({
  viewKey,
  filters,
  children,
}: {
  /** The per-view storage key ("entityGraph" | "entityHierarchy"). */
  viewKey: string;
  filters: ReturnType<typeof useEntityGraphFilterState>;
  children?: ReactNode;
}) {
  return (
    <>
      <FilterPanel activeFilterCount={filters.activeFilterCount} storageKey={viewKey}>
        <EntityGraphFilterControls controls={filters.controls} />
      </FilterPanel>
      {children && (
        <Group gap="sm" wrap="wrap" align="center">
          {children}
        </Group>
      )}
    </>
  );
}
