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
import { type UseFormReturnType } from "@mantine/form";
import { IconPlus, IconTrash } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";
import { useCatalogIdentities } from "../hooks/useCatalogIdentities";
import { useLabels } from "../hooks/useLabels";
import { useNamespaceOptions } from "../hooks/useNamespaceOptions";
import { useTagCategories } from "../hooks/useTagCategories";
import { charCountDescription } from "../utils/charCount";
import { refSuggestions, type RefField } from "../utils/refSuggestions";
import {
  ENTITY_KINDS,
  fieldApplies,
  MAX_DEFINITION_LENGTH,
  MAX_DESCRIPTION_LENGTH,
  MAX_ENTITY_PART_LENGTH,
  MAX_LINK_TITLE_LENGTH,
  MAX_TITLE_LENGTH,
  RELATION_FIELDS,
  WELL_KNOWN_LIFECYCLES,
  WELL_KNOWN_TYPES,
  type CatalogFileFormValues,
  type EntityKind,
  type SpecFieldName,
} from "../utils/catalogFileForm";

const BELOW_INPUT = ["label", "input", "description", "error"] as const;

type CatalogForm = UseFormReturnType<CatalogFileFormValues>;
type Suggest = (field: RefField) => string[];

/**
 * The namespace picker: catalog writes accept ONLY namespaces defined in the admin-curated
 * dictionary, so free text became a Select over the active entries (blank still means
 * `default`). A stored value no longer in the dictionary is appended by the hook so it keeps
 * displaying — the server's strict 400 then names the problem on save. A failed options load
 * shows its own error (never an empty-looking list) but leaves the field enabled: the server
 * is the actual gate.
 */
function NamespaceSelect({ form }: { form: CatalogForm }) {
  const { t } = useTranslation();
  const { options, defaultNamespace, loading, error } = useNamespaceOptions(form.values.namespace);
  return (
    <Select
      label={t("catalog.field.namespace")}
      placeholder={defaultNamespace ?? "default"}
      data={options}
      searchable
      clearable
      disabled={loading}
      description={t("catalog.hint.namespace", { default: defaultNamespace ?? "default" })}
      value={form.values.namespace.trim().toLowerCase() || null}
      onChange={(value) => form.setFieldValue("namespace", value ?? "")}
      error={form.getInputProps("namespace").error ?? (error ? t("catalog.namespaceOptionsFailed") : undefined)}
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
  const { categories, error } = useTagCategories();
  const kind = form.values.kind;
  const allowed = categories.filter((category) => category.kinds.includes(kind));
  const offered = new Set(allowed.flatMap((category) => category.tags));
  const stale = form.values.tags.filter((tag) => !offered.has(tag));
  let hint: string | undefined;
  if (error) hint = t("catalog.tagOptionsFailed");
  else if (allowed.length === 0) hint = t("catalog.noTagsForKind", { kind });
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
    />
  );
}

function SpecFieldset({ form, suggest }: { form: CatalogForm; suggest: Suggest }) {
  const { t } = useTranslation();
  const kind = form.values.kind;
  const has = (field: SpecFieldName) => fieldApplies(kind, field);
  return (
    <Fieldset legend={t("catalog.section.spec")}>
      <Stack gap="sm">
        {(has("type") || has("lifecycle")) && (
          <Group grow align="flex-start">
            {has("type") && (
              <Autocomplete
                label={t("catalog.field.type")}
                required={kind !== "System" && kind !== "Domain"}
                data={[...WELL_KNOWN_TYPES[kind]]}
                placeholder={WELL_KNOWN_TYPES[kind].slice(0, 3).join(", ")}
                maxLength={MAX_ENTITY_PART_LENGTH}
                {...form.getInputProps("type")}
              />
            )}
            {has("lifecycle") && (
              <Autocomplete
                label={t("catalog.field.lifecycle")}
                required
                data={[...WELL_KNOWN_LIFECYCLES]}
                placeholder={t("catalog.hint.lifecycle")}
                maxLength={MAX_ENTITY_PART_LENGTH}
                {...form.getInputProps("lifecycle")}
              />
            )}
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
              />
            )}
            {has("subcomponentOf") && (
              <Autocomplete
                label={t("catalog.field.subcomponentOf")}
                description={t("catalog.hint.subcomponentOf")}
                data={suggest("subcomponentOf")}
                {...form.getInputProps("subcomponentOf")}
              />
            )}
            {has("domain") && (
              <Autocomplete
                label={t("catalog.field.domain")}
                description={t("catalog.hint.domain")}
                data={suggest("domain")}
                {...form.getInputProps("domain")}
              />
            )}
            {has("subdomainOf") && (
              <Autocomplete
                label={t("catalog.field.subdomainOf")}
                description={t("catalog.hint.subdomainOf")}
                data={suggest("subdomainOf")}
                {...form.getInputProps("subdomainOf")}
              />
            )}
            {has("parent") && (
              <Autocomplete
                label={t("catalog.field.parent")}
                description={t("catalog.hint.parent")}
                data={suggest("parent")}
                {...form.getInputProps("parent")}
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
              aria-label={`${t("catalog.field.url")} ${index + 1}`}
              placeholder={t("catalog.field.url")}
              {...form.getInputProps(`links.${index}.url`)}
            />
            <TextInput
              style={{ flex: 1 }}
              aria-label={`${t("catalog.field.linkTitle")} ${index + 1}`}
              placeholder={t("catalog.field.linkTitle")}
              maxLength={MAX_LINK_TITLE_LENGTH}
              {...form.getInputProps(`links.${index}.title`)}
            />
            <TextInput
              style={{ flex: 1 }}
              aria-label={`${t("catalog.field.icon")} ${index + 1}`}
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
  const { labels, error } = useLabels();
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
                aria-label={`${t("catalog.section.labels")} ${t("catalog.field.key")} ${index + 1}`}
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
              />
              <Select
                style={{ flex: 1 }}
                aria-label={`${t("catalog.section.labels")} ${t("catalog.field.value")} ${index + 1}`}
                placeholder={t("catalog.field.value")}
                data={valueData}
                searchable
                value={row.value || null}
                onChange={(value) => form.setFieldValue(`labels.${index}.value`, value ?? "")}
                error={form.getInputProps(`labels.${index}.value`).error}
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

/** The annotations key-value editor — free-form (key grammar aside), index-addressed rows. */
function AnnotationsFieldset({ form }: { form: CatalogForm }) {
  const { t } = useTranslation();
  return (
    <Fieldset legend={t("catalog.section.annotations")}>
      <Stack gap="sm">
        {form.values.annotations.map((_row, index) => (
          // Rows have no identity beyond position — Mantine's form list helpers are index-addressed.
          <Group key={`annotations-${index}`} align="flex-start" gap="sm" wrap="nowrap">
            <TextInput
              style={{ flex: 1 }}
              aria-label={`${t("catalog.section.annotations")} ${t("catalog.field.key")} ${index + 1}`}
              placeholder={t("catalog.field.key")}
              {...form.getInputProps(`annotations.${index}.key`)}
            />
            <TextInput
              style={{ flex: 1 }}
              aria-label={`${t("catalog.section.annotations")} ${t("catalog.field.value")} ${index + 1}`}
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
        ))}
        <Button
          variant="light"
          size="xs"
          leftSection={<IconPlus size={14} />}
          style={{ alignSelf: "flex-start" }}
          onClick={() => form.insertListItem("annotations", { key: "", value: "" })}
        >
          {t("catalog.addAnnotation")}
        </Button>
      </Stack>
    </Fieldset>
  );
}

/**
 * The field block shared by the create and edit catalog-file pages (which own submit/error
 * handling and the YAML preview). The kind Select drives which per-kind spec/relations fields
 * render — hidden fields keep their values (a kind switch back restores them) and the request
 * mapper strips whatever doesn't belong to the submitted kind.
 */
export default function CatalogFileFormFields({ form }: { form: CatalogForm }) {
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
    <Stack gap="md">
      <Select
        label={t("catalog.field.kind")}
        data={[...ENTITY_KINDS]}
        allowDeselect={false}
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
    </Stack>
  );
}
