import { type ReactNode } from "react";
import { Group } from "@mantine/core";
import type { useCatalogFileFilterState } from "../hooks/useCatalogFileFilterState";
import CatalogFileFilterControls from "./CatalogFileFilterControls";
import CatalogKindPills from "./CatalogKindPills";
import FilterPanel from "./FilterPanel";
import LensPicker from "./LensPicker";

/**
 * The four catalog views' toolbar (v1.20.0) — ONE row: the Filters toggle (+ active count),
 * the lens picker and its actions, and, pushed right, the visible-kinds pills; the expanded
 * filter panel opens beneath it, and a view's own secondary controls (the Graph's relations
 * and layout, the Hierarchy's expand/collapse, the Errors summary strip) ride `children`
 * as a second row. Rendered through `PageHeader`'s toolbar slot. Nothing about the filter
 * semantics moved: the hook's nine slots, `noKinds`, and the LensPicker's aside contract are
 * exactly what they were — this component only fixes WHERE the pieces sit.
 */
export default function CatalogToolbar({
  viewKey,
  filters,
  children,
}: {
  /** The per-view storage key ("catalogFiles" | "hierarchy" | "renderGraph" | "errors"). */
  viewKey: string;
  filters: ReturnType<typeof useCatalogFileFilterState>;
  children?: ReactNode;
}) {
  return (
    <>
      <FilterPanel
        activeFilterCount={filters.activeFilterCount}
        storageKey={viewKey}
        aside={<LensPicker values={filters.values} controls={filters.controls} />}
        trailing={<CatalogKindPills kinds={filters.controls.kinds} setKinds={filters.controls.setKinds} />}
      >
        <CatalogFileFilterControls controls={filters.controls} />
      </FilterPanel>
      {children && (
        <Group gap="sm" wrap="wrap" align="center">
          {children}
        </Group>
      )}
    </>
  );
}
