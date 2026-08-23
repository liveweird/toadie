import {
  ActionIcon,
  Autocomplete,
  Button,
  Fieldset,
  Group,
  Stack,
  TagsInput,
  Text,
  Textarea,
  TextInput,
} from "@mantine/core";
import { type UseFormReturnType } from "@mantine/form";
import { IconPlus, IconTrash } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";
import { charCountDescription } from "../utils/charCount";
import {
  MAX_DESCRIPTION_LENGTH,
  MAX_ENTITY_PART_LENGTH,
  MAX_LINK_TITLE_LENGTH,
  MAX_TITLE_LENGTH,
  WELL_KNOWN_LIFECYCLES,
  WELL_KNOWN_TYPES,
  type CatalogFileFormValues,
} from "../utils/catalogFileForm";

const BELOW_INPUT = ["label", "input", "description", "error"] as const;

/**
 * The field block shared by the create and edit catalog-file pages (which own submit/error
 * handling and the YAML preview). Sectioned to mirror the descriptor: metadata → spec →
 * relations → links → labels → annotations.
 */
export default function CatalogFileFormFields({
  form,
}: {
  form: UseFormReturnType<CatalogFileFormValues>;
}) {
  const { t } = useTranslation();

  const keyValueRows = (listField: "labels" | "annotations") =>
    form.values[listField].map((_row, index) => (
      // Rows have no identity beyond position — Mantine's form list helpers are index-addressed.
      <Group key={`${listField}-${index}`} align="flex-start" gap="sm" wrap="nowrap">
        <TextInput
          style={{ flex: 1 }}
          aria-label={`${t(`catalog.section.${listField}`)} ${t("catalog.field.key")} ${index + 1}`}
          placeholder={t("catalog.field.key")}
          {...form.getInputProps(`${listField}.${index}.key`)}
        />
        <TextInput
          style={{ flex: 1 }}
          aria-label={`${t(`catalog.section.${listField}`)} ${t("catalog.field.value")} ${index + 1}`}
          placeholder={t("catalog.field.value")}
          {...form.getInputProps(`${listField}.${index}.value`)}
        />
        <ActionIcon
          variant="subtle"
          color="red"
          mt={4}
          aria-label={t(
            listField === "labels" ? "catalog.removeLabelAria" : "catalog.removeAnnotationAria",
            { index: index + 1 },
          )}
          onClick={() => form.removeListItem(listField, index)}
        >
          <IconTrash size={16} />
        </ActionIcon>
      </Group>
    ));

  return (
    <Stack gap="md">
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
          <TextInput
            label={t("catalog.field.namespace")}
            placeholder="default"
            maxLength={MAX_ENTITY_PART_LENGTH}
            description={t("catalog.hint.namespace")}
            {...form.getInputProps("namespace")}
          />
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
          <TagsInput label={t("catalog.field.tags")} splitChars={[",", " "]} {...form.getInputProps("tags")} />
        </Stack>
      </Fieldset>

      <Fieldset legend={t("catalog.section.spec")}>
        <Stack gap="sm">
          <Group grow align="flex-start">
            <Autocomplete
              label={t("catalog.field.type")}
              required
              data={[...WELL_KNOWN_TYPES]}
              placeholder={t("catalog.hint.type")}
              maxLength={MAX_ENTITY_PART_LENGTH}
              {...form.getInputProps("type")}
            />
            <Autocomplete
              label={t("catalog.field.lifecycle")}
              required
              data={[...WELL_KNOWN_LIFECYCLES]}
              placeholder={t("catalog.hint.lifecycle")}
              maxLength={MAX_ENTITY_PART_LENGTH}
              {...form.getInputProps("lifecycle")}
            />
          </Group>
          <TextInput
            label={t("catalog.field.owner")}
            required
            placeholder="group:default/platform"
            description={t("catalog.hint.owner")}
            {...form.getInputProps("owner")}
          />
          <Group grow align="flex-start">
            <TextInput
              label={t("catalog.field.system")}
              description={t("catalog.hint.system")}
              {...form.getInputProps("system")}
            />
            <TextInput
              label={t("catalog.field.subcomponentOf")}
              description={t("catalog.hint.subcomponentOf")}
              {...form.getInputProps("subcomponentOf")}
            />
          </Group>
        </Stack>
      </Fieldset>

      <Fieldset legend={t("catalog.section.relations")}>
        <Stack gap="sm">
          <Text size="xs" c="dimmed">
            {t("catalog.refHelp")}
          </Text>
          <TagsInput
            label={t("catalog.field.providesApis")}
            description={t("catalog.hint.providesApis")}
            splitChars={[",", " "]}
            {...form.getInputProps("providesApis")}
          />
          <TagsInput
            label={t("catalog.field.consumesApis")}
            description={t("catalog.hint.consumesApis")}
            splitChars={[",", " "]}
            {...form.getInputProps("consumesApis")}
          />
          <TagsInput
            label={t("catalog.field.dependsOn")}
            description={t("catalog.hint.dependsOn")}
            splitChars={[",", " "]}
            {...form.getInputProps("dependsOn")}
          />
          <TagsInput
            label={t("catalog.field.dependencyOf")}
            description={t("catalog.hint.dependencyOf")}
            splitChars={[",", " "]}
            {...form.getInputProps("dependencyOf")}
          />
        </Stack>
      </Fieldset>

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

      <Fieldset legend={t("catalog.section.labels")}>
        <Stack gap="sm">
          {keyValueRows("labels")}
          <Button
            variant="light"
            size="xs"
            leftSection={<IconPlus size={14} />}
            style={{ alignSelf: "flex-start" }}
            onClick={() => form.insertListItem("labels", { key: "", value: "" })}
          >
            {t("catalog.addLabel")}
          </Button>
        </Stack>
      </Fieldset>

      <Fieldset legend={t("catalog.section.annotations")}>
        <Stack gap="sm">
          {keyValueRows("annotations")}
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
    </Stack>
  );
}
