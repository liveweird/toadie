import type { TFunction } from "i18next";
import type { DictionaryEntry, DictionaryUpdateBody } from "../api/dictionaries";
import { MAX_ENTITY_PART_LENGTH, NAMESPACE_RE } from "./catalogFileForm";
import { saveErrorMessage } from "./saveError";

// The lifecycleForm sibling for the hierarchies dictionary — same draft/fold/validation
// shape, minus the default-flag plumbing (the HIERARCHY dictionary has no default entry;
// the server rejects flagged items on it).
export type HierarchyEntryDraft = {
  key: string;
  id?: number;
  value: string;
};

export type HierarchiesFormValues = {
  entries: HierarchyEntryDraft[];
};

let keyCounter = 0;
function newDraftKey(): string {
  keyCounter += 1;
  return `hi-draft-${keyCounter}`;
}

export function emptyHierarchyDraft(): HierarchyEntryDraft {
  return { key: newDraftKey(), value: "" };
}

/** The loaded dictionary -> editable form values. */
export function toHierarchiesFormValues(items: DictionaryEntry[]): HierarchiesFormValues {
  return { entries: items.map((e) => ({ key: newDraftKey(), id: e.id, value: e.value })) };
}

/** The stored form of a typed value — the server folds identically before validating. */
function foldHierarchyValue(value: string): string {
  return value.trim().toLowerCase();
}

/**
 * Form values -> the PUT body (local keys stripped, values folded, ids preserved, no
 * default flags). The array order IS the stored order.
 */
export function toHierarchiesUpdateBody(values: HierarchiesFormValues): DictionaryUpdateBody {
  return {
    items: values.entries.map((e) => ({ id: e.id, value: foldHierarchyValue(e.value), isDefault: false })),
  };
}

/**
 * Mirrors the server's payload rules (validateDictionaryUpdate — the shared dictionary
 * value grammar): 1-63 lowercase alphanumeric runs with single dashes after folding,
 * unique within the document (the duplicate flag lands on the LATER row).
 */
export function hierarchiesFormValidation(t: TFunction) {
  return {
    entries: {
      value: (v: string, values: HierarchiesFormValues, path: string) => {
        const folded = foldHierarchyValue(v);
        if (!folded) return t("hierarchies.valueRequired");
        if (folded.length > MAX_ENTITY_PART_LENGTH || !NAMESPACE_RE.test(folded)) {
          return t("hierarchies.valueInvalid");
        }
        // path is `entries.<index>.value` — [1] is the row index.
        const index = Number(path.split(".")[1]);
        const earlier = values.entries.slice(0, index);
        if (earlier.some((e) => foldHierarchyValue(e.value) === folded)) {
          return t("hierarchies.valueDuplicate");
        }
        return null;
      },
    },
  };
}

/** The shared mutation-error -> message mapping for hierarchy saves. */
export function hierarchiesSaveErrorMessage(err: unknown, t: TFunction): string {
  return saveErrorMessage(err, t, {
    forbidden: "hierarchies.error.permission",
    conflict: "hierarchies.error.conflict",
    invalid: "hierarchies.error.validation",
    failedStatus: "hierarchies.error.saveFailedStatus",
    failed: "hierarchies.error.saveFailed",
  });
}
