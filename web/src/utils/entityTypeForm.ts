import type { TFunction } from "i18next";
import type { EntityTypes, EntityTypesBody } from "../api/entityTypes";
import { ENTITY_KINDS, fieldApplies, MAX_ENTITY_PART_LENGTH, type EntityKind } from "./catalogFileForm";
import { saveErrorMessage } from "./saveError";

/** The kinds whose spec carries a `type` field (all but User) — derived from the field table. */
export const TYPE_BEARING_KINDS = ENTITY_KINDS.filter((kind) => fieldApplies(kind, "type"));

// The server's caps (types/EntityTypes.kt) — mirror, don't invent.
const MAX_KIND_TYPES = 100;

export type EntityTypesFormValues = {
  kind: string;
  types: string[];
};

export function emptyEntityTypesForm(): EntityTypesFormValues {
  return { kind: "", types: [] };
}

export function toEntityTypesFormValues(dictionary: EntityTypes): EntityTypesFormValues {
  return { kind: dictionary.kind, types: [...dictionary.types] };
}

export function toEntityTypesBody(values: EntityTypesFormValues): EntityTypesBody {
  return { kind: values.kind, types: values.types.map((type) => type.trim()) };
}

/** Validation rules for the create/edit modal (mirrors the server's checks). */
export function entityTypesFormValidation(t: TFunction) {
  return {
    kind: (value: string) =>
      TYPE_BEARING_KINDS.includes(value as EntityKind) ? null : t("types.validation.kind"),
    types: (values: string[]) => {
      if (values.length === 0) return t("types.validation.typesRequired");
      if (values.length > MAX_KIND_TYPES) return t("types.validation.typesCount");
      // The server's spec.type rule: 1–63 characters, no whitespace.
      const bad = values
        .map((type) => type.trim())
        .find((type) => type.length < 1 || type.length > MAX_ENTITY_PART_LENGTH || /\s/.test(type));
      if (bad !== undefined) return t("types.validation.type", { type: bad });
      const folded = values.map((type) => type.trim().toLowerCase());
      return folded.length === new Set(folded).size ? null : t("types.validation.typesDuplicate");
    },
  };
}

/**
 * The dictionary save's fixed error vocabulary. The one conflict cause: the kind already
 * holds an active dictionary.
 */
export function entityTypesSaveErrorMessage(err: unknown, t: TFunction): string {
  return saveErrorMessage(err, t, {
    conflict: "types.saveConflict",
    invalid: "types.saveInvalid",
    notFound: "types.saveGone",
    failedStatus: "common.error.actionFailedStatus",
    failed: "common.error.actionFailed",
  });
}
