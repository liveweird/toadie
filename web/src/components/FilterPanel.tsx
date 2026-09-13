import { type ReactNode } from "react";
import { Group, Paper } from "@mantine/core";
import { IconFilter } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";
import { isBoolean, useStoredState } from "../hooks/useStoredState";
import ToolbarToggle from "./ToolbarToggle";

/**
 * The collapsible filter drawer above every list table: collapsed by default, open state
 * persisted per view, with an active-filter-count badge on the toggle. The toggle itself is
 * `ToolbarToggle` (extracted for `EntityGraphToolbar`'s own Filters/Query pair) — this pure
 * refactor changes nothing about the rendered markup or persisted key.
 */
export default function FilterPanel({
  activeFilterCount,
  storageKey,
  children,
  aside,
  trailing,
}: {
  activeFilterCount: number;
  storageKey: string;
  children: ReactNode;
  /** Rendered in the header row beside the toggle — reachable with the panel collapsed
   *  (the LensPicker's slot). */
  aside?: ReactNode;
  /** Rendered at the END of the header row, pushed right (the Kind pills' slot, v1.20.0). */
  trailing?: ReactNode;
}) {
  const { t } = useTranslation();
  const [open, setOpen] = useStoredState(`${storageKey}.filtersOpen`, false, isBoolean);
  const panelId = `${storageKey}-filter-panel`;
  return (
    <div>
      <Group gap="xs" mb={open ? "sm" : 0} wrap="wrap">
        <ToolbarToggle
          icon={<IconFilter size={16} />}
          label={t("common.filter.title")}
          open={open}
          onClick={() => setOpen(!open)}
          count={activeFilterCount}
          controlsId={open ? panelId : undefined}
        />
        {aside}
        {trailing && <Group ml="auto">{trailing}</Group>}
      </Group>
      {open && (
        <Paper id={panelId} withBorder radius="md" p="sm" bg="var(--mantine-color-default-hover)">
          <Group align="flex-end" gap="sm">
            {children}
          </Group>
        </Paper>
      )}
    </div>
  );
}
