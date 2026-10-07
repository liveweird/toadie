import { Select } from "@mantine/core";
import type { ParseKeys } from "i18next";
import { useTranslation } from "react-i18next";
import { TIERS, asTier, type Tier } from "../utils/tiers";
import TierDot from "./TierDot";
import { renderTierOption } from "./renderTierOption";

// Literal keys (not a template) so the unused-keys scan sees every option label.
const OPTION_KEYS: Record<Tier, ParseKeys> = {
  1: "common.tier.focus.upTo1",
  2: "common.tier.focus.upTo2",
  3: "common.tier.focus.upTo3",
  4: "common.tier.focus.upTo4",
};

/**
 * The one "Focus: up to tier N" picker (2.18.0) every tier-aware view shares: clearable, the
 * placeholder "All tiers" is the cleared state, and each option (Tier 1 / Tiers 1-2 / 1-3 /
 * 1-4) carries the dot of its upper bound. The value is a `Tier | null` — the persisted state
 * of `hooks/useTierFocus.ts` — never the Select's string. A purely visual narrowing: items
 * outside the focus are hidden or folded by the caller, never altered.
 */
export default function TierFocusSelect({
  value,
  onChange,
  w = 170,
}: {
  value: Tier | null;
  onChange: (next: Tier | null) => void;
  w?: number;
}) {
  const { t } = useTranslation();
  const data = TIERS.map((tier) => ({ value: String(tier), label: t(OPTION_KEYS[tier]) }));
  return (
    <Select
      label={t("common.tier.focus.label")}
      placeholder={t("common.tier.focus.placeholder")}
      data={data}
      value={value === null ? null : String(value)}
      onChange={(next) => onChange(asTier(next === null ? null : Number(next)))}
      leftSection={value === null ? undefined : <TierDot tier={value} />}
      renderOption={renderTierOption((optionValue) => Number(optionValue))}
      clearable
      clearButtonProps={{ "aria-label": t("common.tier.focus.clear") }}
      w={w}
    />
  );
}
