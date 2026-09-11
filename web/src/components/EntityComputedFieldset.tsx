import { Badge, Fieldset, Group, Stack, Text } from "@mantine/core";
import { useTranslation } from "react-i18next";
import EntityComputedValue from "./EntityComputedValue";
import type { ComputedDefinition, ComputedKind } from "../utils/computedProperties";

const KIND_BADGE_COLOR: Record<ComputedKind, string> = {
  mirror: "blue",
  calculation: "grape",
  aggregation: "cyan",
};

/**
 * The entity editor's read-only "Computed" section (v1.27.0, phase 5 of the Port data-model
 * move) — one row per mirror/calculation/aggregation property the owning blueprint declares,
 * each evaluated server-side at read time and never part of form state: there is nothing here
 * to submit, `utils/entityForm.ts#toEntityRequest` never emits these ids, and the server 400s
 * (`COMPUTED_PROPERTY`) a POST/PUT that tries to. Rendered by `EntityFormFields` LAST, after
 * Relations, only when the caller supplies values AND the blueprint declares at least one
 * computed property — the `EntityTeamField` Inherited-pills template one level up: a title, a
 * kind Badge (localized Mirror/Calculation/Aggregation), the value via the shared
 * `EntityComputedValue`, and a fixed hint that it isn't editable here.
 */
export default function EntityComputedFieldset({
  definitions,
  values,
}: {
  definitions: readonly ComputedDefinition[];
  values: Record<string, unknown>;
}) {
  const { t } = useTranslation();
  return (
    <Fieldset legend={t("entities.section.computed")}>
      <Stack gap="md">
        {definitions.map((definition) => (
          <Stack key={definition.id} gap={4}>
            <Group gap="xs">
              <Text size="sm" fw={500}>
                {definition.title}
              </Text>
              <Badge size="sm" variant="light" color={KIND_BADGE_COLOR[definition.kind]}>
                {t(`entities.computed.kind.${definition.kind}`)}
              </Badge>
            </Group>
            <EntityComputedValue value={values[definition.id]} definition={definition} />
            <Text size="xs" c="dimmed">
              {t("entities.hint.computed")}
            </Text>
          </Stack>
        ))}
      </Stack>
    </Fieldset>
  );
}
