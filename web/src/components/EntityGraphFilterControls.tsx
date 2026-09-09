import { MultiSelect } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { useBlueprints } from "../hooks/useBlueprints";
import type { EntityGraphFilterControlsState } from "../hooks/useEntityGraphFilterState";
import ClearableTextInput from "./ClearableTextInput";

/**
 * The Entity graph/hierarchy pages' filter controls (v1.25.0): a blueprint `MultiSelect` fed
 * by `hooks/useBlueprints.ts` (a stored identifier the registry no longer carries is appended
 * to its own options, the registry-Select idiom, so it keeps displaying) plus the free-text
 * search `ClearableTextInput`. Rendered inside `FilterPanel` via `EntityGraphToolbar.tsx`.
 */
export default function EntityGraphFilterControls({
  controls,
}: {
  controls: EntityGraphFilterControlsState;
}) {
  const { t } = useTranslation();
  const { blueprints } = useBlueprints();
  const known = new Set(blueprints.map((b) => b.identifier));
  const options = [
    ...blueprints.map((b) => ({ value: b.identifier, label: b.identifier })),
    ...controls.blueprints.filter((id) => !known.has(id)).map((id) => ({ value: id, label: id })),
  ];
  return (
    <>
      <MultiSelect
        label={t("entityGraph.filter.blueprints")}
        data={options}
        value={controls.blueprints}
        onChange={controls.setBlueprints}
        searchable
        clearable
        w={280}
      />
      <ClearableTextInput
        label={t("entityGraph.filter.q")}
        value={controls.q}
        onChange={controls.setQ}
        clearLabel={t("entityGraph.filter.clearQ")}
      />
    </>
  );
}
