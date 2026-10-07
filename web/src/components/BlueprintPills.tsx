import { useTranslation } from "react-i18next";
import { Chip, Group } from "@mantine/core";
import { withinFocus, type Tier } from "../utils/tiers";
import CaptionedChipGroup from "./CaptionedChipGroup";
import TierDot from "./TierDot";
import classes from "../theme.module.css";

/**
 * The always-visible blueprint-visibility pills on the Entity graph and Entity hierarchy
 * canvases (2.4.1) — replacing the Blueprints MultiSelect that used to live inside the
 * collapsible filter panel: every REGISTERED blueprint starts shown (chip text = its
 * identifier, exactly the old MultiSelect's option labels), toggling one off hides its entities
 * from the canvas, and toggling every one off shows the empty state instead of fetching an
 * unfiltered graph (the caller's `noBlueprints`, computed by `useEntityGraphFilterState`).
 * Captioned "Blueprints" (`entityGraph.blueprintsLabel`) via `CaptionedChipGroup` — the captioned
 * Port twin of the caption-less Backstage `CatalogKindPills`, since this row is now the first
 * thing under the toolbar with nothing else nearby to say what the chips are. Since 2.18.0
 * each chip leads with the blueprint's `TierDot`, and — while a tier Focus is active — a
 * blueprint outside it is DIMMED (`data-out-of-focus` + a native tooltip + an `aria-description` for assistive tech) but stays togglable:
 * the pills are the user's own visibility set, the Focus only narrows what is fetched.
 */
export default function BlueprintPills({
  active,
  hidden,
  onChange,
  tierOf,
  focus = null,
}: {
  active: readonly string[];
  hidden: readonly string[];
  onChange: (hidden: string[]) => void;
  /** Blueprint identifier → its tier (`null` untiered); absent = no dots, nothing dimmed. */
  tierOf?: (identifier: string) => Tier | null;
  /** The active tier Focus, `null` = all tiers. */
  focus?: Tier | null;
}) {
  const { t } = useTranslation();
  const hiddenSet = new Set(hidden);
  const visible = active.filter((id) => !hiddenSet.has(id));

  return (
    <Chip.Group
      multiple
      value={visible}
      onChange={(values) => onChange(active.filter((id) => !values.includes(id)))}
    >
      <CaptionedChipGroup label={t("entityGraph.blueprintsLabel")}>
        {active.map((id) => {
          const tier = tierOf?.(id) ?? null;
          const outOfFocus = tierOf !== undefined && !withinFocus(tier, focus);
          return (
            <Chip
              key={id}
              value={id}
              size="xs"
              className={outOfFocus ? classes.outOfFocus : undefined}
              // On the checkbox input (Chip spreads its rest props there): a description, not a
              // label, so the pill's accessible name stays the bare blueprint identifier.
              aria-description={outOfFocus ? t("common.tier.outOfFocus") : undefined}
              wrapperProps={
                outOfFocus ? { "data-out-of-focus": true, title: t("common.tier.outOfFocus") } : undefined
              }
            >
              <Group gap={4} wrap="nowrap" display="inline-flex" component="span">
                <TierDot tier={tier} />
                {id}
              </Group>
            </Chip>
          );
        })}
      </CaptionedChipGroup>
    </Chip.Group>
  );
}
