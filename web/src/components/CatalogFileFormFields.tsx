import {
  ActionIcon,
  Autocomplete,
  Button,
  Fieldset,
  Group,
  MultiSelect,
  Select,
  Stack,
  TagsInput,
  Text,
  Textarea,
  TextInput,
} from "@mantine/core";
import { createContext, useContext } from "react";
import { type UseFormReturnType } from "@mantine/form";
import { IconPlus, IconTrash } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";
import { renderFindingPill } from "./FindingPill";
import { renderKindOption } from "./KindTierDot";
import { useAnnotationKeys } from "../hooks/useAnnotationKeys";
import { useCatalogIdentities } from "../hooks/useCatalogIdentities";
import { useEntityTypes } from "../hooks/useEntityTypes";
import { useLabels } from "../hooks/useLabels";
import { useLifecycleOptions } from "../hooks/useLifecycleOptions";
import { useNamespaceOptions } from "../hooks/useNamespaceOptions";
import { useTagCategories } from "../hooks/useTagCategories";
import { charCountDescription } from "../utils/charCount";
import { NO_FINDINGS, type FieldFindings } from "../utils/fieldFindings";
import { findingProps } from "../utils/findingProps";
import { refSuggestions, type RefField } from "../utils/refSuggestions";
import {
  ENTITY_KINDS,
  fieldApplies,
  fieldRequired,
  isValidEntityRef,
  isValidTag,
  MAX_DEFINITION_LENGTH,
  MAX_DESCRIPTION_LENGTH,
  MAX_ENTITY_PART_LENGTH,
  MAX_LINK_TITLE_LENGTH,
  MAX_SOURCE_URL_LENGTH,
  MAX_TITLE_LENGTH,
  RELATION_FIELDS,
  type CatalogFileFormValues,
  type EntityKind,
  type SpecFieldName,
} from "../utils/catalogFileForm";

const BELOW_INPUT = ["label", "input", "description", "error"] as const;

/**
 * The live check's findings, by control. Context rather than a prop through all eleven
 * fieldsets: every one of them would otherwise grow a parameter it only forwards.
 */
const FindingsContext = createContext<FieldFindings>(NO_FINDINGS);
const useFindings = () => useContext(FindingsContext);

type CatalogForm = UseFormReturnType<CatalogFileFormValues>;
type Suggest = (field: RefField) => string[];

/**
 * The namespace picker: catalog writes accept ONLY namespaces defined in the admin-curated
 * dictionary, so free text became a Select over the active entries (blank still means
 * `default`). A stored value no longer in the dictionary is appended by the hook so it keeps
 * displaying — the server's strict 400 then names the problem on save. A failed options load
 * shows its own hint (never an empty-looking list) and leaves the field enabled: the server
 * is the actual gate. The hint/never-disabled presentation matches the sibling pickers.
 */
function NamespaceSelect({ form }: { form: CatalogForm }) {
  const { t } = useTranslation();
  const { options, defaultNamespace, loading, error } = useNamespaceOptions(form.values.namespace);
  let hint: string | undefined;
  if (error) hint = t("catalog.namespaceOptionsFailed");
  else if (!loading && options.length === 0) hint = t("catalog.noNamespacesDefined");
  return (
    <Select
      label={t("catalog.field.namespace")}
      placeholder={defaultNamespace ?? "default"}
      data={options}
      searchable
      clearable
      description={hint ?? t("catalog.hint.namespace", { default: defaultNamespace ?? "default" })}
      // Spread first for onFocus/onBlur (the blur is what validates); value/onChange/error
      // are overridden after, since a Select's value is nullable where the form's is "".
      {...form.getInputProps("namespace")}
      value={form.values.namespace.trim().toLowerCase() || null}
      onChange={(value) => form.setFieldValue("namespace", value ?? "")}
    />
  );
}

function MetadataFieldset({ form }: { form: CatalogForm }) {
  const { t } = useTranslation();
  return (
    <Fieldset legend={t("catalog.section.metadata")}>
      <Stack gap="sm">
        <TextInput
          label={t("common.field.name")}
          autoFocus
          required
          maxLength={MAX_ENTITY_PART_LENGTH}
          description={charCountDescription(form.values.name.length, MAX_ENTITY_PART_LENGTH)}
          inputWrapperOrder={[...BELOW_INPUT]}
          {...form.getInputProps("name")}
        />
        <NamespaceSelect form={form} />
        <TextInput
          label={t("catalog.field.title")}
          maxLength={MAX_TITLE_LENGTH}
          description={charCountDescription(form.values.title.length, MAX_TITLE_LENGTH)}
          inputWrapperOrder={[...BELOW_INPUT]}
          {...form.getInputProps("title")}
        />
        <Textarea
          label={t("catalog.field.description")}
          autosize
          minRows={2}
          maxLength={MAX_DESCRIPTION_LENGTH}
          description={charCountDescription(form.values.description.length, MAX_DESCRIPTION_LENGTH)}
          inputWrapperOrder={[...BELOW_INPUT]}
          {...form.getInputProps("description")}
        />
        <TagsMultiSelect form={form} />
      </Stack>
    </Fieldset>
  );
}

/**
 * The tags picker — registry-constrained, NOT free entry: catalog writes accept only tags
 * belonging to an ADMIN-defined tag category whose kinds include the document's kind
 * (server-enforced, strict), so the options are the allowed categories' tags GROUPED by
 * category. Stored tags no longer offered (category removed/narrowed since the file was
 * saved) are appended under their own group so the file keeps rendering — the server's 400
 * then names the problem on save. A failed registry load keeps the field rendered (hint only).
 */
function TagsMultiSelect({ form }: { form: CatalogForm }) {
  const { t } = useTranslation();
  const findings = useFindings();
  const { categories, loading, error } = useTagCategories();
  const kind = form.values.kind;
  const allowed = categories.filter((category) => category.kinds.includes(kind));
  const offered = new Set(allowed.flatMap((category) => category.tags));
  const stale = form.values.tags.filter((tag) => !offered.has(tag));
  let hint: string | undefined;
  if (error) hint = t("catalog.tagOptionsFailed");
  // Not while loading — an in-flight registry fetch is not "none defined".
  else if (!loading && allowed.length === 0) hint = t("catalog.noTagsForKind", { kind });
  return (
    <MultiSelect
      label={t("catalog.field.tags")}
      data={[
        ...allowed.map((category) => ({ group: category.name, items: [...category.tags] })),
        ...(stale.length > 0 ? [{ group: t("catalog.staleTagsGroup"), items: stale }] : []),
      ]}
      searchable
      description={hint}
      {...form.getInputProps("tags")}
      {...findingProps(findings.forPath("tags"), t, {
        hardError: form.getInputProps("tags").error,
        namedValues: true,
      })}
      renderPill={renderFindingPill({
        findings: findings.forPath("tags"),
        hardError: form.getInputProps("tags").error,
        invalid: (tag) => (isValidTag(tag) ? null : t("catalog.validation.tag", { tag })),
        t,
      })}
    />
  );
}

/**
 * The type picker — registry-constrained, NOT free entry: catalog writes accept only
 * spec.type values from the document's kind's ADMIN-defined type dictionary
 * (server-enforced, strict), so the options are that dictionary's values. A stored value
 * no longer offered is appended so the file keeps rendering — the server's 400 then names
 * the problem on save. A failed registry load keeps the field rendered (hint only), and a
 * kind with no dictionary shows its own hint (a required-type kind cannot be saved until
 * an admin defines the list — the server is the gate).
 */
function TypeSelect({ form }: { form: CatalogForm }) {
  const { t } = useTranslation();
  const findings = useFindings();
  const { dictionaries, loading, error } = useEntityTypes();
  const kind = form.values.kind;
  const optional = !fieldRequired(kind, "type");
  const offered = dictionaries.find((dictionary) => dictionary.kind === kind)?.types ?? [];
  const current = form.values.type.trim();
  const options = current && !offered.includes(current) ? [...offered, current] : [...offered];
  let hint: string | undefined;
  if (error) hint = t("catalog.typeOptionsFailed");
  // Not while loading — an in-flight registry fetch is not "none defined".
  else if (!loading && offered.length === 0) hint = t("catalog.noTypesForKind", { kind });
  return (
    <Select
      label={t("catalog.field.type")}
      required={!optional}
      data={options}
      searchable
      clearable={optional}
      description={hint}
      {...form.getInputProps("type")}
      value={current || null}
      onChange={(value) => form.setFieldValue("type", value ?? "")}
      {...findingProps(findings.forPath("type"), t, {
        hardError: form.getInputProps("type").error,
      })}
    />
  );
}

/**
 * The lifecycle picker — registry-constrained like [TypeSelect], but against the GLOBAL
 * lifecycles dictionary (one list for every lifecycle-bearing kind; server-enforced,
 * strict). A stored value no longer in the dictionary is appended so the file keeps
 * rendering — the server's 400 then names the problem on save. Not clearable: every kind
 * that renders the field requires it.
 */
function LifecycleSelect({ form }: { form: CatalogForm }) {
  const { t } = useTranslation();
  const findings = useFindings();
  const current = form.values.lifecycle.trim();
  const { options, loading, error } = useLifecycleOptions(current);
  let hint: string | undefined;
  if (error) hint = t("catalog.lifecycleOptionsFailed");
  // Not while loading — an in-flight registry fetch is not "none defined".
  else if (!loading && options.length === 0) hint = t("catalog.noLifecyclesDefined");
  return (
    <Select
      label={t("catalog.field.lifecycle")}
      required
      data={options}
      searchable
      description={hint}
      {...form.getInputProps("lifecycle")}
      value={current || null}
      onChange={(value) => form.setFieldValue("lifecycle", value ?? "")}
      {...findingProps(findings.forPath("lifecycle"), t, {
        hardError: form.getInputProps("lifecycle").error,
      })}
    />
  );
}

function SpecFieldset({ form, suggest }: { form: CatalogForm; suggest: Suggest }) {
  const { t } = useTranslation();
  const findings = useFindings();
  const kind = form.values.kind;
  const has = (field: SpecFieldName) => fieldApplies(kind, field);
  // Single-value refs: the offending value is already in the input, so the message alone.
  const refFinding = (field: string) =>
    findingProps(findings.forPath(field), t, { hardError: form.getInputProps(field).error });
  return (
    <Fieldset legend={t("catalog.section.spec")}>
      <Stack gap="sm">
        {(has("type") || has("lifecycle")) && (
          <Group grow align="flex-start">
            {has("type") && <TypeSelect form={form} />}
            {has("lifecycle") && <LifecycleSelect form={form} />}
          </Group>
        )}
        {has("owner") && (
          <Autocomplete
            label={t("catalog.field.owner")}
            required
            placeholder="group:default/platform"
            description={t("catalog.hint.owner")}
            data={suggest("owner")}
            {...form.getInputProps("owner")}
            {...refFinding("owner")}
          />
        )}
        {(has("system") || has("subcomponentOf") || has("domain") || has("subdomainOf") || has("parent")) && (
          <Group grow align="flex-start">
            {has("system") && (
              <Autocomplete
                label={t("catalog.field.system")}
                description={t("catalog.hint.system")}
                data={suggest("system")}
                {...form.getInputProps("system")}
                {...refFinding("system")}
              />
            )}
            {has("subcomponentOf") && (
              <Autocomplete
                label={t("catalog.field.subcomponentOf")}
                description={t("catalog.hint.subcomponentOf")}
                data={suggest("subcomponentOf")}
                {...form.getInputProps("subcomponentOf")}
                {...refFinding("subcomponentOf")}
              />
            )}
            {has("domain") && (
              <Autocomplete
                label={t("catalog.field.domain")}
                description={t("catalog.hint.domain")}
                data={suggest("domain")}
                {...form.getInputProps("domain")}
                {...refFinding("domain")}
              />
            )}
            {has("subdomainOf") && (
              <Autocomplete
                label={t("catalog.field.subdomainOf")}
                description={t("catalog.hint.subdomainOf")}
                data={suggest("subdomainOf")}
                {...form.getInputProps("subdomainOf")}
                {...refFinding("subdomainOf")}
              />
            )}
            {has("parent") && (
              <Autocomplete
                label={t("catalog.field.parent")}
                description={t("catalog.hint.parent")}
                data={suggest("parent")}
                {...form.getInputProps("parent")}
                {...refFinding("parent")}
              />
            )}
          </Group>
        )}
        {has("definition") && (
          <Textarea
            label={t("catalog.field.definition")}
            required
            autosize
            minRows={6}
            maxRows={18}
            maxLength={MAX_DEFINITION_LENGTH}
            styles={{ input: { fontFamily: "var(--mantine-font-family-monospace)" } }}
            description={t("catalog.hint.definition")}
            inputWrapperOrder={[...BELOW_INPUT]}
            {...form.getInputProps("definition")}
          />
        )}
      </Stack>
    </Fieldset>
  );
}

function ProfileFieldset({ form }: { form: CatalogForm }) {
  const { t } = useTranslation();
  return (
    <Fieldset legend={t("catalog.section.profile")}>
      <Group grow align="flex-start">
        <TextInput
          label={t("catalog.field.profileDisplayName")}
          maxLength={MAX_TITLE_LENGTH}
          {...form.getInputProps("profileDisplayName")}
        />
        <TextInput label={t("catalog.field.profileEmail")} {...form.getInputProps("profileEmail")} />
        <TextInput label={t("catalog.field.profilePicture")} {...form.getInputProps("profilePicture")} />
      </Group>
    </Fieldset>
  );
}

function RelationsFieldset({
  form,
  fields,
  suggest,
}: {
  form: CatalogForm;
  fields: readonly (typeof RELATION_FIELDS)[number][];
  suggest: Suggest;
}) {
  const { t } = useTranslation();
  const findings = useFindings();
  return (
    <Fieldset legend={t("catalog.section.relations")}>
      <Stack gap="sm">
        <Text size="xs" c="dimmed">
          {t("catalog.refHelp")}
        </Text>
        {fields.map((field) => (
          <TagsInput
            key={field}
            label={t(`catalog.field.${field}`)}
            description={t(`catalog.hint.${field}`)}
            splitChars={[",", " "]}
            data={suggest(field)}
            {...form.getInputProps(field)}
            {...findingProps(findings.forPath(field), t, {
              hardError: form.getInputProps(field).error,
              namedValues: true,
            })}
            renderPill={renderFindingPill({
              findings: findings.forPath(field),
              hardError: form.getInputProps(field).error,
              invalid: (ref) =>
                isValidEntityRef(ref.trim()) ? null : t("catalog.validation.ref"),
              t,
            })}
          />
        ))}
      </Stack>
    </Fieldset>
  );
}

function LinksFieldset({ form }: { form: CatalogForm }) {
  const { t } = useTranslation();
  return (
    <Fieldset legend={t("catalog.section.links")}>
      <Stack gap="sm">
        {form.values.links.map((_link, index) => (
          // Rows have no identity beyond position — Mantine's form list helpers are index-addressed.
          <Group key={`link-${index}`} align="flex-start" gap="sm" wrap="nowrap">
            <TextInput
              style={{ flex: 2 }}
              aria-label={t("catalog.linkUrlAria", { index: index + 1 })}
              placeholder={t("catalog.field.url")}
              {...form.getInputProps(`links.${index}.url`)}
            />
            <TextInput
              style={{ flex: 1 }}
              aria-label={t("catalog.linkTitleAria", { index: index + 1 })}
              placeholder={t("catalog.field.linkTitle")}
              maxLength={MAX_LINK_TITLE_LENGTH}
              {...form.getInputProps(`links.${index}.title`)}
            />
            <TextInput
              style={{ flex: 1 }}
              aria-label={t("catalog.linkIconAria", { index: index + 1 })}
              placeholder={t("catalog.field.icon")}
              maxLength={MAX_ENTITY_PART_LENGTH}
              {...form.getInputProps(`links.${index}.icon`)}
            />
            <ActionIcon
              variant="subtle"
              color="red"
              mt={4}
              aria-label={t("catalog.removeLinkAria", { index: index + 1 })}
              onClick={() => form.removeListItem("links", index)}
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
          onClick={() => form.insertListItem("links", { url: "", title: "", icon: "" })}
        >
          {t("catalog.addLink")}
        </Button>
      </Stack>
    </Fieldset>
  );
}

/**
 * The labels editor — registry-constrained pickers, NOT free key/value inputs: catalog
 * writes accept only ADMIN-registered labels allowed for the document's kind, each with a
 * value from that label's closed list (server-enforced, strict). A stored key or value no
 * longer offered (label removed/renamed/narrowed since the file was saved) is appended to
 * its own row's options so it keeps displaying — the server's 400 then names the problem
 * on save. A failed registry load disables adding but keeps existing rows rendered.
 */
function LabelsFieldset({ form }: { form: CatalogForm }) {
  const { t } = useTranslation();
  const findings = useFindings();
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
function AnnotationsFieldset({ form }: { form: CatalogForm }) {
  const { t } = useTranslation();
  const findings = useFindings();
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

/**
 * The source reference — provenance (the https URL of the file's repo copy), deliberately
 * its own fieldset rather than Metadata: it is envelope state beside the document, never
 * part of the Backstage YAML. Backs the Files list's sync column and the Sync-from-repo
 * operation.
 */
function SourceFieldset({ form }: { form: CatalogForm }) {
  const { t } = useTranslation();
  return (
    <Fieldset legend={t("catalog.section.source")}>
      <TextInput
        label={t("catalog.field.sourceUrl")}
        placeholder="https://github.com/acme/service/blob/main/catalog-info.yaml"
        maxLength={MAX_SOURCE_URL_LENGTH}
        description={t("catalog.hint.sourceUrl")}
        inputWrapperOrder={[...BELOW_INPUT]}
        {...form.getInputProps("sourceUrl")}
      />
    </Fieldset>
  );
}

/**
 * The field block shared by the create and edit catalog-file pages (which own submit/error
 * handling and the YAML preview). The kind Select drives which per-kind spec/relations fields
 * render — hidden fields keep their values (a kind switch back restores them) and the request
 * mapper strips whatever doesn't belong to the submitted kind.
 */
export default function CatalogFileFormFields({
  form,
  findings = NO_FINDINGS,
}: {
  form: CatalogForm;
  /** Routed to the individual controls; the editor shell runs the one check. */
  findings?: FieldFindings;
}) {
  const { t } = useTranslation();
  const kind = form.values.kind;
  const has = (field: SpecFieldName) => fieldApplies(kind, field);
  // The reference pickers' pool — advisory: while loading or on failure the ref fields
  // simply offer no suggestions and stay plain free-text inputs. Options are always the
  // full `kind:namespace/name` identity; typed short forms remain legal. The entity being
  // edited never offers ITSELF (self-references are rejected on save); a blank namespace
  // matches against the flagged default — the identity a blank save resolves to.
  const identities = useCatalogIdentities();
  const { defaultNamespace } = useNamespaceOptions(form.values.namespace);
  const name = form.values.name.trim().toLowerCase();
  const self = name
    ? { kind, namespace: form.values.namespace.trim().toLowerCase() || (defaultNamespace ?? "default"), name }
    : null;
  const suggest = (field: RefField) => refSuggestions(identities, field, self);
  const relationFields = RELATION_FIELDS.filter(has);

  return (
    <FindingsContext.Provider value={findings}>
    <Stack gap="md">
      <Select
        label={t("catalog.field.kind")}
        data={[...ENTITY_KINDS]}
        allowDeselect={false}
        renderOption={renderKindOption}
        value={kind}
        onChange={(v) => {
          if (v) form.setFieldValue("kind", v as EntityKind);
        }}
        error={form.getInputProps("kind").error}
      />
      <MetadataFieldset form={form} />
      <SpecFieldset form={form} suggest={suggest} />
      {has("profile") && <ProfileFieldset form={form} />}
      {relationFields.length > 0 && (
        <RelationsFieldset form={form} fields={relationFields} suggest={suggest} />
      )}
      <LinksFieldset form={form} />
      <LabelsFieldset form={form} />
      <AnnotationsFieldset form={form} />
      <SourceFieldset form={form} />
    </Stack>
    </FindingsContext.Provider>
  );
}
