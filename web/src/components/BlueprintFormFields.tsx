import {
  ActionIcon,
  Button,
  Fieldset,
  Group,
  Select,
  Stack,
  Switch,
  Textarea,
  TextInput,
} from "@mantine/core";
import { type UseFormReturnType } from "@mantine/form";
import { IconPlus, IconTrash } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";
import BlueprintPropertyRow from "./BlueprintPropertyRow";
import RowControls from "./RowControls";
import { useBlueprints } from "../hooks/useBlueprints";
import { BELOW_INPUT, charCountDescription } from "../utils/charCount";
import {
  AGGREGATION_CALCULATION_BY,
  AGGREGATION_FUNCS_ENTITIES,
  AGGREGATION_FUNCS_PROPERTY,
  AVERAGE_OF,
  MAX_DESCRIPTION_LENGTH,
  MAX_ICON_LENGTH,
  MAX_IDENTIFIER_LENGTH,
  MAX_TITLE_LENGTH,
  OWNERSHIP_TYPES,
  PROPERTY_TYPES,
  STRING_FORMATS,
  STRING_SPECS,
  OBJECT_SPECS,
  emptyAggregationDraft,
  emptyCalculationDraft,
  emptyMirrorDraft,
  emptyPropertyDraft,
  emptyRelationDraft,
  type BlueprintFormValues,
} from "../utils/blueprintForm";

type Form = UseFormReturnType<BlueprintFormValues>;

/**
 * The relation/aggregation target picker: every OTHER active blueprint plus this blueprint's
 * OWN (live, possibly not-yet-saved) identifier — labelled distinctly since it names a
 * self-reference rather than a registry row. A stale stored value not among either is
 * appended so it keeps displaying.
 */
function TargetSelect({
  value,
  onChange,
  ownIdentifier,
  error,
  label,
}: {
  value: string;
  onChange: (value: string) => void;
  ownIdentifier: string;
  error?: string;
  label: string;
}) {
  const { t } = useTranslation();
  const { blueprints, loading, error: loadError } = useBlueprints();
  const own = ownIdentifier.trim();
  const options = blueprints.filter((b) => b.identifier !== own).map((b) => b.identifier);
  const withSelf = own ? [...options, own] : options;
  const current = value.trim();
  const data = [
    ...withSelf.map((identifier) => ({
      value: identifier,
      label: identifier === own ? t("blueprints.field.targetSelf", { identifier }) : identifier,
    })),
    ...(current && !withSelf.includes(current) ? [{ value: current, label: current }] : []),
  ];
  let hint: string | undefined;
  if (loadError) hint = t("blueprints.targetOptionsFailed");
  else if (!loading && withSelf.length === 0) hint = t("blueprints.noBlueprintsYet");
  return (
    <Select
      label={label}
      required
      data={data}
      searchable
      description={hint}
      value={current || null}
      onChange={(v) => onChange(v ?? "")}
      error={error}
    />
  );
}

function IdentityFields({ form }: { form: Form }) {
  const { t } = useTranslation();
  return (
    <Stack gap="sm">
      <TextInput
        label={t("blueprints.field.identifier")}
        autoFocus
        required
        maxLength={MAX_IDENTIFIER_LENGTH}
        description={charCountDescription(form.values.identifier.length, MAX_IDENTIFIER_LENGTH)}
        inputWrapperOrder={[...BELOW_INPUT]}
        {...form.getInputProps("identifier")}
      />
      <TextInput
        label={t("blueprints.field.title")}
        required
        maxLength={MAX_TITLE_LENGTH}
        description={charCountDescription(form.values.title.length, MAX_TITLE_LENGTH)}
        inputWrapperOrder={[...BELOW_INPUT]}
        {...form.getInputProps("title")}
      />
      <Textarea
        label={t("blueprints.field.description")}
        autosize
        minRows={2}
        maxLength={MAX_DESCRIPTION_LENGTH}
        description={charCountDescription(form.values.description.length, MAX_DESCRIPTION_LENGTH)}
        inputWrapperOrder={[...BELOW_INPUT]}
        {...form.getInputProps("description")}
      />
      <TextInput
        label={t("blueprints.field.icon")}
        maxLength={MAX_ICON_LENGTH}
        description={t("blueprints.hint.icon")}
        {...form.getInputProps("icon")}
      />
    </Stack>
  );
}

function PropertiesFieldset({ form }: { form: Form }) {
  const { t } = useTranslation();
  const rows = form.values.properties;
  return (
    <Fieldset legend={t("blueprints.section.properties")}>
      <Stack gap="md">
        {rows.map((row, index) => (
          <Group key={row.key} align="flex-start" gap="sm" wrap="nowrap">
            <Stack style={{ flex: 1 }}>
              <BlueprintPropertyRow form={form} index={index} />
            </Stack>
            <RowControls
              index={index}
              count={rows.length}
              onMoveUp={() => form.reorderListItem("properties", { from: index, to: index - 1 })}
              onMoveDown={() => form.reorderListItem("properties", { from: index, to: index + 1 })}
              onRemove={() => form.removeListItem("properties", index)}
              moveUpLabel={t("blueprints.movePropertyUp", { position: index + 1 })}
              moveDownLabel={t("blueprints.movePropertyDown", { position: index + 1 })}
              removeLabel={t("blueprints.removePropertyAria", { position: index + 1 })}
            />
          </Group>
        ))}
        <Button
          variant="light"
          size="xs"
          leftSection={<IconPlus size={14} />}
          style={{ alignSelf: "flex-start" }}
          onClick={() => form.insertListItem("properties", emptyPropertyDraft())}
        >
          {t("blueprints.addProperty")}
        </Button>
      </Stack>
    </Fieldset>
  );
}

function RelationsFieldset({ form }: { form: Form }) {
  const { t } = useTranslation();
  return (
    <Fieldset legend={t("blueprints.section.relations")}>
      <Stack gap="md">
        {form.values.relations.map((row, index) => (
          <Group key={row.key} align="flex-start" gap="sm" wrap="wrap">
            <TextInput
              style={{ flex: 1, minWidth: 140 }}
              label={t("blueprints.field.relationId")}
              required
              {...form.getInputProps(`relations.${index}.id`)}
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
            <TargetSelect
              value={row.target}
              onChange={(v) => form.setFieldValue(`relations.${index}.target`, v)}
              ownIdentifier={form.values.identifier}
              error={form.getInputProps(`relations.${index}.target`).error}
              label={t("blueprints.field.target")}
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
            <ActionIcon
              variant="subtle"
              color="red"
              mt={26}
              aria-label={t("blueprints.removeRelationAria", { position: index + 1 })}
              onClick={() => form.removeListItem("relations", index)}
            >
              <IconTrash size={16} />
            </ActionIcon>
          </Group>
        ))}
        <Button
          variant="light"
          size="xs"
          leftSection={<IconPlus size={14} />}
          style={{ alignSelf: "flex-start" }}
          onClick={() => form.insertListItem("relations", emptyRelationDraft())}
        >
          {t("blueprints.addRelation")}
        </Button>
      </Stack>
    </Fieldset>
  );
}

function MirrorFieldset({ form }: { form: Form }) {
  const { t } = useTranslation();
  return (
    <Fieldset legend={t("blueprints.section.mirrorProperties")}>
      <Stack gap="md">
        {form.values.mirrorProperties.map((row, index) => (
          <Group key={row.key} align="flex-start" gap="sm" wrap="wrap">
            <TextInput
              style={{ flex: 1, minWidth: 140 }}
              label={t("blueprints.field.propertyId")}
              required
              {...form.getInputProps(`mirrorProperties.${index}.id`)}
            />
            <TextInput
              style={{ flex: 1, minWidth: 140 }}
              label={t("blueprints.field.title")}
              required
              maxLength={MAX_TITLE_LENGTH}
              {...form.getInputProps(`mirrorProperties.${index}.title`)}
            />
            <TextInput
              style={{ flex: 2, minWidth: 200 }}
              label={t("blueprints.field.path")}
              description={t("blueprints.hint.path")}
              required
              {...form.getInputProps(`mirrorProperties.${index}.path`)}
            />
            <ActionIcon
              variant="subtle"
              color="red"
              mt={26}
              aria-label={t("blueprints.removeMirrorAria", { position: index + 1 })}
              onClick={() => form.removeListItem("mirrorProperties", index)}
            >
              <IconTrash size={16} />
            </ActionIcon>
          </Group>
        ))}
        <Button
          variant="light"
          size="xs"
          leftSection={<IconPlus size={14} />}
          style={{ alignSelf: "flex-start" }}
          onClick={() => form.insertListItem("mirrorProperties", emptyMirrorDraft())}
        >
          {t("blueprints.addMirror")}
        </Button>
      </Stack>
    </Fieldset>
  );
}

function CalculationFieldset({ form }: { form: Form }) {
  const { t } = useTranslation();
  return (
    <Fieldset legend={t("blueprints.section.calculationProperties")}>
      <Stack gap="md">
        {form.values.calculationProperties.map((row, index) => (
          <Stack key={row.key} gap="sm" pb="sm">
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
              <ActionIcon
                variant="subtle"
                color="red"
                mt={26}
                aria-label={t("blueprints.removeCalculationAria", { position: index + 1 })}
                onClick={() => form.removeListItem("calculationProperties", index)}
              >
                <IconTrash size={16} />
              </ActionIcon>
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
        ))}
        <Button
          variant="light"
          size="xs"
          leftSection={<IconPlus size={14} />}
          style={{ alignSelf: "flex-start" }}
          onClick={() => form.insertListItem("calculationProperties", emptyCalculationDraft())}
        >
          {t("blueprints.addCalculation")}
        </Button>
      </Stack>
    </Fieldset>
  );
}

function AggregationFieldset({ form }: { form: Form }) {
  const { t } = useTranslation();
  return (
    <Fieldset legend={t("blueprints.section.aggregationProperties")}>
      <Stack gap="md">
        {form.values.aggregationProperties.map((row, index) => (
          <Stack key={row.key} gap="sm" pb="sm">
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
              <TargetSelect
                value={row.target}
                onChange={(v) => form.setFieldValue(`aggregationProperties.${index}.target`, v)}
                ownIdentifier={form.values.identifier}
                error={form.getInputProps(`aggregationProperties.${index}.target`).error}
                label={t("blueprints.field.target")}
              />
              <ActionIcon
                variant="subtle"
                color="red"
                mt={26}
                aria-label={t("blueprints.removeAggregationAria", { position: index + 1 })}
                onClick={() => form.removeListItem("aggregationProperties", index)}
              >
                <IconTrash size={16} />
              </ActionIcon>
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
        ))}
        <Button
          variant="light"
          size="xs"
          leftSection={<IconPlus size={14} />}
          style={{ alignSelf: "flex-start" }}
          onClick={() => form.insertListItem("aggregationProperties", emptyAggregationDraft())}
        >
          {t("blueprints.addAggregation")}
        </Button>
      </Stack>
    </Fieldset>
  );
}

function OwnershipFieldset({ form }: { form: Form }) {
  const { t } = useTranslation();
  return (
    <Fieldset legend={t("blueprints.section.ownership")}>
      <Stack gap="sm">
        <Select
          label={t("blueprints.field.ownershipType")}
          data={[
            { value: "", label: t("blueprints.field.ownershipNone") },
            ...OWNERSHIP_TYPES.map((type) => ({ value: type, label: type })),
          ]}
          value={form.values.ownershipType || ""}
          onChange={(v) => form.setFieldValue("ownershipType", v ?? "")}
          allowDeselect={false}
        />
        {form.values.ownershipType && (
          <TextInput
            label={t("blueprints.field.title")}
            maxLength={MAX_TITLE_LENGTH}
            {...form.getInputProps("ownershipTitle")}
          />
        )}
        {form.values.ownershipType === "Inherited" && (
          <TextInput
            label={t("blueprints.field.path")}
            description={t("blueprints.hint.path")}
            required
            {...form.getInputProps("ownershipPath")}
          />
        )}
      </Stack>
    </Fieldset>
  );
}

/**
 * The field block for the Blueprint editor (create/edit pages own submit/error handling and
 * the JSON preview). Identity fields first, then the six Port fieldsets in schema order.
 */
export default function BlueprintFormFields({ form }: { form: Form }) {
  return (
    <Stack gap="md">
      <IdentityFields form={form} />
      <PropertiesFieldset form={form} />
      <RelationsFieldset form={form} />
      <MirrorFieldset form={form} />
      <CalculationFieldset form={form} />
      <AggregationFieldset form={form} />
      <OwnershipFieldset form={form} />
    </Stack>
  );
}
