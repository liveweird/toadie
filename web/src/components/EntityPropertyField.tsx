import { MultiSelect, NumberInput, Select, Stack, TagsInput, Text, Textarea, TextInput } from "@mantine/core";
import type { UseFormReturnType } from "@mantine/form";
import { useTranslation } from "react-i18next";
import EntityOptionsSelect from "./EntityOptionsSelect";
import { safeJsonParse } from "../utils/blueprintForm";
import { entityFindingProps } from "../utils/entityFieldFindings";
import type { EntityFinding } from "../api/entities";
import type { EntityFormValues, PropertyDefinitionWire } from "../utils/entityForm";
import { referenceTargetOf, TEAM_BLUEPRINT } from "../utils/systemBlueprints";

type Form = UseFormReturnType<EntityFormValues>;

// String formats rendered as a monospace multi-line box rather than a single-line TextInput —
// Port's own "long free text" formats.
const MONO_FORMATS = new Set(["yaml", "markdown", "proto"]);
// Monospace on the INPUT only — the `ff` style prop would restyle the label and description too
// (the blueprint rows' idiom).
const MONO_INPUT = { input: { fontFamily: "var(--mantine-font-family-monospace)" } } as const;

type FindingProps = ReturnType<typeof entityFindingProps>;

/**
 * `format: team` / `format: user` widget — a scalar string property renders a single Select,
 * an array of string items with that item format renders a MultiSelect, both over
 * `EntityOptionsSelect` (extracted so the parent switch below stays inside sonarjs's
 * complexity backstop). The failed-load hint names which system blueprint is unreachable.
 */
function ReferencePropertyField({
  form,
  index,
  target,
  many,
  label,
  description,
  required,
  findingProps,
}: {
  form: Form;
  index: number;
  target: string;
  many: boolean;
  label: string;
  description?: string;
  required: boolean;
  findingProps: FindingProps;
}) {
  const { t } = useTranslation();
  const failedHint = target === TEAM_BLUEPRINT ? t("entities.field.teamOptionsFailed") : t("entities.field.userOptionsFailed");
  const emptyHint = t("entities.editor.noTargets");

  if (many) {
    return (
      <EntityOptionsSelect
        mode="multi"
        target={target}
        label={label}
        description={description}
        required={required}
        failedHint={failedHint}
        emptyHint={emptyHint}
        inputProps={form.getInputProps(`properties.${index}.list`)}
        findingProps={findingProps}
      />
    );
  }
  return (
    <EntityOptionsSelect
      mode="single"
      target={target}
      label={label}
      description={description}
      required={required}
      clearable={!required}
      failedHint={failedHint}
      emptyHint={emptyHint}
      inputProps={form.getInputProps(`properties.${index}.text`)}
      findingProps={findingProps}
    />
  );
}

/**
 * One property row's widget, keyed by the blueprint's `PropertyDefinition` (the plan's widget
 * table): string -> TextInput (Textarea mono for yaml/markdown/proto, Select for enum, a
 * `ReferencePropertyField` Select for `format: team|user`, an ISO hint for date-time/timer);
 * number -> NumberInput (Select for enum); boolean -> Select Unset/True/False (the blueprint
 * default-editor tri-state); array -> TagsInput (string/number items), MultiSelect
 * (`items.enum`, or `ReferencePropertyField` for `items.format: team|user`), or a JSON
 * Textarea (`items.type === "object"`); object -> a JSON Textarea, or two TextInputs for
 * `format: labeled-url`. A stored key the blueprint no longer declares (`draft.unknown`)
 * always renders as a raw JSON row, since its type is unknown — the server's 400 on save is
 * what names the problem. Every widget gets its field's soft-finding props (Phase 4 ownership
 * findings against `properties.<id>` — `TEAM_TARGET_MISSING`/`USER_TARGET_MISSING`, but also
 * any other property finding a stale blueprint edit leaves behind), spread AFTER
 * `getInputProps` so a real client validation error still wins.
 */
export default function EntityPropertyField({
  form,
  index,
  definition,
  required,
  findings = [],
}: {
  form: Form;
  index: number;
  /** Absent for a stored key the blueprint no longer declares. */
  definition?: PropertyDefinitionWire;
  required: boolean;
  findings?: readonly EntityFinding[];
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
        {...entityFindingProps(findings, t, { hardError: form.errors[`properties.${index}.json`] })}
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
          {...entityFindingProps(findings, t, { hardError: form.errors[`properties.${index}.bool`] })}
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
            {...entityFindingProps(findings, t, { hardError: form.errors[`properties.${index}.text`] })}
          />
        );
      }
      return (
        <NumberInput
          {...common}
          {...form.getInputProps(`properties.${index}.text`)}
          value={draft.text === "" ? "" : Number(draft.text)}
          onChange={(v) => form.setFieldValue(`properties.${index}.text`, v === "" ? "" : String(v))}
          {...entityFindingProps(findings, t, { hardError: form.errors[`properties.${index}.text`] })}
        />
      );
    case "array": {
      const itemsTarget = referenceTargetOf(definition.items?.format);
      if (itemsTarget && definition.items?.type === "string") {
        return (
          <ReferencePropertyField
            form={form}
            index={index}
            target={itemsTarget}
            many
            label={label}
            description={description}
            required={required}
            findingProps={entityFindingProps(findings, t, { hardError: form.errors[`properties.${index}.list`] })}
          />
        );
      }
      if (definition.items?.type === "object") {
        return (
          <Textarea
            {...common}
            minRows={4}
            autosize
            styles={MONO_INPUT}
            {...form.getInputProps(`properties.${index}.json`)}
            {...entityFindingProps(findings, t, { hardError: form.errors[`properties.${index}.json`] })}
          />
        );
      }
      if (definition.items?.enum && definition.items.enum.length > 0) {
        return (
          <MultiSelect
            {...common}
            data={definition.items.enum.map((v) => String(v))}
            {...form.getInputProps(`properties.${index}.list`)}
            {...entityFindingProps(findings, t, { hardError: form.errors[`properties.${index}.list`] })}
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
            {...entityFindingProps(findings, t, { hardError: form.errors[`properties.${index}.list`] })}
          />
        );
      }
      return (
        <TagsInput
          {...common}
          {...form.getInputProps(`properties.${index}.list`)}
          {...entityFindingProps(findings, t, { hardError: form.errors[`properties.${index}.list`] })}
        />
      );
    }
    case "object":
      if (definition.format === "labeled-url") {
        return <LabeledUrlFields form={form} index={index} label={label} description={description} required={required} />;
      }
      return (
        <Textarea
          {...common}
          minRows={4}
          autosize
          styles={MONO_INPUT}
          {...form.getInputProps(`properties.${index}.json`)}
          {...entityFindingProps(findings, t, { hardError: form.errors[`properties.${index}.json`] })}
        />
      );
    default: {
      // string
      const target = referenceTargetOf(definition.format);
      if (target) {
        return (
          <ReferencePropertyField
            form={form}
            index={index}
            target={target}
            many={false}
            label={label}
            description={description}
            required={required}
            findingProps={entityFindingProps(findings, t, { hardError: form.errors[`properties.${index}.text`] })}
          />
        );
      }
      if (definition.enum && definition.enum.length > 0) {
        return (
          <Select
            {...common}
            clearable
            data={definition.enum.map((v) => String(v))}
            {...form.getInputProps(`properties.${index}.text`)}
            {...entityFindingProps(findings, t, { hardError: form.errors[`properties.${index}.text`] })}
          />
        );
      }
      if (definition.format && MONO_FORMATS.has(definition.format)) {
        return (
          <Textarea
            {...common}
            minRows={3}
            autosize
            styles={MONO_INPUT}
            {...form.getInputProps(`properties.${index}.text`)}
            {...entityFindingProps(findings, t, { hardError: form.errors[`properties.${index}.text`] })}
          />
        );
      }
      const isoHint =
        (definition.format === "date-time" || definition.format === "timer") && description === undefined;
      return (
        <TextInput
          {...common}
          description={description ?? (isoHint ? t("entities.field.isoHint") : undefined)}
          {...form.getInputProps(`properties.${index}.text`)}
          {...entityFindingProps(findings, t, { hardError: form.errors[`properties.${index}.text`] })}
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
