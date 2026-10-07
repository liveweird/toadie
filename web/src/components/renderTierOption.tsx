import { Group, type ComboboxItem } from "@mantine/core";
import { IconCheck } from "@tabler/icons-react";
import TierDot from "./TierDot";

/**
 * The shared `renderOption` for every Select whose options carry a fill-in tier dot (2.18.0):
 * the dot before the label, and — `renderOption` replaces Mantine's default row wholesale —
 * the selected checkmark re-rendered, the way `renderKindOption` does for kinds. [tierOf]
 * maps an option's value to its tier (`null`/`undefined` = no dot).
 */
export function renderTierOption(tierOf: (value: string) => number | null | undefined) {
  return ({ option, checked }: { option: ComboboxItem; checked?: boolean }) => (
    <Group flex="1" gap={6} wrap="nowrap">
      <TierDot tier={tierOf(option.value)} />
      {option.label}
      {checked && <IconCheck size={14} style={{ marginInlineStart: "auto" }} />}
    </Group>
  );
}
