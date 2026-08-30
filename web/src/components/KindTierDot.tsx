import { useTranslation } from "react-i18next";
import { Group, type ComboboxItem } from "@mantine/core";
import { IconCheck } from "@tabler/icons-react";
import { kindTier } from "../utils/catalogFileForm";
import classes from "../theme.module.css";

/**
 * The kind's tier (fill-in priority 1–4) as a small circled numeral — a purely VISUAL
 * marker rendered before the kind name everywhere kinds appear. aria-hidden on purpose:
 * pills/badges keep their bare-kind accessible names, and the kind text stays its own
 * text node beside this element. Unknown kinds render nothing.
 */
export default function KindTierDot({ kind }: { kind: string }) {
  const { t } = useTranslation();
  const tier = kindTier(kind);
  if (tier === undefined) {
    return null;
  }
  // The numeral is CSS content (::before over data-tier), NOT a text node — text locators
  // (getByText exact, Playwright included) must keep seeing the bare kind beside the dot.
  return (
    <span
      aria-hidden="true"
      data-tier={tier}
      className={classes.tierDot}
      title={t("catalog.tier.tooltip", { tier })}
    />
  );
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
