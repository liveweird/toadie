import { Group, Select, Stack, Switch, Textarea, TextInput } from "@mantine/core";
import { type UseFormReturnType } from "@mantine/form";
import { useTranslation } from "react-i18next";
import {
  MAX_TITLE_LENGTH,
  OBJECT_SPECS,
  PROPERTY_TYPES,
  STRING_FORMATS,
  STRING_SPECS,
  type BlueprintFormValues,
} from "../utils/blueprintForm";

type Form = UseFormReturnType<BlueprintFormValues>;

/**
 * One calculation property row's fields: identifier/title/type, the type-conditional
 * format/spec + colorized switch, the jq calculation, and the optional colours map.
 * Removal/reordering lives one level up, in CalculationFieldset's `EditorRowList`.
 */
export default function BlueprintCalculationRow({ form, index }: { form: Form; index: number }) {
  const { t } = useTranslation();
  const row = form.values.calculationProperties[index];
  return (
    <Stack gap="sm">
      <Group align="flex-start" gap="sm" wrap="wrap">
        <TextInput
          style={{ flex: 1, minWidth: 140 }}
          label={t("blueprints.field.propertyId")}
          required
          {...form.getInputProps(`calculationProperties.${index}.id`)}
        />
        <TextInput
          style={{ flex: 1, minWidth: 140 }}
          label={t("blueprints.field.title")}
          required
          maxLength={MAX_TITLE_LENGTH}
          {...form.getInputProps(`calculationProperties.${index}.title`)}
        />
        <Select
          style={{ minWidth: 140 }}
          label={t("blueprints.field.type")}
          required
          allowDeselect={false}
          data={[...PROPERTY_TYPES]}
          {...form.getInputProps(`calculationProperties.${index}.type`)}
        />
      </Group>
      <Group align="flex-start" gap="sm" wrap="wrap">
        {row.type === "string" && (
          <Select
            style={{ minWidth: 160 }}
            label={t("blueprints.field.format")}
            data={[...STRING_FORMATS]}
            clearable
            {...form.getInputProps(`calculationProperties.${index}.format`)}
          />
        )}
        {(row.type === "string" || row.type === "object") && (
          <Select
            style={{ minWidth: 160 }}
            label={t("blueprints.field.spec")}
            data={row.type === "string" ? [...STRING_SPECS] : [...OBJECT_SPECS]}
            clearable
            {...form.getInputProps(`calculationProperties.${index}.spec`)}
          />
        )}
        <Switch
          mt={26}
          label={t("blueprints.field.colorized")}
          {...form.getInputProps(`calculationProperties.${index}.colorized`, { type: "checkbox" })}
        />
      </Group>
      <Textarea
        label={t("blueprints.field.calculation")}
        description={t("blueprints.hint.calculation")}
        autosize
        minRows={2}
        styles={{ input: { fontFamily: "var(--mantine-font-family-monospace)" } }}
        {...form.getInputProps(`calculationProperties.${index}.calculation`)}
      />
      {row.colorized && (
        <Textarea
          label={t("blueprints.field.colors")}
          description={t("blueprints.hint.colors")}
          autosize
          minRows={2}
          styles={{ input: { fontFamily: "var(--mantine-font-family-monospace)" } }}
          {...form.getInputProps(`calculationProperties.${index}.colorsJson`)}
        />
      )}
    </Stack>
  );
}
