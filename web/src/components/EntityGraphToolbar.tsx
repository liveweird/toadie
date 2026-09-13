import { type ReactNode, useEffect, useRef } from "react";
import { useTranslation } from "react-i18next";
import { Badge, Group, Paper, Stack } from "@mantine/core";
import { IconCode, IconFilter } from "@tabler/icons-react";
import type { useEntityGraphFilterState } from "../hooks/useEntityGraphFilterState";
import { isBoolean, useStoredState } from "../hooks/useStoredState";
import BlueprintPills from "./BlueprintPills";
import EntityGraphFilterControls from "./EntityGraphFilterControls";
import PageHeader from "./PageHeader";
import ToolbarToggle from "./ToolbarToggle";

/**
 * The Entity graph/hierarchy pages' WHOLE header (2.4.1 — replacing the previous filter-panel-
 * plus-query-row-plus-children stack): renders `PageHeader` itself, so a canvas gets exactly two
 * rows on a first visit — the title row (Filters/Query toggles + the view's own secondary
 * controls, e.g. the hierarchy picker and layout controls, as `children`) and the always-visible
 * blueprint-pills row — with the Filters drawer and the Query section appearing as a THIRD row
 * only once opened. Collapsed state persists per-view for Filters (`${viewKey}.filtersOpen`,
 * `FilterPanel`'s own key, reused rather than duplicated) and globally for Query (the SHARED
 * `entityQuery.open` flag `useEntityQuery` owns, since the draft/applied pair it gates is itself
 * shared across both canvases). `queryForcedOpen` (a refused run's diagnostics) opens the Query
 * section through the same persisted flag — the effect only ever turns it ON, never off, so an
 * explicit user Close is never fought (it holds until the next refusal).
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
  /** The view's own secondary controls (hierarchy picker, layout controls, expand/collapse). */
  children?: ReactNode;
}) {
  const { t } = useTranslation();
  const [filtersOpen, setFiltersOpen] = useStoredState(`${viewKey}.filtersOpen`, false, isBoolean);

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

  const filtersPanelId = `${viewKey}-entity-filters`;
  const queryPanelId = `${viewKey}-entity-query`;

  return (
    <PageHeader
      title={title}
      actions={
        <Group gap="xs" wrap="wrap" align="center">
          <ToolbarToggle
            icon={<IconFilter size={16} />}
            label={t("common.filter.title")}
            open={filtersOpen}
            onClick={() => setFiltersOpen(!filtersOpen)}
            count={filters.activeFilterCount}
            controlsId={filtersOpen ? filtersPanelId : undefined}
          />
          <ToolbarToggle
            icon={<IconCode size={16} />}
            label={t("entityQuery.toggle")}
            open={queryOpen}
            onClick={() => onQueryOpenChange(!queryOpen)}
            controlsId={queryOpen ? queryPanelId : undefined}
            extra={
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
              ) : undefined
            }
          />
          {children}
        </Group>
      }
      toolbar={
        <Stack gap="sm">
          <Group gap="md" wrap="wrap" align="center">
            <BlueprintPills
              active={filters.blueprintPills.active}
              hidden={filters.blueprintPills.hidden}
              onChange={filters.blueprintPills.setHidden}
            />
            {pills}
          </Group>
          {filtersOpen && (
            <Paper id={filtersPanelId} withBorder radius="md" p="sm" bg="var(--mantine-color-default-hover)">
              <Group align="flex-end" gap="sm">
                <EntityGraphFilterControls controls={filters.controls} />
              </Group>
            </Paper>
          )}
          {queryOpen && (
            <Paper id={queryPanelId} withBorder radius="md" p="sm" bg="var(--mantine-color-default-hover)">
              {query}
            </Paper>
          )}
        </Stack>
      }
    />
  );
}
