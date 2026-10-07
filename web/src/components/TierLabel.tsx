import type { ReactNode } from "react";
import TierDot from "./TierDot";

/**
 * A form-field label led by its fill-in tier dot (2.18.0). The dot is aria-hidden and
 * contributes no text node, so the field's accessible name and every label-based locator stay
 * the bare label; an untiered field renders the label unchanged (no wrapper element).
 */
export default function TierLabel({ tier, children }: { tier: number | null | undefined; children: ReactNode }) {
  if (tier == null) return <>{children}</>;
  return (
    <span style={{ display: "inline-flex", alignItems: "center", gap: 6 }}>
      <TierDot tier={tier} />
      <span>{children}</span>
    </span>
  );
}
