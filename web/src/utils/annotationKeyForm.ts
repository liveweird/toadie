import type { TFunction } from "i18next";
import type { AnnotationKey, AnnotationKeyBody } from "../api/annotationKeys";
import { isValidKey } from "./catalogFileForm";
import { saveErrorMessage } from "./saveError";

// The server's cap (annotations/AnnotationKey.kt) — mirror, don't invent.
export const MAX_ANNOTATION_KEY_LENGTH = 317;

export type AnnotationKeyFormValues = {
  key: string;
  kinds: string[];
};

export function emptyAnnotationKeyForm(): AnnotationKeyFormValues {
  return { key: "", kinds: [] };
}

export function toAnnotationKeyFormValues(row: AnnotationKey): AnnotationKeyFormValues {
  return { key: row.key, kinds: [...row.kinds] };
}

export function toAnnotationKeyBody(values: AnnotationKeyFormValues): AnnotationKeyBody {
  return { key: values.key.trim(), kinds: values.kinds };
}

/** Validation rules for the create/edit modal (mirrors the server's checks). */
export function annotationKeyFormValidation(t: TFunction) {
  return {
    key: (value: string) => {
      const v = value.trim();
      return v.length <= MAX_ANNOTATION_KEY_LENGTH && isValidKey(v)
        ? null
        : t("annotations.validation.key");
    },
    kinds: (values: string[]) => (values.length > 0 ? null : t("annotations.validation.kindsRequired")),
  };
}

/** The save's fixed error vocabulary (409 = an active row already holds the key). */
export function annotationKeySaveErrorMessage(err: unknown, t: TFunction): string {
  return saveErrorMessage(err, t, {
    forbidden: "annotations.saveForbidden",
    conflict: "annotations.saveConflict",
    invalid: "annotations.saveInvalid",
    notFound: "annotations.saveGone",
    failedStatus: "common.error.actionFailedStatus",
    failed: "common.error.actionFailed",
  });
}
