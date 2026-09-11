import { Badge, Group, Stack, Text } from "@mantine/core";
import type { UseFormReturnType } from "@mantine/form";
import { useTranslation } from "react-i18next";
import type { Blueprint } from "../api/blueprints";
import EntityOptionsSelect from "./EntityOptionsSelect";
import { entityFindingProps, type EntityFieldFindings } from "../utils/entityFieldFindings";
import type { EntityFormValues } from "../utils/entityForm";
import { TEAM_BLUEPRINT } from "../utils/systemBlueprints";

type Form = UseFormReturnType<EntityFormValues>;

/**
 * The `team` field's widget — Phase 4 ownership (`.claude/docs/port-data-model.md`
 * "Ownership"), the plain `TagsInput` `EntityFormFields` carried before v1.26.0:
 *
 * - **Direct/absent ownership** (the STORED value): a MultiSelect over active `_team`
 *   entities (`EntityOptionsSelect`), labelled by the blueprint's own `ownership.title` when
 *   set (falling back to the generic "Team" label) — `getInputProps("team")` spread first so
 *   validate-on-blur keeps working, the field's finding props spread last.
 * - **Inherited ownership** (the EFFECTIVE value, computed server-side at read time — never
 *   stored, never editable here): read-only pills of `computedTeam` plus a hint naming the
 *   `ownership.path` it was derived from. No fetch — nothing here queries `_team` at all.
 */
export default function EntityTeamField({
  form,
  blueprint,
  computedTeam,
  findings,
}: {
  form: Form;
  blueprint: Blueprint;
  /** The entity's own EFFECTIVE team (from its GET response) — empty on create, where nothing
   *  has been saved yet for the server to compute a chain from. */
  computedTeam: readonly string[];
  findings: EntityFieldFindings;
}) {
  const { t } = useTranslation();
  const ownership = blueprint.ownership;
  const label = ownership?.title ?? t("entities.field.team");

  if (ownership?.type === "Inherited") {
    return (
      <Stack gap={4}>
        <Text size="sm" fw={500}>
          {label}
        </Text>
        {computedTeam.length > 0 ? (
          <Group gap="xs">
            {computedTeam.map((value) => (
              <Badge key={value} variant="light" color="gray">
                {value}
              </Badge>
            ))}
          </Group>
        ) : (
          <Text size="sm" c="dimmed">
            {t("entities.field.teamInheritedEmpty")}
          </Text>
        )}
        <Text size="xs" c="dimmed">
          {t("entities.hint.teamInherited", { path: ownership.path ?? "" })}
        </Text>
      </Stack>
    );
  }

  return (
    <EntityOptionsSelect
      mode="multi"
      target={TEAM_BLUEPRINT}
      label={label}
      description={t("entities.hint.team")}
      failedHint={t("entities.field.teamOptionsFailed")}
      emptyHint={t("entities.editor.noTargets")}
      inputProps={form.getInputProps("team")}
      findingProps={entityFindingProps(findings.forField("team"), t, { hardError: form.errors.team })}
    />
  );
}
