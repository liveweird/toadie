import { useTranslation } from "react-i18next";
import { Chip } from "@mantine/core";
import CaptionedChipGroup from "./CaptionedChipGroup";

/**
 * The always-visible blueprint-visibility pills on the Entity graph and Entity hierarchy
 * canvases (2.4.1) — replacing the Blueprints MultiSelect that used to live inside the
 * collapsible filter panel: every REGISTERED blueprint starts shown (chip text = its
 * identifier, exactly the old MultiSelect's option labels), toggling one off hides its entities
 * from the canvas, and toggling every one off shows the empty state instead of fetching an
 * unfiltered graph (the caller's `noBlueprints`, computed by `useEntityGraphFilterState`).
 * Captioned "Blueprints" (`entityGraph.blueprintsLabel`) via `CaptionedChipGroup` — the captioned
 * Port twin of the caption-less Backstage `CatalogKindPills`, since this row is now the first
 * thing under the toolbar with nothing else nearby to say what the chips are.
 */
export default function BlueprintPills({
  active,
  hidden,
  onChange,
}: {
  active: readonly string[];
  hidden: readonly string[];
  onChange: (hidden: string[]) => void;
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
        {active.map((id) => (
          <Chip key={id} value={id} size="xs">
            {id}
          </Chip>
        ))}
      </CaptionedChipGroup>
    </Chip.Group>
  );
}
