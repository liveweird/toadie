import { Select } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { useEntityOptions } from "../hooks/useEntityOptions";
import type { EntityGraphFilterControlsState } from "../hooks/useEntityGraphFilterState";
import { TEAM_BLUEPRINT } from "../utils/systemBlueprints";
import ClearableTextInput from "./ClearableTextInput";

/**
 * The Entity graph/hierarchy pages' filter controls (v1.25.0, + the Phase 4 Team select,
 * v1.26.0) — Search and Team only since 2.4.1: the free-text search `ClearableTextInput`, and a
 * Team `Select` fed by `useEntityOptions(TEAM_BLUEPRINT)` (the `_team` system blueprint's own
 * entities, labelled `identifier — title`; a stored value the pool doesn't currently carry is
 * appended so it keeps displaying — the registry-Select idiom). The blueprint slot moved OUT of
 * this panel to the always-visible `BlueprintPills` row (`EntityGraphToolbar.tsx`). Rendered
 * inside the Filters section via `EntityGraphToolbar.tsx`.
 */
export default function EntityGraphFilterControls({
  controls,
}: {
  controls: EntityGraphFilterControlsState;
}) {
  const { t } = useTranslation();
  const { options: teams } = useEntityOptions(TEAM_BLUEPRINT);
  const knownTeams = new Set(teams.map((team) => team.identifier));
  const teamOptions = [
    ...teams.map((team) => ({ value: team.identifier, label: `${team.identifier} — ${team.title}` })),
    ...(controls.team && !knownTeams.has(controls.team)
      ? [{ value: controls.team, label: controls.team }]
      : []),
  ];

  return (
    <>
      <ClearableTextInput
        label={t("entityGraph.filter.q")}
        value={controls.q}
        onChange={controls.setQ}
        clearLabel={t("entityGraph.filter.clearQ")}
      />
      <Select
        label={t("entityGraph.filter.team")}
        placeholder={t("entityGraph.filter.anyTeam")}
        data={teamOptions}
        value={controls.team || null}
        onChange={(value) => controls.setTeam(value ?? "")}
        searchable
        clearable
        clearButtonProps={{ "aria-label": t("entityGraph.filter.clearTeam") }}
        w={240}
      />
    </>
  );
}
