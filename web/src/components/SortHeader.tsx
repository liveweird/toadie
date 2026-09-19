import { Table, Text, UnstyledButton } from "@mantine/core";
import { IconArrowDown, IconArrowUp, IconArrowsSort } from "@tabler/icons-react";

export type SortDir = "asc" | "desc";

/**
 * A sortable column header cycling the list sort (pair with usePagedSort's toggleSort).
 * Renders its own `Table.Th` so the sort state also lands as `aria-sort` on the header
 * cell — the icon alone is mouse-eyes-only. An optional `secondary` field (2.8.1 — the
 * Entities identifier/title header) renders a second, smaller sort button in the SAME
 * header cell; `aria-sort` then reflects whichever of the two fields is the active one
 * (`none` when neither is).
 */
export default function SortHeader<F extends string>({
  field,
  label,
  secondary,
  activeField,
  activeDir,
  onToggle,
  width,
}: {
  field: F;
  label: string;
  /** A second, smaller sort control sharing this header cell (e.g. Title beside Identifier). */
  secondary?: { field: F; label: string };
  activeField: F;
  activeDir: SortDir;
  onToggle: (field: F) => void;
  /** A fixed column width (px) for `layout="fixed"` tables — omit on the one wide column. */
  width?: number;
}) {
  const isActive = activeField === field;
  const isSecondaryActive = secondary != null && activeField === secondary.field;
  const Icon = !isActive ? IconArrowsSort : activeDir === "asc" ? IconArrowUp : IconArrowDown;
  const SecondaryIcon = !isSecondaryActive ? IconArrowsSort : activeDir === "asc" ? IconArrowUp : IconArrowDown;
  const ariaSort = isActive || isSecondaryActive ? (activeDir === "asc" ? "ascending" : "descending") : "none";
  return (
    <Table.Th aria-sort={ariaSort} w={width}>
      <UnstyledButton
        onClick={() => onToggle(field)}
        style={{ display: "inline-flex", alignItems: "center", gap: 4, fontWeight: 600 }}
      >
        <span>{label}</span>
        <Icon size={14} stroke={1.5} opacity={isActive ? 1 : 0.4} />
      </UnstyledButton>
      {secondary && (
        <>
          <Text component="span" c="dimmed" fz="xs" mx={4} aria-hidden>
            ·
          </Text>
          <UnstyledButton
            onClick={() => onToggle(secondary.field)}
            c={isSecondaryActive ? undefined : "dimmed"}
            style={{
              display: "inline-flex",
              alignItems: "center",
              gap: 4,
              fontSize: "var(--mantine-font-size-xs)",
              fontWeight: 500,
            }}
          >
            <span>{secondary.label}</span>
            <SecondaryIcon size={12} stroke={1.5} opacity={isSecondaryActive ? 1 : 0.4} />
          </UnstyledButton>
        </>
      )}
    </Table.Th>
  );
}
