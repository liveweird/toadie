import type { TFunction } from "i18next";
import type { DictionaryEntry, DictionaryUpdateBody } from "../api/dictionaries";
import { MAX_ENTITY_PART_LENGTH, NAMESPACE_RE } from "./catalogFileForm";
import { saveErrorMessage } from "./saveError";

// Draft rows carry a local `key` for React list identity (stable across reorders, unlike the
// index); rows loaded from the server also keep their `id`, which the PUT body preserves so
// the backend can tell renames from add/remove — new rows simply have no id.
export type NamespaceEntryDraft = {
  key: string;
  id?: number;
  value: string;
  /** Exactly one draft carries this — what a blank catalog-file namespace resolves to. */
  isDefault: boolean;
};

export type NamespaceFormValues = {
  entries: NamespaceEntryDraft[];
};

let keyCounter = 0;
function newDraftKey(): string {
  keyCounter += 1;
  return `ns-draft-${keyCounter}`;
}

export function emptyEntryDraft(): NamespaceEntryDraft {
  return { key: newDraftKey(), value: "", isDefault: false };
}

/** The loaded dictionary -> editable form values. */
export function toFormValues(items: DictionaryEntry[]): NamespaceFormValues {
  return {
    entries: items.map((e) => ({ key: newDraftKey(), id: e.id, value: e.value, isDefault: e.isDefault })),
  };
}

/** The stored form of a typed value — the server folds identically before validating. */
export function foldNamespaceValue(value: string): string {
  return value.trim().toLowerCase();
}

/**
 * Form values -> the PUT body (local keys stripped, values folded, ids preserved). The array
 * order IS the stored order — the server rewrites positions from it.
 */
export function toUpdateBody(values: NamespaceFormValues): DictionaryUpdateBody {
  return {
    items: values.entries.map((e) => ({
      id: e.id,
      value: foldNamespaceValue(e.value),
      isDefault: e.isDefault,
    })),
  };
}

/**
 * Mirrors the server's payload rules (validateDictionaryUpdate): every value must satisfy the
 * namespace grammar after folding (1-63 lowercase alphanumeric runs with single dashes) and
 * be unique within the document. The duplicate flag lands on the LATER row so the first
 * occurrence stays valid.
 */
export function namespaceFormValidation(t: TFunction) {
  return {
    entries: {
      value: (v: string, values: NamespaceFormValues, path: string) => {
        const folded = foldNamespaceValue(v);
        if (!folded) return t("namespaces.valueRequired");
        if (folded.length > MAX_ENTITY_PART_LENGTH || !NAMESPACE_RE.test(folded)) {
          return t("namespaces.valueInvalid");
        }
        // path is `entries.<index>.value` — [1] is the row index.
        const index = Number(path.split(".")[1]);
        const earlier = values.entries.slice(0, index);
        if (earlier.some((e) => foldNamespaceValue(e.value) === folded)) {
          return t("namespaces.valueDuplicate");
        }
        return null;
      },
    },
  };
}

/**
 * The document-level rule the per-field validation can't express: a non-empty document must
 * flag exactly one default. The radio UI makes >1 impossible, so the page only has to guard
 * "none" (e.g. after removing the flagged row) — surfaced as an inline alert on save.
 */
export function missingDefault(values: NamespaceFormValues): boolean {
  return values.entries.length > 0 && !values.entries.some((e) => e.isDefault);
}

/** The shared mutation-error -> message mapping for namespace saves. */
export function namespaceSaveErrorMessage(err: unknown, t: TFunction): string {
  return saveErrorMessage(err, t, {
    forbidden: "namespaces.error.permission",
    conflict: "namespaces.error.conflict",
    invalid: "namespaces.error.validation",
    failedStatus: "namespaces.error.saveFailedStatus",
    failed: "namespaces.error.saveFailed",
  });
}
