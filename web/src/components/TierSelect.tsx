import { Select } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { TIERS, asTier } from "../utils/tiers";
import TierDot from "./TierDot";
import { renderTierOption } from "./renderTierOption";

/**
 * The compact tier editor (2.18.0) of a blueprint, a schema property or a relation: "No tier"
 * (`null`) or Tier 1-4, each option with its dot. The caller wires `value`/`onChange` to the
 * draft's `tier` (`form.setFieldValue`). The visible [label] is short ("Tier") while
 * [ariaLabel] names the row ("Tier for {id}"); the blueprint-level picker passes the long
 * "Fill-in tier" label and no aria override.
 */
export default function TierSelect({
  value,
  onChange,
  label,
  ariaLabel,
  description,
  w,
}: {
  value: number | null;
  onChange: (next: number | null) => void;
  label: string;
  ariaLabel?: string;
  description?: string;
  w?: number;
}) {
  const { t } = useTranslation();
  const data = [
    { value: "", label: t("common.tier.none") },
    ...TIERS.map((tier) => ({ value: String(tier), label: t("common.tier.option", { tier }) })),
  ];
  return (
    <Select
      label={label}
      aria-label={ariaLabel}
      description={description}
      data={data}
      value={asTier(value) === null ? "" : String(value)}
      onChange={(next) => onChange(next ? Number(next) : null)}
      leftSection={asTier(value) === null ? undefined : <TierDot tier={value} />}
      renderOption={renderTierOption((optionValue) => (optionValue ? Number(optionValue) : null))}
      allowDeselect={false}
      w={w}
    />
  );
}
