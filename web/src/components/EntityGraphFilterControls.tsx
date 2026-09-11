import { MultiSelect, Select } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { useBlueprints } from "../hooks/useBlueprints";
import { useEntityOptions } from "../hooks/useEntityOptions";
import type { EntityGraphFilterControlsState } from "../hooks/useEntityGraphFilterState";
import { TEAM_BLUEPRINT } from "../utils/systemBlueprints";
import ClearableTextInput from "./ClearableTextInput";

/**
 * The Entity graph/hierarchy pages' filter controls (v1.25.0, + the Phase 4 Team select,
 * v1.26.0): a blueprint `MultiSelect` fed by `hooks/useBlueprints.ts` (a stored identifier the
 * registry no longer carries is appended to its own options, the registry-Select idiom, so it
 * keeps displaying), the free-text search `ClearableTextInput`, and a Team `Select` fed by
 * `useEntityOptions(TEAM_BLUEPRINT)` (the `_team` system blueprint's own entities, labelled
 * `identifier — title`; same stale-value-append idiom). Rendered inside `FilterPanel` via
 * `EntityGraphToolbar.tsx`.
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
