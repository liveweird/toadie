import type { TFunction } from "i18next";
import { saveErrorMessage } from "./saveError";
import { sourceUrlProblem } from "./sourceUrl";
import { safeJsonParse } from "./blueprintFormMapping";
import {
  AGGREGATION_FUNCS_ENTITIES,
  AGGREGATION_FUNCS_PROPERTY,
  MAX_DESCRIPTION_LENGTH,
  MAX_ICON_LENGTH,
  MAX_IDENTIFIER_LENGTH,
  MAX_TITLE_LENGTH,
  type BlueprintFormValues,
} from "./blueprintFormModel";

// Port's rules (.claude/docs/port-data-model.md) mirrored client-side.
const MAX_PATTERN_LENGTH = 500;
const MAX_CALCULATION_LENGTH = 10000;
const MAX_ENUM_VALUES = 200;
const MAX_PATH_SEGMENTS = 10;

// The identifier charset is Port's stated UI rule (assumption — no documented regex); never
// starts with `$` (reserved for meta-properties).
const IDENTIFIER_RE = /^[A-Za-z0-9@_.:/=-]+$/;

export function isValidBlueprintIdentifier(value: string): boolean {
  return (
    value.length >= 1 &&
    value.length <= MAX_IDENTIFIER_LENGTH &&
    !value.startsWith("$") &&
    IDENTIFIER_RE.test(value)
  );
}

// -- Validation (mirrors BlueprintValidation.kt / PropertyValidation.kt) -----------------

type PropertyFamily = "properties" | "mirrorProperties" | "calculationProperties" | "aggregationProperties";
const PROPERTY_FAMILY_ORDER: readonly PropertyFamily[] = [
  "properties", "mirrorProperties", "calculationProperties", "aggregationProperties",
];

/** Property, mirror, calculation and aggregation identifiers share ONE namespace (Port
 *  entities expose them all under `properties`) — duplicates are flagged on the row that
 *  occurs LATER in this fixed family order, mirroring the server's combined-namespace check. */
function combinedPropertyIds(values: BlueprintFormValues): { family: PropertyFamily; index: number; id: string }[] {
  const families: Record<PropertyFamily, { id: string }[]> = {
    properties: values.properties,
    mirrorProperties: values.mirrorProperties,
    calculationProperties: values.calculationProperties,
    aggregationProperties: values.aggregationProperties,
  };
  return PROPERTY_FAMILY_ORDER.flatMap((family) =>
    families[family].map((draft, index) => ({ family, index, id: draft.id.trim() })),
  );
}

function isDuplicatePropertyId(values: BlueprintFormValues, family: PropertyFamily, index: number, id: string): boolean {
  const folded = id.trim();
  if (!folded) return false;
  const combined = combinedPropertyIds(values);
  const selfPos = combined.findIndex((entry) => entry.family === family && entry.index === index);
  return combined.slice(0, selfPos).some((entry) => entry.id === folded);
}

function isDuplicateRelationId(values: BlueprintFormValues, index: number, id: string): boolean {
  const folded = id.trim();
  if (!folded) return false;
  return values.relations.slice(0, index).some((r) => r.id.trim() === folded);
}

function rowIndex(path: string): number {
  return Number(path.split(".")[1]);
}

function minMaxRule(
  draft: { minLength: string; maxLength: string } | { minimum: string; maximum: string } | { minItems: string; maxItems: string } | undefined,
  min: string,
  max: string,
  t: TFunction,
): string | null {
  if (!draft) return null;
  const minTrim = min.trim();
  const maxTrim = max.trim();
  if (!minTrim || !maxTrim) return null;
  const minN = Number(minTrim);
  const maxN = Number(maxTrim);
  if (Number.isNaN(minN) || Number.isNaN(maxN)) return null;
  return minN <= maxN ? null : t("blueprints.validation.minMax");
}

function pathRule(value: string, relationIds: Set<string>, t: TFunction): string | null {
  const v = value.trim();
  if (!v) return t("blueprints.validation.required");
  const segments = v.split(".");
  if (segments.length > MAX_PATH_SEGMENTS) return t("blueprints.validation.pathLength");
  return relationIds.has(segments[0]) ? null : t("blueprints.validation.pathFirstSegment");
}

function jsonFieldRule(value: string, t: TFunction): string | null {
  if (!value.trim()) return null;
  return safeJsonParse(value) === undefined ? t("blueprints.validation.jsonInvalid") : null;
}

/** The blueprint's own identity fields: identifier/title/description/icon. */
function identityRules(t: TFunction) {
  return {
    identifier: (value: string) => (isValidBlueprintIdentifier(value.trim()) ? null : t("blueprints.validation.identifier")),
    title: (value: string) => {
      const v = value.trim();
      return v && v.length <= MAX_TITLE_LENGTH ? null : t("blueprints.validation.title");
    },
    description: (value: string) =>
      value.length <= MAX_DESCRIPTION_LENGTH ? null : t("blueprints.validation.descriptionLength"),
    icon: (value: string) => (value.length <= MAX_ICON_LENGTH ? null : t("blueprints.validation.iconLength")),
    sourceUrl: (value: string) => (sourceUrlProblem(value) ? t("blueprints.validation.sourceUrl") : null),
  };
}

/** The `schema.properties` rows: id/title/pattern/min-max pairs/enum/default/object schema. */
function propertyRules(t: TFunction) {
  return {
    id: (value: string, values: BlueprintFormValues, path: string) => {
      const v = value.trim();
      if (!isValidBlueprintIdentifier(v)) return t("blueprints.validation.propertyId");
      return isDuplicatePropertyId(values, "properties", rowIndex(path), v)
        ? t("blueprints.validation.propertyIdDuplicate")
        : null;
    },
    title: (value: string) => (value.trim() ? null : t("blueprints.validation.required")),
    pattern: (value: string, values: BlueprintFormValues, path: string) => {
      const draft = values.properties[rowIndex(path)];
      if (!draft || draft.type !== "string" || !value.trim()) return null;
      if (value.length > MAX_PATTERN_LENGTH) return t("blueprints.validation.patternLength");
      try {
        new RegExp(value);
        return null;
      } catch {
        return t("blueprints.validation.patternInvalid");
      }
    },
    minLength: (value: string, values: BlueprintFormValues, path: string) =>
      minMaxRule(values.properties[rowIndex(path)], value, values.properties[rowIndex(path)]?.maxLength ?? "", t),
    minimum: (value: string, values: BlueprintFormValues, path: string) =>
      minMaxRule(values.properties[rowIndex(path)], value, values.properties[rowIndex(path)]?.maximum ?? "", t),
    minItems: (value: string, values: BlueprintFormValues, path: string) =>
      minMaxRule(values.properties[rowIndex(path)], value, values.properties[rowIndex(path)]?.maxItems ?? "", t),
    exclusiveMinimum: (value: string, values: BlueprintFormValues, path: string) => {
      const draft = values.properties[rowIndex(path)];
      if (!draft || draft.type !== "number") return null;
      if (draft.minimum.trim() && value.trim()) return t("blueprints.validation.exclusiveConflict");
      return minMaxRule(draft, value, draft.exclusiveMaximum, t);
    },
    exclusiveMaximum: (value: string, values: BlueprintFormValues, path: string) => {
      const draft = values.properties[rowIndex(path)];
      if (!draft || draft.type !== "number") return null;
      return draft.maximum.trim() && value.trim() ? t("blueprints.validation.exclusiveConflict") : null;
    },
    enumValues: (value: string[], values: BlueprintFormValues, path: string) => {
      const draft = values.properties[rowIndex(path)];
      if (!draft || (draft.type !== "string" && draft.type !== "number") || value.length === 0) return null;
      if (value.length > MAX_ENUM_VALUES) return t("blueprints.validation.enumCount");
      if (draft.type === "number" && value.some((v) => Number.isNaN(Number(v.trim())))) {
        return t("blueprints.validation.enumNumber");
      }
      const folded = value.map((v) => v.trim());
      return folded.length === new Set(folded).size ? null : t("blueprints.validation.enumDuplicate");
    },
    defaultText: (value: string, values: BlueprintFormValues, path: string) => {
      const draft = values.properties[rowIndex(path)];
      if (!draft || !value.trim()) return null;
      if (draft.type === "number" && Number.isNaN(Number(value.trim()))) return t("blueprints.validation.defaultNumber");
      if (draft.type === "object") return jsonFieldRule(value, t);
      if (draft.enumValues.length > 0 && !draft.enumValues.includes(value.trim())) {
        return t("blueprints.validation.defaultNotInEnum");
      }
      return null;
    },
    objectSchemaJson: (value: string, values: BlueprintFormValues, path: string) => {
      const draft = values.properties[rowIndex(path)];
      return draft?.type === "object" ? jsonFieldRule(value, t) : null;
    },
  };
}

/** The `relations` rows: id/title/target/the required+many conflict. */
function relationRules(t: TFunction) {
  return {
    id: (value: string, values: BlueprintFormValues, path: string) => {
      const v = value.trim();
      if (!isValidBlueprintIdentifier(v)) return t("blueprints.validation.relationId");
      return isDuplicateRelationId(values, rowIndex(path), v) ? t("blueprints.validation.relationIdDuplicate") : null;
    },
    title: (value: string) => (value.trim() ? null : t("blueprints.validation.required")),
    target: (value: string) => (value.trim() ? null : t("blueprints.validation.targetRequired")),
    many: (value: boolean, values: BlueprintFormValues, path: string) => {
      const draft = values.relations[rowIndex(path)];
      return draft?.required && value ? t("blueprints.validation.relationRequiredMany") : null;
    },
  };
}

/** The three computed-property families (mirror/calculation/aggregation, phase 5's vocabulary). */
function computedRules(t: TFunction) {
  return {
    mirrorProperties: {
      id: (value: string, values: BlueprintFormValues, path: string) => {
        const v = value.trim();
        if (!isValidBlueprintIdentifier(v)) return t("blueprints.validation.propertyId");
        return isDuplicatePropertyId(values, "mirrorProperties", rowIndex(path), v)
          ? t("blueprints.validation.propertyIdDuplicate")
          : null;
      },
      title: (value: string) => (value.trim() ? null : t("blueprints.validation.required")),
      path: (value: string, values: BlueprintFormValues) =>
        pathRule(value, new Set(values.relations.map((r) => r.id.trim())), t),
    },
    calculationProperties: {
      id: (value: string, values: BlueprintFormValues, path: string) => {
        const v = value.trim();
        if (!isValidBlueprintIdentifier(v)) return t("blueprints.validation.propertyId");
        return isDuplicatePropertyId(values, "calculationProperties", rowIndex(path), v)
          ? t("blueprints.validation.propertyIdDuplicate")
          : null;
      },
      title: (value: string) => (value.trim() ? null : t("blueprints.validation.required")),
      calculation: (value: string) => {
        const v = value.trim();
        if (!v) return t("blueprints.validation.required");
        return v.length <= MAX_CALCULATION_LENGTH ? null : t("blueprints.validation.calculationLength");
      },
      colorsJson: (value: string) => jsonFieldRule(value, t),
    },
    aggregationProperties: {
      id: (value: string, values: BlueprintFormValues, path: string) => {
        const v = value.trim();
        if (!isValidBlueprintIdentifier(v)) return t("blueprints.validation.propertyId");
        return isDuplicatePropertyId(values, "aggregationProperties", rowIndex(path), v)
          ? t("blueprints.validation.propertyIdDuplicate")
          : null;
      },
      title: (value: string) => (value.trim() ? null : t("blueprints.validation.required")),
      target: (value: string) => (value.trim() ? null : t("blueprints.validation.targetRequired")),
      property: (value: string, values: BlueprintFormValues, path: string) => {
        const draft = values.aggregationProperties[rowIndex(path)];
        if (!draft) return null;
        if (draft.calculationBy === "property" && !value.trim()) return t("blueprints.validation.required");
        if (draft.calculationBy === "entities" && value.trim()) return t("blueprints.validation.aggregationPropertyForbidden");
        return null;
      },
      func: (value: string, values: BlueprintFormValues, path: string) => {
        const draft = values.aggregationProperties[rowIndex(path)];
        if (!draft) return null;
        const allowed: readonly string[] =
          draft.calculationBy === "property" ? AGGREGATION_FUNCS_PROPERTY : AGGREGATION_FUNCS_ENTITIES;
        return allowed.includes(value) ? null : t("blueprints.validation.aggregationFunc");
      },
      queryJson: (value: string) => jsonFieldRule(value, t),
      pathFilterJson: (value: string) => jsonFieldRule(value, t),
    },
  };
}

/** The `ownershipPath` field: only required/checked while `ownershipType === "Inherited"`. */
function ownershipRules(t: TFunction) {
  return {
    ownershipPath: (value: string, values: BlueprintFormValues) =>
      values.ownershipType === "Inherited"
        ? pathRule(value, new Set(values.relations.map((r) => r.id.trim())), t)
        : null,
  };
}

/** Validation rules for the create/edit page (mirrors the server's checks), composed from the
 *  per-family rule sets above. */
export function blueprintFormValidation(t: TFunction) {
  return {
    ...identityRules(t),
    properties: propertyRules(t),
    relations: relationRules(t),
    ...computedRules(t),
    ...ownershipRules(t),
  };
}

/** The blueprint save's fixed error vocabulary (409 = an active blueprint already holds the
 *  identifier). No waiver flow — a blueprint definition carries no soft/registry findings. */
export function blueprintSaveErrorMessage(err: unknown, t: TFunction): string {
  return saveErrorMessage(err, t, {
    forbidden: "blueprints.saveForbidden",
    conflict: "blueprints.saveConflict",
    invalid: "blueprints.saveInvalid",
    notFound: "blueprints.saveGone",
    failedStatus: "common.error.actionFailedStatus",
    failed: "common.error.actionFailed",
  });
}

/** The blueprint delete's fixed error vocabulary (409 = still targeted by another blueprint's
 *  relation/aggregation — the problem detail names the referrers, kept in the alert body). */
export function blueprintDeleteErrorMessage(err: unknown, t: TFunction): string {
  return saveErrorMessage(err, t, {
    conflict: "blueprints.deleteConflict",
    failedStatus: "common.error.actionFailedStatus",
    failed: "common.error.actionFailed",
  });
}
