import { Fieldset, Stack, TextInput } from "@mantine/core";
import type { UseFormReturnType } from "@mantine/form";
import { useTranslation } from "react-i18next";
import type { Blueprint } from "../api/blueprints";
import EntityPropertyField from "./EntityPropertyField";
import EntityRelationField from "./EntityRelationField";
import EntityTeamField from "./EntityTeamField";
import { BELOW_INPUT, charCountDescription } from "../utils/charCount";
import { NO_ENTITY_FINDINGS, type EntityFieldFindings } from "../utils/entityFieldFindings";
import { MAX_ICON_LENGTH, MAX_IDENTIFIER_LENGTH, MAX_TITLE_LENGTH, type EntityFormValues } from "../utils/entityForm";

type Form = UseFormReturnType<EntityFormValues>;

function IdentityFieldset({
  form,
  blueprint,
  computedTeam,
  findings,
}: {
  form: Form;
  blueprint: Blueprint;
  computedTeam: readonly string[];
  findings: EntityFieldFindings;
}) {
  const { t } = useTranslation();
  return (
    <Fieldset legend={t("entities.section.identity")}>
      <Stack gap="sm">
        <TextInput
          label={t("entities.field.identifier")}
          autoFocus
          required
          maxLength={MAX_IDENTIFIER_LENGTH}
          description={charCountDescription(form.values.identifier.length, MAX_IDENTIFIER_LENGTH)}
          inputWrapperOrder={[...BELOW_INPUT]}
          {...form.getInputProps("identifier")}
        />
        <TextInput
          label={t("entities.field.title")}
          required
          maxLength={MAX_TITLE_LENGTH}
          description={charCountDescription(form.values.title.length, MAX_TITLE_LENGTH)}
          inputWrapperOrder={[...BELOW_INPUT]}
          {...form.getInputProps("title")}
        />
        <TextInput label={t("entities.field.icon")} maxLength={MAX_ICON_LENGTH} {...form.getInputProps("icon")} />
        <EntityTeamField form={form} blueprint={blueprint} computedTeam={computedTeam} findings={findings} />
      </Stack>
    </Fieldset>
  );
}

function PropertiesFieldset({
  form,
  blueprint,
  findings,
}: {
  form: Form;
  blueprint: Blueprint;
  findings: EntityFieldFindings;
}) {
  const { t } = useTranslation();
  if (form.values.properties.length === 0) return null;
  const required = new Set(blueprint.schema.required);
  return (
    <Fieldset legend={t("entities.section.properties")}>
      <Stack gap="md">
        {form.values.properties.map((draft, index) => (
          <EntityPropertyField
            key={draft.id}
            form={form}
            index={index}
            definition={blueprint.schema.properties[draft.id]}
            required={required.has(draft.id)}
            findings={findings.forField(`properties.${draft.id}`)}
          />
        ))}
      </Stack>
    </Fieldset>
  );
}

function RelationsFieldset({ form, blueprint }: { form: Form; blueprint: Blueprint }) {
  const { t } = useTranslation();
  if (form.values.relations.length === 0) return null;
  return (
    <Fieldset legend={t("entities.section.relations")}>
      <Stack gap="md">
        {form.values.relations.map((draft, index) => {
          const definition = blueprint.relations[draft.id];
          if (!definition) return null;
          return (
            <EntityRelationField
              key={draft.id}
              form={form}
              index={index}
              definition={definition}
              required={definition.required}
            />
          );
        })}
      </Stack>
    </Fieldset>
  );
}

/**
 * The field block for the Entity editor: Identity (identifier/title/icon/team) then one
 * fixed-size row per blueprint schema property and relation — unlike the Blueprint editor's
 * foldable EditorRowLists, an entity cannot invent new property/relation ids, so there is
 * nothing to add, move, or remove here. `findings` (Phase 4 ownership + the general stale-entity
 * surface) is indexed once by `EntityEditor` and threaded down so `team`/`properties.<id>`
 * controls paint their own soft finding; `computedTeam` is the entity's own EFFECTIVE `team`
 * (empty on create) — `EntityTeamField`'s Inherited-ownership pills.
 */
export default function EntityFormFields({
  form,
  blueprint,
  computedTeam = [],
  findings = NO_ENTITY_FINDINGS,
}: {
  form: Form;
  blueprint: Blueprint;
  computedTeam?: readonly string[];
  findings?: EntityFieldFindings;
}) {
  return (
    <Stack gap="md">
      <IdentityFieldset form={form} blueprint={blueprint} computedTeam={computedTeam} findings={findings} />
      <PropertiesFieldset form={form} blueprint={blueprint} findings={findings} />
      <RelationsFieldset form={form} blueprint={blueprint} />
    </Stack>
  );
}
