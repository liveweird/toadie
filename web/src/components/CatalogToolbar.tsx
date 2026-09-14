import { type ReactNode } from "react";
import { Group } from "@mantine/core";
import { IconEye, IconFilter } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";
import type { useCatalogFileFilterState } from "../hooks/useCatalogFileFilterState";
import { isBoolean, useStoredState } from "../hooks/useStoredState";
import { ENTITY_KINDS } from "../utils/catalogFileForm";
import CatalogFileFilterControls from "./CatalogFileFilterControls";
import CatalogKindPills from "./CatalogKindPills";
import CollapsibleHeader, { type HeaderSection } from "./CollapsibleHeader";
import FilterPanel from "./FilterPanel";
import LensPicker from "./LensPicker";

/**
 * The catalog views' toolbar — TWO modes (2.4.2).
 *
 * **Row mode** (no `title` — Files, Errors, unchanged since v1.20.0): one row via `FilterPanel`
 * (the Filters toggle, the lens picker `aside`, the kind pills `trailing`) plus a second row for
 * the view's own `children`.
 *
 * **Header mode** (`title` given — Graph, Hierarchy, since 2.4.2): renders the page's WHOLE
 * `PageHeader` itself via `CollapsibleHeader` — Filters (the SAME `${viewKey}.filtersOpen` key
 * `FilterPanel` uses, so a view's remembered state survives the switch between modes) and
 * Visibility (NEW `${viewKey}.pillsOpen` key, collapsed by default like Filters — the kind pills
 * plus the caller's own `pills`, e.g. the Graph's relation chips; its badge counts hidden kinds
 * PLUS `hiddenRelationsCount`), the lens picker as `aside`, and the view's own secondary
 * controls (layout controls, expand/collapse…) as `children` on the title row.
 */
export default function CatalogToolbar({
  title,
  viewKey,
  filters,
  pills,
  hiddenRelationsCount,
  children,
}: {
  /** Header mode when set — renders the page's WHOLE `PageHeader` (Graph, Hierarchy). */
  title?: string;
  /** The per-view storage key ("catalogFiles" | "hierarchy" | "graph" | "errors"). */
  viewKey: string;
  filters: ReturnType<typeof useCatalogFileFilterState>;
  /** Header-mode only: an extra chip group beside the kind pills (the Graph's Relations group). */
  pills?: ReactNode;
  /** Header-mode only: hidden relation-chip count, folded into the Visibility badge. */
  hiddenRelationsCount?: number;
  children?: ReactNode;
}) {
  const { t } = useTranslation();
  const [filtersOpen, setFiltersOpen] = useStoredState(`${viewKey}.filtersOpen`, false, isBoolean);
  const [pillsOpen, setPillsOpen] = useStoredState(`${viewKey}.pillsOpen`, false, isBoolean);

  if (title != null) {
    const hiddenCount = ENTITY_KINDS.length - filters.controls.kinds.length + (hiddenRelationsCount ?? 0);
    const sections: HeaderSection[] = [
      {
        id: `${viewKey}-filter-panel`,
        icon: <IconFilter size={16} />,
        label: t("common.filter.title"),
        open: filtersOpen,
        onOpenChange: setFiltersOpen,
        count: filters.activeFilterCount,
        frame: "paper",
        content: (
          <Group align="flex-end" gap="sm">
            <CatalogFileFilterControls controls={filters.controls} />
          </Group>
        ),
      },
      {
        id: `${viewKey}-visibility`,
        icon: <IconEye size={16} />,
        label: t("catalog.visibilityToggle"),
        open: pillsOpen,
        onOpenChange: setPillsOpen,
        count: hiddenCount,
        frame: "plain",
        content: (
          <>
            <CatalogKindPills kinds={filters.controls.kinds} setKinds={filters.controls.setKinds} />
            {pills}
          </>
        ),
      },
    ];
    return (
      <CollapsibleHeader
        title={title}
        sections={sections}
        aside={<LensPicker values={filters.values} controls={filters.controls} />}
      >
        {children}
      </CollapsibleHeader>
    );
  }

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
