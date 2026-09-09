import { MultiSelect, Select } from "@mantine/core";
import type { UseFormReturnType } from "@mantine/form";
import { useTranslation } from "react-i18next";
import { useEntityOptions } from "../hooks/useEntityOptions";
import { foldedOptionsFilter } from "../utils/text";
import type { EntityFormValues, RelationDefinitionWire } from "../utils/entityForm";

type Form = UseFormReturnType<EntityFormValues>;

/**
 * One relation row's widget: a Select (`many: false`) or MultiSelect (`many: true`) over
 * `useEntityOptions(target)` — every active entity of the target blueprint, labelled
 * `identifier — title`. A stored value the pool doesn't currently carry (deleted target,
 * or simply outside the pool's cap) is appended so it keeps displaying — the registry-Select
 * idiom used throughout the app (`BlueprintTargetSelect`, the catalog form's registry pickers).
 */
export default function EntityRelationField({
  form,
  index,
  definition,
  required,
}: {
  form: Form;
  index: number;
  definition: RelationDefinitionWire;
  required: boolean;
}) {
  const { t } = useTranslation();
  const draft = form.values.relations[index];
  const { options, loading, error } = useEntityOptions(definition.target);

  const optionIds = options.map((e) => e.identifier);
  const labelFor = (identifier: string) => {
    const match = options.find((e) => e.identifier === identifier);
    return match ? `${match.identifier} — ${match.title}` : identifier;
  };

  let hint: string | undefined;
  if (error) hint = t("entities.field.relationOptionsFailed");
  else if (!loading && optionIds.length === 0) hint = t("entities.editor.noTargets");

  if (definition.many) {
    const stale = draft.many.filter((v) => !optionIds.includes(v));
    const data = [...optionIds, ...stale].map((v) => ({ value: v, label: labelFor(v) }));
    return (
      <MultiSelect
        label={definition.title}
        description={definition.description ?? hint}
        required={required}
        searchable
        filter={foldedOptionsFilter}
        data={data}
        {...form.getInputProps(`relations.${index}.many`)}
      />
    );
  }

  const current = draft.single.trim();
  const stale = current && !optionIds.includes(current) ? [current] : [];
  const data = [...optionIds, ...stale].map((v) => ({ value: v, label: labelFor(v) }));
  return (
    <Select
      label={definition.title}
      description={definition.description ?? hint}
      required={required}
      searchable
      clearable={!required}
      filter={foldedOptionsFilter}
      data={data}
      {...form.getInputProps(`relations.${index}.single`)}
      value={current || null}
      onChange={(v) => form.setFieldValue(`relations.${index}.single`, v ?? "")}
    />
  );
}
