import { useState } from "react";
import {
  Box,
  Button,
  ColorSwatch,
  Group,
  NumberInput,
  Select,
  Stack,
  Switch,
  TagsInput,
  Text,
  Textarea,
  TextInput,
} from "@mantine/core";
import { type UseFormReturnType } from "@mantine/form";
import { IconChevronDown, IconChevronRight } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";
import {
  ARRAY_ITEM_TYPES,
  DATE_FORMATS,
  ENUM_COLORS,
  MAX_ICON_LENGTH,
  MAX_TITLE_LENGTH,
  OBJECT_FORMATS,
  OBJECT_SPECS,
  PROPERTY_TYPES,
  STRING_FORMATS,
  STRING_SPECS,
  type BlueprintFormValues,
} from "../utils/blueprintForm";

type Form = UseFormReturnType<BlueprintFormValues>;

/** The colour Select fed a ColorSwatch leftSection, shared by the property row's enum editor
 *  and the calculation properties' colorized state. */
function ColorSelect({
  value,
  onChange,
  ariaLabel,
}: {
  value: string;
  onChange: (value: string) => void;
  ariaLabel: string;
}) {
  return (
    <Select
      aria-label={ariaLabel}
      w={140}
      data={[...ENUM_COLORS]}
      value={value || null}
      onChange={(v) => onChange(v ?? "")}
      leftSection={value ? <ColorSwatch color={value} size={14} /> : undefined}
      clearable
    />
  );
}

/** The enum values + per-value colour editor shared by string and number properties. */
function EnumEditor({ form, index }: { form: Form; index: number }) {
  const { t } = useTranslation();
  const values = form.values.properties[index].enumValues;
  return (
    <Stack gap="xs">
      <TagsInput
        label={t("blueprints.field.enumValues")}
        description={t("blueprints.hint.enumValues")}
        splitChars={[","]}
        {...form.getInputProps(`properties.${index}.enumValues`)}
      />
      {values.length > 0 && (
        <Stack gap={4}>
          {values.map((value) => (
            <Group key={value} gap="xs" wrap="nowrap">
              <Text size="sm" style={{ flex: 1 }} truncate>
                {value}
              </Text>
              <ColorSelect
                value={form.values.properties[index].enumColors[value] ?? ""}
                onChange={(color) =>
                  form.setFieldValue(`properties.${index}.enumColors`, {
                    ...form.values.properties[index].enumColors,
                    ...(color ? { [value]: color } : {}),
                    ...(color ? {} : Object.fromEntries(
                      Object.entries(form.values.properties[index].enumColors).filter(([k]) => k !== value),
                    )),
                  })
                }
                ariaLabel={t("blueprints.field.enumColorAria", { value })}
              />
            </Group>
          ))}
        </Stack>
      )}
    </Stack>
  );
}

function StringDefault({ form, index }: { form: Form; index: number }) {
  const { t } = useTranslation();
  const draft = form.values.properties[index];
  if (draft.enumValues.length > 0) {
    return (
      <Select
        label={t("blueprints.field.default")}
        data={draft.enumValues}
        clearable
        {...form.getInputProps(`properties.${index}.defaultText`)}
      />
    );
  }
  return <TextInput label={t("blueprints.field.default")} {...form.getInputProps(`properties.${index}.defaultText`)} />;
}

function StringPropertyFields({ form, index }: { form: Form; index: number }) {
  const { t } = useTranslation();
  const draft = form.values.properties[index];
  return (
    <Stack gap="sm">
      <Group grow align="flex-start">
        <Select
          label={t("blueprints.field.format")}
          data={[...STRING_FORMATS]}
          clearable
          {...form.getInputProps(`properties.${index}.format`)}
        />
        {draft.format === "date-time" && (
          <Select
            label={t("blueprints.field.dateFormat")}
            data={[...DATE_FORMATS]}
            clearable
            {...form.getInputProps(`properties.${index}.dateFormat`)}
          />
        )}
      </Group>
      <TextInput
        label={t("blueprints.field.pattern")}
        description={t("blueprints.hint.pattern")}
        {...form.getInputProps(`properties.${index}.pattern`)}
      />
      <Group grow align="flex-start">
        <NumberInput
          label={t("blueprints.field.minLength")}
          min={0}
          {...form.getInputProps(`properties.${index}.minLength`)}
        />
        <NumberInput
          label={t("blueprints.field.maxLength")}
          min={0}
          {...form.getInputProps(`properties.${index}.maxLength`)}
        />
      </Group>
      <Select
        label={t("blueprints.field.spec")}
        data={[...STRING_SPECS]}
        clearable
        {...form.getInputProps(`properties.${index}.spec`)}
      />
      {draft.spec === "embedded-url" && (
        <Stack gap="sm">
          <TextInput
            label={t("blueprints.field.specAuthorizationUrl")}
            {...form.getInputProps(`properties.${index}.specAuthorizationUrl`)}
          />
          <TextInput
            label={t("blueprints.field.specTokenUrl")}
            {...form.getInputProps(`properties.${index}.specTokenUrl`)}
          />
          <TextInput
            label={t("blueprints.field.specClientId")}
            {...form.getInputProps(`properties.${index}.specClientId`)}
          />
          <TagsInput
            label={t("blueprints.field.specAuthorizationScope")}
            splitChars={[",", " "]}
            {...form.getInputProps(`properties.${index}.specAuthorizationScope`)}
          />
        </Stack>
      )}
      <EnumEditor form={form} index={index} />
      <StringDefault form={form} index={index} />
    </Stack>
  );
}

function NumberDefault({ form, index }: { form: Form; index: number }) {
  const { t } = useTranslation();
  const draft = form.values.properties[index];
  if (draft.enumValues.length > 0) {
    return (
      <Select
        label={t("blueprints.field.default")}
        data={draft.enumValues}
        clearable
        {...form.getInputProps(`properties.${index}.defaultText`)}
      />
    );
  }
  return <NumberInput label={t("blueprints.field.default")} {...form.getInputProps(`properties.${index}.defaultText`)} />;
}

function NumberPropertyFields({ form, index }: { form: Form; index: number }) {
  const { t } = useTranslation();
  return (
    <Stack gap="sm">
      <Group grow align="flex-start">
        <NumberInput label={t("blueprints.field.minimum")} {...form.getInputProps(`properties.${index}.minimum`)} />
        <NumberInput label={t("blueprints.field.maximum")} {...form.getInputProps(`properties.${index}.maximum`)} />
      </Group>
      <Group grow align="flex-start">
        <NumberInput
          label={t("blueprints.field.exclusiveMinimum")}
          {...form.getInputProps(`properties.${index}.exclusiveMinimum`)}
        />
        <NumberInput
          label={t("blueprints.field.exclusiveMaximum")}
          {...form.getInputProps(`properties.${index}.exclusiveMaximum`)}
        />
      </Group>
      <EnumEditor form={form} index={index} />
      <NumberDefault form={form} index={index} />
    </Stack>
  );
}

function BooleanPropertyFields({ form, index }: { form: Form; index: number }) {
  const { t } = useTranslation();
  return (
    <Select
      label={t("blueprints.field.default")}
      data={[
        { value: "", label: t("blueprints.field.defaultUnset") },
        { value: "true", label: t("blueprints.field.defaultTrue") },
        { value: "false", label: t("blueprints.field.defaultFalse") },
      ]}
      value={form.values.properties[index].defaultBool}
      onChange={(v) => form.setFieldValue(`properties.${index}.defaultBool`, (v ?? "") as "" | "true" | "false")}
      allowDeselect={false}
    />
  );
}

function ArrayPropertyFields({ form, index }: { form: Form; index: number }) {
  const { t } = useTranslation();
  const draft = form.values.properties[index];
  return (
    <Stack gap="sm">
      <Group grow align="flex-start">
        <Select
          label={t("blueprints.field.itemsType")}
          required
          data={[...ARRAY_ITEM_TYPES]}
          {...form.getInputProps(`properties.${index}.itemsType`)}
        />
        {draft.itemsType === "string" && (
          <Select
            label={t("blueprints.field.format")}
            data={[...STRING_FORMATS]}
            clearable
            {...form.getInputProps(`properties.${index}.itemsFormat`)}
          />
        )}
      </Group>
      <Group grow align="flex-start">
        <NumberInput label={t("blueprints.field.minItems")} min={0} {...form.getInputProps(`properties.${index}.minItems`)} />
        <NumberInput label={t("blueprints.field.maxItems")} min={0} {...form.getInputProps(`properties.${index}.maxItems`)} />
      </Group>
      <Switch
        label={t("blueprints.field.uniqueItems")}
        {...form.getInputProps(`properties.${index}.uniqueItems`, { type: "checkbox" })}
      />
      <TagsInput
        label={t("blueprints.field.default")}
        splitChars={[",", " "]}
        {...form.getInputProps(`properties.${index}.defaultList`)}
      />
    </Stack>
  );
}

function ObjectPropertyFields({ form, index }: { form: Form; index: number }) {
  const { t } = useTranslation();
  return (
    <Stack gap="sm">
      <Group grow align="flex-start">
        <Select
          label={t("blueprints.field.format")}
          data={[...OBJECT_FORMATS]}
          clearable
          {...form.getInputProps(`properties.${index}.objectFormat`)}
        />
        <Select
          label={t("blueprints.field.spec")}
          data={[...OBJECT_SPECS]}
          clearable
          {...form.getInputProps(`properties.${index}.objectSpec`)}
        />
      </Group>
      <Textarea
        label={t("blueprints.field.objectSchemaJson")}
        description={t("blueprints.hint.objectSchemaJson")}
        autosize
        minRows={3}
        styles={{ input: { fontFamily: "var(--mantine-font-family-monospace)" } }}
        {...form.getInputProps(`properties.${index}.objectSchemaJson`)}
      />
      <Textarea
        label={t("blueprints.field.default")}
        autosize
        minRows={2}
        styles={{ input: { fontFamily: "var(--mantine-font-family-monospace)" } }}
        {...form.getInputProps(`properties.${index}.defaultText`)}
      />
    </Stack>
  );
}

function TypeSpecificFields({ form, index }: { form: Form; index: number }) {
  const type = form.values.properties[index].type;
  if (type === "string") return <StringPropertyFields form={form} index={index} />;
  if (type === "number") return <NumberPropertyFields form={form} index={index} />;
  if (type === "boolean") return <BooleanPropertyFields form={form} index={index} />;
  if (type === "array") return <ArrayPropertyFields form={form} index={index} />;
  return <ObjectPropertyFields form={form} index={index} />;
}

/** The Advanced fieldset (description + icon) — the two fields every property type carries
 *  but that most schemas leave blank, so they hide behind a toggle rather than crowd the row. */
function AdvancedFields({ form, index }: { form: Form; index: number }) {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(false);
  return (
    <Box>
      <Button
        variant="subtle"
        size="xs"
        color="gray"
        px={0}
        aria-expanded={expanded}
        leftSection={expanded ? <IconChevronDown size={14} /> : <IconChevronRight size={14} />}
        onClick={() => setExpanded((v) => !v)}
      >
        {t("blueprints.advanced")}
      </Button>
      {expanded && (
        <Stack gap="sm" mt="xs">
          <Textarea
            label={t("blueprints.field.description")}
            autosize
            minRows={2}
            {...form.getInputProps(`properties.${index}.description`)}
          />
          <TextInput
            label={t("blueprints.field.icon")}
            maxLength={MAX_ICON_LENGTH}
            description={t("blueprints.hint.icon")}
            {...form.getInputProps(`properties.${index}.icon`)}
          />
        </Stack>
      )}
    </Box>
  );
}

/**
 * One property row: identifier, type, title, required on the first line, then the
 * type-specific block (switching wholesale on `type` — propertyFieldApplies keeps unrelated
 * wire fields from carrying stale values across a type switch), then the Advanced toggle for
 * the two fields every type has but few schemas set. Removal/reordering is the enclosing
 * PropertiesFieldset's RowControls — the shared reorder-editor unit used everywhere else in
 * the app (Namespaces, Lifecycles) — rather than a second, redundant remove control here.
 */
export default function BlueprintPropertyRow({ form, index }: { form: Form; index: number }) {
  const { t } = useTranslation();
  return (
    <Stack gap="sm" data-testid={`property-row-${index}`}>
      <Group align="flex-start" gap="sm" wrap="wrap">
        <TextInput
          style={{ flex: 1, minWidth: 160 }}
          label={t("blueprints.field.propertyId")}
          required
          {...form.getInputProps(`properties.${index}.id`)}
        />
        <Select
          style={{ minWidth: 140 }}
          label={t("blueprints.field.type")}
          required
          allowDeselect={false}
          data={[...PROPERTY_TYPES]}
          {...form.getInputProps(`properties.${index}.type`)}
        />
        <TextInput
          style={{ flex: 1, minWidth: 160 }}
          label={t("blueprints.field.propertyTitle")}
          maxLength={MAX_TITLE_LENGTH}
          required
          {...form.getInputProps(`properties.${index}.title`)}
        />
        <Switch
          mt={26}
          label={t("blueprints.field.required")}
          {...form.getInputProps(`properties.${index}.required`, { type: "checkbox" })}
        />
      </Group>
      <TypeSpecificFields form={form} index={index} />
      <AdvancedFields form={form} index={index} />
    </Stack>
  );
}
