import type { TFunction } from "i18next";
import type { ConversionMapping, ConversionMappingBody } from "../api/conversionMappings";
import { saveErrorMessage } from "./saveError";

/** The server's name cap (`MAX_CONVERSION_MAPPING_NAME_LENGTH`). */
export const MAX_MAPPING_NAME_LENGTH = 100;

export type ConversionMappingFormValues = {
  name: string;
  /** The mapping document as editable JSON text. */
  document: string;
};

/** A fresh mapping starts from the smallest document the server accepts. */
const EMPTY_DOCUMENT = { schemaVersion: 1, apiVersion: "backstage.io/v1alpha1", pairs: [] };

export const emptyConversionMappingForm = (): ConversionMappingFormValues => ({
  name: "",
  document: JSON.stringify(EMPTY_DOCUMENT, null, 2),
});

export const fromConversionMappingResponse = (mapping: ConversionMapping): ConversionMappingFormValues => ({
  name: mapping.name,
  document: JSON.stringify(mapping.document, null, 2),
});

type DocumentParse = { ok: true; value: Record<string, unknown> } | { ok: false; reason: "syntax" | "object"; message: string };

/** Parses the document text; a failure carries which rule it broke (the JSON engine's message
 *  is kept for `syntax`, it locates the offending character). */
function parseDocument(text: string): DocumentParse {
  try {
    const value: unknown = JSON.parse(text);
    if (value === null || typeof value !== "object" || Array.isArray(value)) {
      return { ok: false, reason: "object", message: "" };
    }
    return { ok: true, value: value as Record<string, unknown> };
  } catch (err) {
    return { ok: false, reason: "syntax", message: err instanceof Error ? err.message : "" };
  }
}

/** Grammar only — the server is the gate for everything about the document's meaning. */
export function conversionMappingFormValidation(t: TFunction) {
  return {
    name: (value: string) => {
      const trimmed = value.trim();
      if (trimmed === "") return t("conversion.mappings.validation.nameRequired");
      if (trimmed.length > MAX_MAPPING_NAME_LENGTH) return t("conversion.mappings.validation.nameLength");
      return null;
    },
    document: (value: string) => {
      if (value.trim() === "") return t("conversion.mappings.validation.documentRequired");
      const parsed = parseDocument(value);
      if (parsed.ok) return null;
      return parsed.reason === "object"
        ? t("conversion.mappings.validation.documentObject")
        : t("conversion.mappings.validation.documentSyntax", { message: parsed.message });
    },
  };
}

/** The request body for already-validated values. */
export function toConversionMappingRequest(values: ConversionMappingFormValues): ConversionMappingBody {
  const parsed = parseDocument(values.document);
  return { name: values.name.trim(), document: parsed.ok ? parsed.value : {} };
}

/** Save failures: a 400 WITHOUT findings (size cap, registry full) and the 403/404/409 mappings. */
export function conversionMappingSaveErrorMessage(err: unknown, t: TFunction): string {
  return saveErrorMessage(err, t, {
    forbidden: "conversion.mappings.saveForbidden",
    notFound: "conversion.mappings.saveGone",
    conflict: "conversion.mappings.saveConflict",
    invalid: "conversion.mappings.saveInvalid",
    failedStatus: "common.error.saveFailedStatus",
    failed: "common.error.saveFailedNetwork",
  });
}

export function conversionMappingDeleteErrorMessage(err: unknown, t: TFunction): string {
  return saveErrorMessage(err, t, {
    forbidden: "conversion.mappings.saveForbidden",
    notFound: "conversion.mappings.saveGone",
    failedStatus: "common.error.actionFailedStatus",
    failed: "common.error.actionFailed",
  });
}

/** The number of pairs in a stored document (the list column); 0 for a malformed one. */
export function pairCount(mapping: ConversionMapping): number {
  const pairs = (mapping.document as { pairs?: unknown }).pairs;
  return Array.isArray(pairs) ? pairs.length : 0;
}
