import type { UseFormReturnType } from "@mantine/form";
import { useTranslation } from "react-i18next";
import EntityOptionsSelect from "./EntityOptionsSelect";
import TierLabel from "./TierLabel";
import type { EntityFormValues, RelationDefinitionWire } from "../utils/entityForm";

type Form = UseFormReturnType<EntityFormValues>;

/**
 * One relation row's widget — a thin `EntityOptionsSelect` wrapper (extracted in v1.26.0, the
 * team ownership picker's sibling): a Select (`many: false`) or MultiSelect (`many: true`)
 * over every active entity of the relation's `target` blueprint.
 */
export default function EntityRelationField({
  form,
  index,
  definition,
  required,
  tier = null,
}: {
  form: Form;
  index: number;
  definition: RelationDefinitionWire;
  required: boolean;
  /** The relation's fill-in tier (2.18.0) — a dot before the label, nothing else. */
  tier?: number | null;
}) {
  const { t } = useTranslation();
  const label = <TierLabel tier={tier}>{definition.title}</TierLabel>;
  const hints = { failedHint: t("entities.field.relationOptionsFailed"), emptyHint: t("entities.editor.noTargets") };

  if (definition.many) {
    return (
      <EntityOptionsSelect
        mode="multi"
        target={definition.target}
        label={label}
        description={definition.description}
        required={required}
        inputProps={form.getInputProps(`relations.${index}.many`)}
        {...hints}
      />
    );
  }

  return (
    <EntityOptionsSelect
      mode="single"
      target={definition.target}
      label={label}
      description={definition.description}
      required={required}
      clearable={!required}
      inputProps={form.getInputProps(`relations.${index}.single`)}
      {...hints}
    />
  );
}
