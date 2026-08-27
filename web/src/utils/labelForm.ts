import type { TFunction } from "i18next";
import type { Label, LabelBody } from "../api/labels";
import { isValidKey, isValidName } from "./catalogFileForm";
import { saveErrorMessage } from "./saveError";

// The server's caps (labels/Label.kt) — mirror, don't invent.
export const MAX_LABEL_KEY_LENGTH = 317;
const MAX_LABEL_VALUES = 100;

export type LabelFormValues = {
  key: string;
  values: string[];
  kinds: string[];
};

export function emptyLabelForm(): LabelFormValues {
  return { key: "", values: [], kinds: [] };
}

export function toLabelFormValues(label: Label): LabelFormValues {
  return { key: label.key, values: [...label.values], kinds: [...label.kinds] };
}

export function toLabelBody(values: LabelFormValues): LabelBody {
  return {
    key: values.key.trim(),
    values: values.values.map((v) => v.trim()),
    kinds: values.kinds,
  };
}

/** Validation rules for the create/edit modal (mirrors the server's checks). */
export function labelFormValidation(t: TFunction) {
  return {
    key: (value: string) => {
      const v = value.trim();
      return v.length <= MAX_LABEL_KEY_LENGTH && isValidKey(v) ? null : t("labels.validation.key");
    },
    values: (values: string[]) => {
      if (values.length === 0) return t("labels.validation.valuesRequired");
      if (values.length > MAX_LABEL_VALUES) return t("labels.validation.valuesCount");
      const bad = values.map((v) => v.trim()).find((v) => !isValidName(v));
      if (bad !== undefined) return t("labels.validation.value", { value: bad });
      const folded = values.map((v) => v.trim().toLowerCase());
      return folded.length === new Set(folded).size ? null : t("labels.validation.valuesDuplicate");
    },
    kinds: (values: string[]) => (values.length > 0 ? null : t("labels.validation.kindsRequired")),
  };
}

/** The label save's fixed error vocabulary (409 = an active label already holds the key). */
export function labelSaveErrorMessage(err: unknown, t: TFunction): string {
  return saveErrorMessage(err, t, {
    conflict: "labels.saveConflict",
    invalid: "labels.saveInvalid",
    notFound: "labels.saveGone",
    failedStatus: "common.error.actionFailedStatus",
    failed: "common.error.actionFailed",
  });
}
