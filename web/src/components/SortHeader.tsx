import { Table, UnstyledButton } from "@mantine/core";
import { IconArrowDown, IconArrowUp, IconArrowsSort } from "@tabler/icons-react";

export type SortDir = "asc" | "desc";

/**
 * A sortable column header cycling the list sort (pair with usePagedSort's toggleSort).
 * Renders its own `Table.Th` so the sort state also lands as `aria-sort` on the header
 * cell — the icon alone is mouse-eyes-only.
 */
export default function SortHeader<F extends string>({
  field,
  label,
  activeField,
  activeDir,
  onToggle,
}: {
  field: F;
  label: string;
  activeField: F;
  activeDir: SortDir;
  onToggle: (field: F) => void;
}) {
  const isActive = activeField === field;
  const Icon = !isActive ? IconArrowsSort : activeDir === "asc" ? IconArrowUp : IconArrowDown;
  return (
    <Table.Th aria-sort={!isActive ? "none" : activeDir === "asc" ? "ascending" : "descending"}>
      <UnstyledButton
        onClick={() => onToggle(field)}
        style={{ display: "inline-flex", alignItems: "center", gap: 4, fontWeight: 600 }}
      >
        <span>{label}</span>
        <Icon size={14} stroke={1.5} opacity={isActive ? 1 : 0.4} />
      </UnstyledButton>
    </Table.Th>
  );
}
