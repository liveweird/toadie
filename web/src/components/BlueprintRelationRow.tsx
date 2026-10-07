import { Group, Switch, TextInput } from "@mantine/core";
import { type UseFormReturnType } from "@mantine/form";
import { useTranslation } from "react-i18next";
import BlueprintTargetSelect from "./BlueprintTargetSelect";
import TierSelect from "./TierSelect";
import { MAX_TITLE_LENGTH, type BlueprintFormValues } from "../utils/blueprintForm";

type Form = UseFormReturnType<BlueprintFormValues>;

/**
 * One relation row's fields: identifier, title, description, target, and the Required/Many
 * switches. Removal/reordering lives one level up, in RelationsFieldset's `EditorRowList`
 * (the BlueprintPropertyRow precedent — no second, redundant remove control here).
 */
export default function BlueprintRelationRow({
  form,
  index,
  locked = false,
}: {
  form: Form;
  index: number;
  /** A system-seeded base relation (Phase 4, v1.26.0): the id cannot be renamed. */
  locked?: boolean;
}) {
  const { t } = useTranslation();
  const row = form.values.relations[index];
  return (
    <Group align="flex-start" gap="sm" wrap="wrap">
      <TextInput
        style={{ flex: 1, minWidth: 140 }}
        label={t("blueprints.field.relationId")}
        required
        {...form.getInputProps(`relations.${index}.id`)}
        readOnly={locked}
      />
      <TextInput
        style={{ flex: 1, minWidth: 140 }}
        label={t("blueprints.field.title")}
        required
        maxLength={MAX_TITLE_LENGTH}
        {...form.getInputProps(`relations.${index}.title`)}
      />
      <TextInput
        style={{ flex: 1, minWidth: 140 }}
        label={t("blueprints.field.description")}
        {...form.getInputProps(`relations.${index}.description`)}
      />
      <BlueprintTargetSelect
        value={row.target}
        onChange={(v) => form.setFieldValue(`relations.${index}.target`, v)}
        ownIdentifier={form.values.identifier}
        error={form.getInputProps(`relations.${index}.target`).error}
        label={t("blueprints.field.target")}
      />
      <TierSelect
        label={t("common.tier.label")}
        ariaLabel={t("common.tier.selectAria", { id: row.id.trim() || t("blueprints.rows.new.relations") })}
        value={row.tier}
        onChange={(tier) => form.setFieldValue(`relations.${index}.tier`, tier)}
        w={110}
      />
      <Switch
        mt={26}
        label={t("blueprints.field.required")}
        {...form.getInputProps(`relations.${index}.required`, { type: "checkbox" })}
      />
      <Switch
        mt={26}
        label={t("blueprints.field.many")}
        {...form.getInputProps(`relations.${index}.many`, { type: "checkbox" })}
      />
    </Group>
  );
}
