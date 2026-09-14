import { type ReactNode, useEffect, useRef } from "react";
import { useTranslation } from "react-i18next";
import { Badge, Group } from "@mantine/core";
import { IconCode, IconEye, IconFilter } from "@tabler/icons-react";
import type { useEntityGraphFilterState } from "../hooks/useEntityGraphFilterState";
import { isBoolean, useStoredState } from "../hooks/useStoredState";
import BlueprintPills from "./BlueprintPills";
import CollapsibleHeader, { type HeaderSection } from "./CollapsibleHeader";
import EntityGraphFilterControls from "./EntityGraphFilterControls";

/**
 * The Entity graph/hierarchy pages' WHOLE header (2.4.2 — a thin composer over
 * `CollapsibleHeader`): three collapsible title-row sections, Filters, Visibility, and Query,
 * plus the view's own secondary controls as `children`. **Visibility (new since 2.4.2)** folds
 * the blueprint/relation pills — an always-visible second row through 2.4.1 — behind its own
 * toggle (`${viewKey}.pillsOpen`, the `filtersOpen` idiom, collapsed by default like every
 * other section); its badge counts hidden ACTIVE blueprints (`filters.blueprintPills.hidden`,
 * already narrowed to active ids) plus the caller's own `hiddenRelationsCount` (the Graph's
 * relation-family chips, a separate unpersisted dimension), so a collapsed section never hides
 * state silently. Filters stays keyed by `${viewKey}.filtersOpen` (`FilterPanel`'s own key,
 * reused rather than duplicated); Query stays keyed by the SHARED `entityQuery.open` flag
 * `useEntityQuery` owns, since the draft/applied pair it gates is itself shared across both
 * canvases. `queryForcedOpen` (a refused run's diagnostics) opens the Query section through
 * that same persisted flag — the effect only ever turns it ON, never off, so an explicit user
 * Close is never fought (it holds until the next refusal).
 */
export default function EntityGraphToolbar({
  title,
  viewKey,
  filters,
  queryOpen,
  onQueryOpenChange,
  queryForcedOpen,
  appliedCount,
  query,
  pills,
  hiddenRelationsCount,
  children,
}: {
  title: string;
  /** The per-view storage key ("entityGraph" | "entityHierarchy"). */
  viewKey: string;
  filters: ReturnType<typeof useEntityGraphFilterState>;
  queryOpen: boolean;
  onQueryOpenChange: (open: boolean) => void;
  /** A refused run's diagnostics force the section open (persisted like a user toggle). */
  queryForcedOpen: boolean;
  /** Set once a query is applied and the graph loaded successfully; the shown entity count. */
  appliedCount?: number;
  /** The entity query bar (`EntityQueryBar`) — rendered inside the Query section. */
  query: ReactNode;
  /** An extra captioned chip group beside the blueprint pills (the graph's Relations group). */
  pills?: ReactNode;
  /** Hidden relation-chip count (the graph's own dimension), folded into the Visibility badge
   *  alongside hidden blueprints. */
  hiddenRelationsCount?: number;
  /** The view's own secondary controls (hierarchy picker, layout controls, expand/collapse). */
  children?: ReactNode;
}) {
  const { t } = useTranslation();
  const [filtersOpen, setFiltersOpen] = useStoredState(`${viewKey}.filtersOpen`, false, isBoolean);
  const [pillsOpen, setPillsOpen] = useStoredState(`${viewKey}.pillsOpen`, false, isBoolean);

  // Fires on the RISING edge of `queryForcedOpen` only. The setter is a fresh closure on every
  // render of the owning page (a `useStoredState` setter is not memoized), so the effect re-runs
  // after the user's own Close click re-renders the page — without the edge guard it would see
  // `queryForcedOpen` still true and flip the section straight back open.
  const wasForcedOpen = useRef(false);
  useEffect(() => {
    const rising = queryForcedOpen && !wasForcedOpen.current;
    wasForcedOpen.current = queryForcedOpen;
    if (rising) onQueryOpenChange(true);
  }, [queryForcedOpen, onQueryOpenChange]);

  const hiddenCount = filters.blueprintPills.hidden.length + (hiddenRelationsCount ?? 0);

  const sections: HeaderSection[] = [
    {
      id: `${viewKey}-entity-filters`,
      icon: <IconFilter size={16} />,
      label: t("common.filter.title"),
      open: filtersOpen,
      onOpenChange: setFiltersOpen,
      count: filters.activeFilterCount,
      frame: "paper",
      content: (
        <Group align="flex-end" gap="sm">
          <EntityGraphFilterControls controls={filters.controls} />
        </Group>
      ),
    },
    {
      id: `${viewKey}-entity-visibility`,
      icon: <IconEye size={16} />,
      label: t("entityGraph.visibilityToggle"),
      open: pillsOpen,
      onOpenChange: setPillsOpen,
      count: hiddenCount,
      frame: "plain",
      content: (
        <>
          <BlueprintPills
            active={filters.blueprintPills.active}
            hidden={filters.blueprintPills.hidden}
            onChange={filters.blueprintPills.setHidden}
          />
          {pills}
        </>
      ),
    },
    {
      id: `${viewKey}-entity-query`,
      icon: <IconCode size={16} />,
      label: t("entityQuery.toggle"),
      open: queryOpen,
      onOpenChange: onQueryOpenChange,
      extra:
        appliedCount != null ? (
          <Badge
            data-testid="entityQuery-applied"
            variant="light"
            color="gray"
            size="sm"
            tt="none"
            style={{ flexShrink: 0 }}
          >
            {t("entityQuery.applied")} · {t("entityQuery.appliedCount", { count: appliedCount })}
          </Badge>
        ) : undefined,
      frame: "paper",
      content: query,
    },
  ];

  return (
    <CollapsibleHeader title={title} sections={sections}>
      {children}
    </CollapsibleHeader>
  );
}
