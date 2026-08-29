import type { TFunction } from "i18next";
import type { DictionaryEntry, DictionaryUpdateBody } from "../api/dictionaries";
import { MAX_ENTITY_PART_LENGTH, NAMESPACE_RE } from "./catalogFileForm";
import { saveErrorMessage } from "./saveError";

// The namespaceForm sibling for the lifecycles dictionary — same draft/fold/validation
// shape, minus the default-flag plumbing (the LIFECYCLE dictionary has no default entry;
// the server rejects flagged items on it).
export type LifecycleEntryDraft = {
  key: string;
  id?: number;
  value: string;
};

export type LifecycleFormValues = {
  entries: LifecycleEntryDraft[];
};

let keyCounter = 0;
function newDraftKey(): string {
  keyCounter += 1;
  return `lc-draft-${keyCounter}`;
}

export function emptyLifecycleDraft(): LifecycleEntryDraft {
  return { key: newDraftKey(), value: "" };
}

/** The loaded dictionary -> editable form values. */
export function toLifecycleFormValues(items: DictionaryEntry[]): LifecycleFormValues {
  return { entries: items.map((e) => ({ key: newDraftKey(), id: e.id, value: e.value })) };
}

/** The stored form of a typed value — the server folds identically before validating. */
function foldLifecycleValue(value: string): string {
  return value.trim().toLowerCase();
}

/**
 * Form values -> the PUT body (local keys stripped, values folded, ids preserved, no
 * default flags). The array order IS the stored order.
 */
export function toLifecycleUpdateBody(values: LifecycleFormValues): DictionaryUpdateBody {
  return {
    items: values.entries.map((e) => ({ id: e.id, value: foldLifecycleValue(e.value), isDefault: false })),
  };
}

/**
 * Mirrors the server's payload rules (validateDictionaryUpdate — the shared dictionary
 * value grammar): 1-63 lowercase alphanumeric runs with single dashes after folding,
 * unique within the document (the duplicate flag lands on the LATER row).
 */
export function lifecycleFormValidation(t: TFunction) {
  return {
    entries: {
      value: (v: string, values: LifecycleFormValues, path: string) => {
        const folded = foldLifecycleValue(v);
        if (!folded) return t("lifecycles.valueRequired");
        if (folded.length > MAX_ENTITY_PART_LENGTH || !NAMESPACE_RE.test(folded)) {
          return t("lifecycles.valueInvalid");
        }
        // path is `entries.<index>.value` — [1] is the row index.
        const index = Number(path.split(".")[1]);
        const earlier = values.entries.slice(0, index);
        if (earlier.some((e) => foldLifecycleValue(e.value) === folded)) {
          return t("lifecycles.valueDuplicate");
        }
        return null;
      },
    },
  };
}

/** The shared mutation-error -> message mapping for lifecycle saves. */
export function lifecycleSaveErrorMessage(err: unknown, t: TFunction): string {
  return saveErrorMessage(err, t, {
    forbidden: "lifecycles.error.permission",
    conflict: "lifecycles.error.conflict",
    invalid: "lifecycles.error.validation",
    failedStatus: "lifecycles.error.saveFailedStatus",
    failed: "lifecycles.error.saveFailed",
  });
}
