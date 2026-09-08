import { Group, TextInput } from "@mantine/core";
import { type UseFormReturnType } from "@mantine/form";
import { useTranslation } from "react-i18next";
import { MAX_TITLE_LENGTH, type BlueprintFormValues } from "../utils/blueprintForm";

type Form = UseFormReturnType<BlueprintFormValues>;

/**
 * One mirror property row's fields: identifier, title, and the dot-separated path. Removal/
 * reordering lives one level up, in MirrorFieldset's `EditorRowList`.
 */
export default function BlueprintMirrorRow({ form, index }: { form: Form; index: number }) {
  const { t } = useTranslation();
  return (
    <Group align="flex-start" gap="sm" wrap="wrap">
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
    </Group>
  );
}
