import { useTranslation } from "react-i18next";
import { asTier } from "../utils/tiers";
import classes from "../theme.module.css";

/**
 * A fill-in tier (1-4) as a small circled numeral — a purely VISUAL marker rendered before a
 * name wherever tiers appear (kinds, blueprints, properties, relations). aria-hidden on
 * purpose: pills/badges keep their bare accessible names, and the label stays its own text
 * node beside this element. A missing or out-of-range tier renders nothing.
 */
export default function TierDot({ tier }: { tier: number | null | undefined }) {
  const { t } = useTranslation();
  const value = asTier(tier);
  if (value === null) {
    return null;
  }
  // The numeral is CSS content (::before over data-tier), NOT a text node — text locators
  // (getByText exact, Playwright included) must keep seeing the bare label beside the dot.
  return (
    <span
      aria-hidden="true"
      data-tier={value}
      className={classes.tierDot}
      title={t("common.tier.tooltip", { tier: value })}
    />
  );
}
