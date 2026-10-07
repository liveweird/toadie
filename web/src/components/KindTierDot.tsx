import { Group, type ComboboxItem } from "@mantine/core";
import { IconCheck } from "@tabler/icons-react";
import { kindTier } from "../utils/catalogFileForm";
import TierDot from "./TierDot";

/**
 * The kind's tier (fill-in priority 1–4) as a small circled numeral — a purely VISUAL
 * marker rendered before the kind name everywhere kinds appear. aria-hidden on purpose:
 * pills/badges keep their bare-kind accessible names, and the kind text stays its own
 * text node beside this element. Unknown kinds render nothing. The rendering itself is the
 * shared `TierDot` (the blueprint tiers use the same marker).
 */
export default function KindTierDot({ kind }: { kind: string }) {
  return <TierDot tier={kindTier(kind)} />;
}

/**
 * Shared renderOption for every Select/MultiSelect whose options ARE kinds — the dot before
 * the kind, and (renderOption replaces Mantine's default row wholesale) the selected
 * checkmark re-rendered for MultiSelects.
 */
// eslint-disable-next-line react-refresh/only-export-components -- a render-prop helper, not a component; it belongs beside the dot it wraps
export function renderKindOption({ option, checked }: { option: ComboboxItem; checked?: boolean }) {
  return (
    <Group flex="1" gap={6} wrap="nowrap">
      <KindTierDot kind={option.value} />
      {option.label}
      {checked && <IconCheck size={14} style={{ marginInlineStart: "auto" }} />}
    </Group>
  );
}
