import { Group, Select, Stack, Textarea, TextInput } from "@mantine/core";
import { type UseFormReturnType } from "@mantine/form";
import { useTranslation } from "react-i18next";
import BlueprintTargetSelect from "./BlueprintTargetSelect";
import {
  AGGREGATION_CALCULATION_BY,
  AGGREGATION_FUNCS_ENTITIES,
  AGGREGATION_FUNCS_PROPERTY,
  AVERAGE_OF,
  MAX_TITLE_LENGTH,
  type BlueprintFormValues,
} from "../utils/blueprintForm";

type Form = UseFormReturnType<BlueprintFormValues>;

/**
 * One aggregation property row's fields: identifier/title/target, the calculation-by/func
 * matrix (+ the property/averageOf/measureTimeBy fields it conditionally reveals), and the
 * raw query/pathFilter JSON. Removal/reordering lives one level up, in AggregationFieldset's
 * `EditorRowList`.
 */
export default function BlueprintAggregationRow({ form, index }: { form: Form; index: number }) {
  const { t } = useTranslation();
  const row = form.values.aggregationProperties[index];
  return (
    <Stack gap="sm">
      <Group align="flex-start" gap="sm" wrap="wrap">
        <TextInput
          style={{ flex: 1, minWidth: 140 }}
          label={t("blueprints.field.propertyId")}
          required
          {...form.getInputProps(`aggregationProperties.${index}.id`)}
        />
        <TextInput
          style={{ flex: 1, minWidth: 140 }}
          label={t("blueprints.field.title")}
          required
          maxLength={MAX_TITLE_LENGTH}
          {...form.getInputProps(`aggregationProperties.${index}.title`)}
        />
        <BlueprintTargetSelect
          value={row.target}
          onChange={(v) => form.setFieldValue(`aggregationProperties.${index}.target`, v)}
          ownIdentifier={form.values.identifier}
          error={form.getInputProps(`aggregationProperties.${index}.target`).error}
          label={t("blueprints.field.target")}
        />
      </Group>
      <Group align="flex-start" gap="sm" wrap="wrap">
        <Select
          style={{ minWidth: 160 }}
          label={t("blueprints.field.calculationBy")}
          required
          allowDeselect={false}
          data={[...AGGREGATION_CALCULATION_BY]}
          {...form.getInputProps(`aggregationProperties.${index}.calculationBy`)}
        />
        <Select
          style={{ minWidth: 160 }}
          label={t("blueprints.field.func")}
          required
          allowDeselect={false}
          data={[...(row.calculationBy === "property" ? AGGREGATION_FUNCS_PROPERTY : AGGREGATION_FUNCS_ENTITIES)]}
          {...form.getInputProps(`aggregationProperties.${index}.func`)}
        />
        {row.calculationBy === "property" && (
          <TextInput
            style={{ minWidth: 160 }}
            label={t("blueprints.field.property")}
            required
            {...form.getInputProps(`aggregationProperties.${index}.property`)}
          />
        )}
        {row.func === "average" && (
          <>
            <Select
              style={{ minWidth: 160 }}
              label={t("blueprints.field.averageOf")}
              data={[...AVERAGE_OF]}
              clearable
              {...form.getInputProps(`aggregationProperties.${index}.averageOf`)}
            />
            <TextInput
              style={{ minWidth: 160 }}
              label={t("blueprints.field.measureTimeBy")}
              {...form.getInputProps(`aggregationProperties.${index}.measureTimeBy`)}
            />
          </>
        )}
      </Group>
      <Textarea
        label={t("blueprints.field.query")}
        description={t("blueprints.hint.query")}
        autosize
        minRows={2}
        styles={{ input: { fontFamily: "var(--mantine-font-family-monospace)" } }}
        {...form.getInputProps(`aggregationProperties.${index}.queryJson`)}
      />
      <Textarea
        label={t("blueprints.field.pathFilter")}
        autosize
        minRows={2}
        styles={{ input: { fontFamily: "var(--mantine-font-family-monospace)" } }}
        {...form.getInputProps(`aggregationProperties.${index}.pathFilterJson`)}
      />
    </Stack>
  );
}
