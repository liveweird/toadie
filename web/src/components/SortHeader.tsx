import { UnstyledButton } from "@mantine/core";
import { IconArrowDown, IconArrowUp, IconArrowsSort } from "@tabler/icons-react";

export type SortDir = "asc" | "desc";

/** A clickable column header cycling the list sort (pair with usePagedSort's toggleSort). */
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
    <UnstyledButton
      onClick={() => onToggle(field)}
      style={{ display: "inline-flex", alignItems: "center", gap: 4, fontWeight: 600 }}
    >
      <span>{label}</span>
      <Icon size={14} stroke={1.5} opacity={isActive ? 1 : 0.4} />
    </UnstyledButton>
  );
}
