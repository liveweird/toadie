import { ActionIcon, Button, Fieldset, Group, Select, Stack, Text, TextInput } from "@mantine/core";
import { type UseFormReturnType } from "@mantine/form";
import { IconPlus, IconTrash } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";
import { useAnnotationKeys } from "../hooks/useAnnotationKeys";
import { useLabels } from "../hooks/useLabels";
import { type CatalogFileFormValues } from "../utils/catalogFileForm";
import { type FieldFindings } from "../utils/fieldFindings";
import { findingProps } from "../utils/findingProps";

type CatalogForm = UseFormReturnType<CatalogFileFormValues>;

/**
 * The labels editor — registry-constrained pickers, NOT free key/value inputs: catalog
 * writes accept only ADMIN-registered labels allowed for the document's kind, each with a
 * value from that label's closed list (server-enforced, strict). A stored key or value no
 * longer offered (label removed/renamed/narrowed since the file was saved) is appended to
 * its own row's options so it keeps displaying — the server's 400 then names the problem
 * on save. A failed registry load disables adding but keeps existing rows rendered.
 */
export function LabelsFieldset({ form, findings }: { form: CatalogForm; findings: FieldFindings }) {
  const { t } = useTranslation();
  // A label finding names the row by key, and carries `key=value` when the VALUE is the
  // problem — so the two controls get different findings from the same wire field.
  const keyFinding = (key: string) => (findings.forLabelKey(key) ? [findings.forLabelKey(key)!] : []);
  const valueFinding = (key: string) =>
    findings.forLabelValue(key) ? [findings.forLabelValue(key)!] : [];
  const { labels, loading, error } = useLabels();
  const kind = form.values.kind;
  const allowed = labels.filter((label) => label.kinds.includes(kind));
  const usedKeys = new Set(form.values.labels.map((row) => row.key));
  return (
    <Fieldset legend={t("catalog.section.labels")}>
      <Stack gap="sm">
        {form.values.labels.map((row, index) => {
          const rowLabel = labels.find((label) => label.key === row.key);
          // A key stays offered to the row that holds it; other rows can't duplicate it.
          const keyOptions = allowed
            .map((label) => label.key)
            .filter((key) => key === row.key || !usedKeys.has(key));
          const keyData = row.key && !keyOptions.includes(row.key) ? [...keyOptions, row.key] : keyOptions;
          const valueOptions = rowLabel ? [...rowLabel.values] : [];
          const valueData =
            row.value && !valueOptions.includes(row.value) ? [...valueOptions, row.value] : valueOptions;
          return (
            // Rows have no identity beyond position — Mantine's form list helpers are index-addressed.
            <Group key={`labels-${index}`} align="flex-start" gap="sm" wrap="nowrap">
              <Select
                style={{ flex: 1 }}
                aria-label={t("catalog.labelKeyAria", { index: index + 1 })}
                placeholder={t("catalog.field.key")}
                data={keyData}
                searchable
                value={row.key || null}
                onChange={(value) => {
                  form.setFieldValue(`labels.${index}.key`, value ?? "");
                  // The closed value list is per-key — a key change invalidates the value.
                  form.setFieldValue(`labels.${index}.value`, "");
                }}
                error={form.getInputProps(`labels.${index}.key`).error}
                {...findingProps(keyFinding(row.key), t, {
                  hardError: form.getInputProps(`labels.${index}.key`).error,
                })}
              />
              <Select
                style={{ flex: 1 }}
                aria-label={t("catalog.labelValueAria", { index: index + 1 })}
                placeholder={t("catalog.field.value")}
                data={valueData}
                searchable
                value={row.value || null}
                onChange={(value) => form.setFieldValue(`labels.${index}.value`, value ?? "")}
                error={form.getInputProps(`labels.${index}.value`).error}
                {...findingProps(valueFinding(row.key), t, {
                  hardError: form.getInputProps(`labels.${index}.value`).error,
                })}
              />
              <ActionIcon
                variant="subtle"
                color="red"
                mt={4}
                aria-label={t("catalog.removeLabelAria", { index: index + 1 })}
                onClick={() => form.removeListItem("labels", index)}
              >
                <IconTrash size={16} />
              </ActionIcon>
            </Group>
          );
        })}
        {error ? (
          <Text size="sm" c="dimmed">
            {t("catalog.labelOptionsFailed")}
          </Text>
        ) : (
          // Not while loading — an in-flight registry fetch is not "none defined".
          !loading &&
          allowed.length === 0 && (
            <Text size="sm" c="dimmed">
              {t("catalog.noLabelsForKind", { kind })}
            </Text>
          )
        )}
        <Button
          variant="light"
          size="xs"
          leftSection={<IconPlus size={14} />}
          style={{ alignSelf: "flex-start" }}
          disabled={allowed.length === 0}
          onClick={() => form.insertListItem("labels", { key: "", value: "" })}
        >
          {t("catalog.addLabel")}
        </Button>
      </Stack>
    </Fieldset>
  );
}

/**
 * The annotations editor — the KEY is a registry-constrained picker (catalog writes accept
 * only ADMIN-registered annotation keys allowed for the document's kind — server-enforced,
 * strict) while the VALUE stays a free text input (values are not registry-checked). A
 * stored key no longer offered is appended to its own row's options so it keeps displaying
 * — the server's 400 then names the problem on save. A failed registry load disables
 * adding but keeps existing rows rendered.
 */
export function AnnotationsFieldset({ form, findings }: { form: CatalogForm; findings: FieldFindings }) {
  const { t } = useTranslation();
  const keyFinding = (key: string) =>
    findings.forAnnotationKey(key) ? [findings.forAnnotationKey(key)!] : [];
  const { annotationKeys, loading, error } = useAnnotationKeys();
  const kind = form.values.kind;
  const allowed = annotationKeys.filter((row) => row.kinds.includes(kind));
  const usedKeys = new Set(form.values.annotations.map((row) => row.key));
  return (
    <Fieldset legend={t("catalog.section.annotations")}>
      <Stack gap="sm">
        {form.values.annotations.map((row, index) => {
          // A key stays offered to the row that holds it; other rows can't duplicate it.
          const keyOptions = allowed
            .map((registered) => registered.key)
            .filter((key) => key === row.key || !usedKeys.has(key));
          const keyData = row.key && !keyOptions.includes(row.key) ? [...keyOptions, row.key] : keyOptions;
          return (
            // Rows have no identity beyond position — Mantine's form list helpers are index-addressed.
            <Group key={`annotations-${index}`} align="flex-start" gap="sm" wrap="nowrap">
              <Select
                style={{ flex: 1 }}
                aria-label={t("catalog.annotationKeyAria", { index: index + 1 })}
                placeholder={t("catalog.field.key")}
                data={keyData}
                searchable
                value={row.key || null}
                onChange={(value) => form.setFieldValue(`annotations.${index}.key`, value ?? "")}
                error={form.getInputProps(`annotations.${index}.key`).error}
                {...findingProps(keyFinding(row.key), t, {
                  hardError: form.getInputProps(`annotations.${index}.key`).error,
                })}
              />
              <TextInput
                style={{ flex: 1 }}
                aria-label={t("catalog.annotationValueAria", { index: index + 1 })}
                placeholder={t("catalog.field.value")}
                {...form.getInputProps(`annotations.${index}.value`)}
              />
              <ActionIcon
                variant="subtle"
                color="red"
                mt={4}
                aria-label={t("catalog.removeAnnotationAria", { index: index + 1 })}
                onClick={() => form.removeListItem("annotations", index)}
              >
                <IconTrash size={16} />
              </ActionIcon>
            </Group>
          );
        })}
        {error ? (
          <Text size="sm" c="dimmed">
            {t("catalog.annotationKeyOptionsFailed")}
          </Text>
        ) : (
          // Not while loading — an in-flight registry fetch is not "none defined".
          !loading &&
          allowed.length === 0 && (
            <Text size="sm" c="dimmed">
              {t("catalog.noAnnotationKeysForKind", { kind })}
            </Text>
          )
        )}
        <Button
          variant="light"
          size="xs"
          leftSection={<IconPlus size={14} />}
          style={{ alignSelf: "flex-start" }}
          disabled={allowed.length === 0}
          onClick={() => form.insertListItem("annotations", { key: "", value: "" })}
        >
          {t("catalog.addAnnotation")}
        </Button>
      </Stack>
    </Fieldset>
  );
}
