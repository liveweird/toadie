import { useTranslation } from "react-i18next";
import { Chip, Group } from "@mantine/core";
import type { Tier } from "../utils/tiers";
import CaptionedChipGroup from "./CaptionedChipGroup";
import TierDot from "./TierDot";

/**
 * The bulk-change page's always-visible blueprint picker (2.38.0): `BlueprintPills`' shape and
 * caption with SINGLE-choice semantics — exactly one blueprint's entities are changed at a
 * time, so the chips are radios (Mantine's `Chip.Group` without `multiple`) rather than the
 * canvases' visibility toggles. `value` null = nothing picked yet (the page then asks for a pick
 * and fetches nothing). Chip text is the blueprint identifier, led by its tier dot (the dot is
 * `aria-hidden`, so the accessible name stays the bare identifier).
 */
export default function BlueprintPickPills({
  blueprints,
  value,
  onChange,
  tierOf,
}: {
  /** Blueprint identifiers, in registry order. */
  blueprints: readonly string[];
  value: string | null;
  onChange: (identifier: string) => void;
  tierOf?: (identifier: string) => Tier | null;
}) {
  const { t } = useTranslation();
  return (
    <Chip.Group multiple={false} value={value} onChange={onChange}>
      <CaptionedChipGroup label={t("entityBulk.blueprintsLabel")}>
        {blueprints.map((id) => (
          <Chip key={id} value={id} size="xs">
            <Group gap={4} wrap="nowrap" display="inline-flex" component="span">
              <TierDot tier={tierOf?.(id) ?? null} />
              {id}
            </Group>
          </Chip>
        ))}
      </CaptionedChipGroup>
    </Chip.Group>
  );
}
