import { MultiSelect, NumberInput, Select, Stack, TagsInput, Text, Textarea, TextInput } from "@mantine/core";
import type { UseFormReturnType } from "@mantine/form";
import { useTranslation } from "react-i18next";
import { safeJsonParse } from "../utils/blueprintForm";
import type { EntityFormValues, PropertyDefinitionWire } from "../utils/entityForm";

type Form = UseFormReturnType<EntityFormValues>;

// String formats rendered as a monospace multi-line box rather than a single-line TextInput —
// Port's own "long free text" formats.
const MONO_FORMATS = new Set(["yaml", "markdown", "proto"]);
// Monospace on the INPUT only — the `ff` style prop would restyle the label and description too
// (the blueprint rows' idiom).
const MONO_INPUT = { input: { fontFamily: "var(--mantine-font-family-monospace)" } } as const;

/**
 * One property row's widget, keyed by the blueprint's `PropertyDefinition` (the plan's widget
 * table): string -> TextInput (Textarea mono for yaml/markdown/proto, Select for enum, an ISO
 * hint for date-time/timer); number -> NumberInput (Select for enum); boolean -> Select
 * Unset/True/False (the blueprint default-editor tri-state); array -> TagsInput (string/number
 * items), MultiSelect (`items.enum`), or a JSON Textarea (`items.type === "object"`); object ->
 * a JSON Textarea, or two TextInputs for `format: labeled-url`. A stored key the blueprint no
 * longer declares (`draft.unknown`) always renders as a raw JSON row, since its type is
 * unknown — the server's 400 on save is what names the problem.
 */
export default function EntityPropertyField({
  form,
  index,
  definition,
  required,
}: {
  form: Form;
  index: number;
  /** Absent for a stored key the blueprint no longer declares. */
  definition?: PropertyDefinitionWire;
  required: boolean;
}) {
  const { t } = useTranslation();
  const draft = form.values.properties[index];
  const label = definition?.title ?? draft.id;

  if (!definition || draft.unknown) {
    return (
      <Textarea
        label={label}
        description={t("entities.field.unknownPropertyHint")}
        minRows={3}
        autosize
        styles={MONO_INPUT}
        {...form.getInputProps(`properties.${index}.json`)}
      />
    );
  }

  const description = definition.description;
  const common = { label, description, required };

  switch (definition.type) {
    case "boolean":
      return (
        <Select
          {...common}
          allowDeselect={false}
          data={[
            { value: "", label: t("entities.field.unset") },
            { value: "true", label: t("entities.field.true") },
            { value: "false", label: t("entities.field.false") },
          ]}
          {...form.getInputProps(`properties.${index}.bool`)}
        />
      );
    case "number":
      if (definition.enum && definition.enum.length > 0) {
        return (
          <Select
            {...common}
            clearable
            data={definition.enum.map((v) => String(v))}
            {...form.getInputProps(`properties.${index}.text`)}
          />
        );
      }
      return (
        <NumberInput
          {...common}
          {...form.getInputProps(`properties.${index}.text`)}
          value={draft.text === "" ? "" : Number(draft.text)}
          onChange={(v) => form.setFieldValue(`properties.${index}.text`, v === "" ? "" : String(v))}
        />
      );
    case "array": {
      if (definition.items?.type === "object") {
        return (
          <Textarea {...common} minRows={4} autosize styles={MONO_INPUT} {...form.getInputProps(`properties.${index}.json`)} />
        );
      }
      if (definition.items?.enum && definition.items.enum.length > 0) {
        return (
          <MultiSelect
            {...common}
            data={definition.items.enum.map((v) => String(v))}
            {...form.getInputProps(`properties.${index}.list`)}
          />
        );
      }
      if (definition.items?.type === "boolean") {
        return (
          <MultiSelect
            {...common}
            data={[
              { value: "true", label: t("entities.field.true") },
              { value: "false", label: t("entities.field.false") },
            ]}
            {...form.getInputProps(`properties.${index}.list`)}
          />
        );
      }
      return <TagsInput {...common} {...form.getInputProps(`properties.${index}.list`)} />;
    }
    case "object":
      if (definition.format === "labeled-url") {
        return <LabeledUrlFields form={form} index={index} label={label} description={description} required={required} />;
      }
      return (
        <Textarea {...common} minRows={4} autosize styles={MONO_INPUT} {...form.getInputProps(`properties.${index}.json`)} />
      );
    default: {
      // string
      if (definition.enum && definition.enum.length > 0) {
        return (
          <Select
            {...common}
            clearable
            data={definition.enum.map((v) => String(v))}
            {...form.getInputProps(`properties.${index}.text`)}
          />
        );
      }
      if (definition.format && MONO_FORMATS.has(definition.format)) {
        return (
          <Textarea {...common} minRows={3} autosize styles={MONO_INPUT} {...form.getInputProps(`properties.${index}.text`)} />
        );
      }
      const isoHint =
        (definition.format === "date-time" || definition.format === "timer") && description === undefined;
      return (
        <TextInput
          {...common}
          description={description ?? (isoHint ? t("entities.field.isoHint") : undefined)}
          {...form.getInputProps(`properties.${index}.text`)}
        />
      );
    }
  }
}

/** `object` + `format: labeled-url`: exactly `{url, displayText?}`, edited as two TextInputs
 *  over the same `json` storage slot (there is no dedicated fifth draft field for it). */
function LabeledUrlFields({
  form,
  index,
  label,
  description,
  required,
}: {
  form: Form;
  index: number;
  label: string;
  description?: string;
  required: boolean;
}) {
  const { t } = useTranslation();
  const path = `properties.${index}.json` as const;
  const raw = form.values.properties[index].json;
  const parsed = safeJsonParse<{ url?: string; displayText?: string }>(raw) ?? {};

  function update(next: { url?: string; displayText?: string }) {
    const url = (next.url ?? "").trim();
    const displayText = (next.displayText ?? "").trim();
    if (!url && !displayText) {
      form.setFieldValue(path, "");
      return;
    }
    form.setFieldValue(
      path,
      JSON.stringify({ ...(url ? { url } : {}), ...(displayText ? { displayText } : {}) }),
    );
  }

  return (
    <Stack gap={4}>
      <Text size="sm" fw={500}>
        {label}
        {required && (
          <Text component="span" c="red" inherit>
            {" *"}
          </Text>
        )}
      </Text>
      {description && (
        <Text size="xs" c="dimmed">
          {description}
        </Text>
      )}
      <TextInput
        label={t("entities.field.labeledUrlUrl")}
        {...form.getInputProps(path)}
        value={parsed.url ?? ""}
        onChange={(e) => update({ ...parsed, url: e.currentTarget.value })}
      />
      <TextInput
        label={t("entities.field.labeledUrlDisplayText")}
        {...form.getInputProps(path)}
        value={parsed.displayText ?? ""}
        onChange={(e) => update({ ...parsed, displayText: e.currentTarget.value })}
        error={undefined}
      />
    </Stack>
  );
}
