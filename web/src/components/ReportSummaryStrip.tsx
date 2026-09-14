import { Chip, Group, Paper, Text } from "@mantine/core";
import classes from "../theme.module.css";

export type ReportTile = { value: number | string; label: string; color?: string };

/**
 * The generic shape behind every workspace Errors report's summary strip (extracted v2.5.0
 * from `ErrorsSummaryStrip.tsx`, JSX verbatim so the Backstage Errors page's DOM — and its
 * existing tests — stay byte-identical): stat tiles on the left (`data-tile` names each for
 * tests) and, pushed right, one Chip per class with a per-class count from the UNFILTERED
 * report. The count is `aria-hidden`, so each chip's accessible name stays the bare class
 * label a test/e2e locates. `ErrorsSummaryStrip` (Backstage) and `EntityErrorsSummaryStrip`
 * (Port, v2.5.0) are its two callers — each owns its own tile values and class vocabulary,
 * this component only renders the shared markup.
 */
export default function ReportSummaryStrip<C extends string>({
  tiles,
  allClasses,
  selectedClasses,
  setClasses,
  counts,
  classLabel,
  groupAriaLabel,
}: {
  tiles: ReportTile[];
  allClasses: ReadonlyArray<C>;
  selectedClasses: string[];
  setClasses: (next: string[]) => void;
  counts: Record<C, number>;
  classLabel: (entityClass: C) => string;
  groupAriaLabel: string;
}) {
  return (
    <Group gap="sm" wrap="wrap" align="stretch" style={{ width: "100%" }}>
      {tiles.map((tile) => (
        <Paper key={tile.label} withBorder radius="md" px="md" py={6} data-tile={tile.label}>
          <Text size="lg" fw={700} lh={1.2} c={tile.color} style={{ fontVariantNumeric: "tabular-nums" }}>
            {tile.value}
          </Text>
          <Text size="xs" c="dimmed">
            {tile.label}
          </Text>
        </Paper>
      ))}
      <Chip.Group multiple value={selectedClasses} onChange={setClasses}>
        <Group gap={6} role="group" aria-label={groupAriaLabel} ml="auto" align="center">
          {allClasses.map((entityClass) => (
            <Chip key={entityClass} value={entityClass} size="xs">
              <span>{classLabel(entityClass)}</span>
              <span
                aria-hidden="true"
                className={classes.chipCount}
                data-zero={counts[entityClass] === 0 || undefined}
              >
                {counts[entityClass]}
              </span>
            </Chip>
          ))}
        </Group>
      </Chip.Group>
    </Group>
  );
}
