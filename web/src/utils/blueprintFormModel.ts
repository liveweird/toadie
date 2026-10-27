import type { FormErrors } from "@mantine/form";

// The blueprint form's model: the field vocabulary, the per-row drafts and their empty
// constructors, the type-applicability table and the row-family helpers. The wire mapping lives
// in blueprintFormMapping.ts, the validation factory in blueprintFormValidation.ts.

// Port's rules (.claude/docs/port-data-model.md) mirrored client-side.
export const MAX_IDENTIFIER_LENGTH = 100;
export const MAX_TITLE_LENGTH = 100;
export const MAX_DESCRIPTION_LENGTH = 2000;
export const MAX_ICON_LENGTH = 100;

export const PROPERTY_TYPES = ["string", "number", "boolean", "array", "object"] as const;
export type PropertyType = (typeof PROPERTY_TYPES)[number];

export const STRING_FORMATS = [
  "url", "email", "idn-email", "user", "team", "date-time", "timer", "yaml", "markdown", "proto", "ipv4", "ipv6",
] as const;
export const DATE_FORMATS = ["relative", "12-hour", "24-hour", "YYYY-MM-DD HH:mm"] as const;
export const STRING_SPECS = ["open-api", "async-api", "embedded-url"] as const;
export const OBJECT_SPECS = ["open-api", "async-api"] as const;
export const OBJECT_FORMATS = ["labeled-url"] as const;
export const ARRAY_ITEM_TYPES = ["string", "number", "boolean", "object"] as const;
export const ENUM_COLORS = [
  "blue", "turquoise", "orange", "purple", "pink", "yellow", "green", "red",
  "darkGray", "lightGray", "bronze", "gold", "silver", "paleBlue",
] as const;
export const AGGREGATION_CALCULATION_BY = ["entities", "property"] as const;
export const AGGREGATION_FUNCS_ENTITIES = ["count", "average"] as const;
export const AGGREGATION_FUNCS_PROPERTY = ["average", "sum", "min", "max", "median"] as const;
export const AVERAGE_OF = ["hour", "day", "week", "month", "total"] as const;
export const OWNERSHIP_TYPES = ["Direct", "Inherited"] as const;

/** The per-row applicability table (the catalogFileForm.ts KIND_FIELDS idiom): a field a
 *  type switch left behind simply no-ops instead of leaving a stale error or a stale value
 *  in the wire request. */
export type PropertyFieldName =
  | "format" | "dateFormat" | "pattern" | "minLength" | "maxLength"
  | "spec" | "specAuthorizationUrl" | "specTokenUrl" | "specClientId" | "specAuthorizationScope"
  | "minimum" | "maximum" | "exclusiveMinimum" | "exclusiveMaximum"
  | "enumValues" | "enumColors"
  | "itemsType" | "itemsFormat" | "minItems" | "maxItems" | "uniqueItems"
  | "objectFormat" | "objectSpec" | "objectSchemaJson"
  | "defaultText" | "defaultBool" | "defaultList";

const TYPE_FIELDS: Record<PropertyType, readonly PropertyFieldName[]> = {
  string: [
    "format", "dateFormat", "pattern", "minLength", "maxLength",
    "spec", "specAuthorizationUrl", "specTokenUrl", "specClientId", "specAuthorizationScope",
    "enumValues", "enumColors", "defaultText",
  ],
  number: ["minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "enumValues", "enumColors", "defaultText"],
  boolean: ["defaultBool"],
  array: ["itemsType", "itemsFormat", "minItems", "maxItems", "uniqueItems", "defaultList"],
  object: ["objectFormat", "objectSpec", "objectSchemaJson", "defaultText"],
};

export function propertyFieldApplies(type: PropertyType, field: PropertyFieldName): boolean {
  return TYPE_FIELDS[type].includes(field);
}

// -- Drafts: flat per-row values with a local `key` for React list identity (the
// namespaceForm.ts idiom) — `id` is the Port identifier itself (blueprints store these
// definitions in identifier-keyed maps, not database rows). Numbers are held as STRINGS —
// NumberInput emits number|string, and coercing an empty field with Number("") would submit
// a stray 0; parsing happens only in toBlueprintRequest.

export type PropertyDraft = {
  key: string;
  id: string;
  type: PropertyType;
  title: string;
  description: string;
  icon: string;
  required: boolean;
  /** Fill-in tier 1-4 (2.18.0, a Toadie-only hint); null = untiered. Lives on the row, so a rename carries it and a delete drops it. */
  tier: number | null;
  format: string;
  dateFormat: string;
  pattern: string;
  minLength: string;
  maxLength: string;
  spec: string;
  specAuthorizationUrl: string;
  specTokenUrl: string;
  specClientId: string;
  specAuthorizationScope: string[];
  minimum: string;
  maximum: string;
  exclusiveMinimum: string;
  exclusiveMaximum: string;
  enumValues: string[];
  enumColors: Record<string, string>;
  itemsType: string;
  itemsFormat: string;
  minItems: string;
  maxItems: string;
  uniqueItems: boolean;
  objectFormat: string;
  objectSpec: string;
  objectSchemaJson: string;
  defaultText: string;
  defaultBool: "" | "true" | "false";
  defaultList: string[];
};

export type RelationDraft = {
  key: string;
  id: string;
  title: string;
  description: string;
  target: string;
  required: boolean;
  many: boolean;
  /** Fill-in tier 1-4 (2.18.0); null = untiered. */
  tier: number | null;
};

export type MirrorDraft = {
  key: string;
  id: string;
  title: string;
  path: string;
};

export type CalculationDraft = {
  key: string;
  id: string;
  title: string;
  type: PropertyType;
  format: string;
  spec: string;
  calculation: string;
  colorized: boolean;
  colorsJson: string;
};

export type AggregationDraft = {
  key: string;
  id: string;
  title: string;
  target: string;
  calculationBy: string;
  func: string;
  property: string;
  averageOf: string;
  measureTimeBy: string;
  queryJson: string;
  pathFilterJson: string;
};

export type BlueprintFormValues = {
  identifier: string;
  title: string;
  description: string;
  icon: string;
  properties: PropertyDraft[];
  relations: RelationDraft[];
  mirrorProperties: MirrorDraft[];
  calculationProperties: CalculationDraft[];
  aggregationProperties: AggregationDraft[];
  /** "" = no ownership section (the request omits it entirely). */
  ownershipType: string;
  ownershipTitle: string;
  ownershipPath: string;
  /**
   * Hierarchy identifier (an active `hierarchies` dictionary value) -> relation id (a Toadie-
   * only extension, v1.25.0; multi-hierarchy since v1.32.0 — one blueprint may feed several
   * entity hierarchies, one relation per hierarchy, and one relation MAY serve several
   * hierarchies). DERIVE, DON'T CLEAR, PER ENTRY: each `HierarchyFieldset` Select guards its
   * own displayed value the same way `toBlueprintRequest` guards emission below, so flipping a
   * relation to `many` or removing it silently drops just THAT entry from the preview/request,
   * with no effect needed to keep the two in sync. Whether a KEY still names an active
   * `hierarchies` dictionary entry is a separate, UI-only concern (`HierarchyFieldset`'s
   * warning row): an entry whose key left the dictionary is still EMITTED here so the
   * server's `hierarchyRelations names an unknown hierarchy` 400 surfaces and the admin has
   * to clear it explicitly, rather than the client silently dropping evidence of the problem.
   */
  hierarchyRelations: Record<string, string>;
  /** The blueprint's own fill-in tier 1-4 (2.18.0); null = untiered. */
  tier: number | null;
  /** The blueprint's source reference (2.10.0) — row state for the whole request, never part
   *  of the Port document `toBlueprintRequest` builds; sent separately by `useBlueprintSave.ts`,
   *  the `EntityFormValues.sourceUrl` idiom one level down. "" = unset. */
  sourceUrl: string;
};

/** The relations a blueprint's `hierarchyRelations` entries may name — single (`many === false`) ones
 *  with a non-blank id (the server's rule, `BlueprintValidation.kt`). */
export function singleRelationIds(relations: RelationDraft[]): string[] {
  return relations.filter((r) => r.id.trim() && !r.many).map((r) => r.id.trim());
}

// -- The five row families (EditorRowList, v1.23.2) -------------------------------------

/** The five foldable row lists, in the fixed order the editor renders them — also the
 *  order a blocked submit reveals errors in (family, then row position). */
export const ROW_FAMILIES = [
  "properties",
  "relations",
  "mirrorProperties",
  "calculationProperties",
  "aggregationProperties",
] as const;
export type RowFamily = (typeof ROW_FAMILIES)[number];

const ROW_FAMILY_ERROR_RE =
  /^(properties|relations|mirrorProperties|calculationProperties|aggregationProperties)\.(\d+)\./;

/** The rows carrying at least one validation error, deduped and ordered family-then-index
 *  (`ROW_FAMILIES` order, then row position) — feeds the blocked-submit auto-reveal
 *  (`hooks/useBlueprintRowExpansion.ts`'s `revealErrors`). */
export function rowsWithErrors(errors: FormErrors): { family: RowFamily; index: number }[] {
  const seen = new Set<string>();
  const found: { family: RowFamily; index: number }[] = [];
  for (const key of Object.keys(errors)) {
    const match = ROW_FAMILY_ERROR_RE.exec(key);
    if (!match) continue;
    const family = match[1] as RowFamily;
    const index = Number(match[2]);
    const dedupeKey = `${family}.${index}`;
    if (seen.has(dedupeKey)) continue;
    seen.add(dedupeKey);
    found.push({ family, index });
  }
  return ROW_FAMILIES.flatMap((family) =>
    found.filter((entry) => entry.family === family).sort((a, b) => a.index - b.index),
  );
}


let keyCounter = 0;
export function newDraftKey(prefix: string): string {
  keyCounter += 1;
  return `bp-${prefix}-${keyCounter}`;
}

export function emptyPropertyDraft(): PropertyDraft {
  return {
    key: newDraftKey("prop"),
    id: "",
    type: "string",
    title: "",
    description: "",
    icon: "",
    required: false,
    tier: null,
    format: "",
    dateFormat: "",
    pattern: "",
    minLength: "",
    maxLength: "",
    spec: "",
    specAuthorizationUrl: "",
    specTokenUrl: "",
    specClientId: "",
    specAuthorizationScope: [],
    minimum: "",
    maximum: "",
    exclusiveMinimum: "",
    exclusiveMaximum: "",
    enumValues: [],
    enumColors: {},
    itemsType: "",
    itemsFormat: "",
    minItems: "",
    maxItems: "",
    uniqueItems: false,
    objectFormat: "",
    objectSpec: "",
    objectSchemaJson: "",
    defaultText: "",
    defaultBool: "",
    defaultList: [],
  };
}

export function emptyRelationDraft(): RelationDraft {
  return { key: newDraftKey("rel"), id: "", title: "", description: "", target: "", required: false, many: false, tier: null };
}

export function emptyMirrorDraft(): MirrorDraft {
  return { key: newDraftKey("mirror"), id: "", title: "", path: "" };
}

export function emptyCalculationDraft(): CalculationDraft {
  return {
    key: newDraftKey("calc"),
    id: "",
    title: "",
    type: "string",
    format: "",
    spec: "",
    calculation: "",
    colorized: false,
    colorsJson: "",
  };
}

export function emptyAggregationDraft(): AggregationDraft {
  return {
    key: newDraftKey("agg"),
    id: "",
    title: "",
    target: "",
    calculationBy: "entities",
    func: "count",
    property: "",
    averageOf: "",
    measureTimeBy: "",
    queryJson: "",
    pathFilterJson: "",
  };
}

export function emptyBlueprintForm(): BlueprintFormValues {
  return {
    identifier: "",
    title: "",
    description: "",
    icon: "",
    properties: [],
    relations: [],
    mirrorProperties: [],
    calculationProperties: [],
    aggregationProperties: [],
    ownershipType: "",
    ownershipTitle: "",
    ownershipPath: "",
    hierarchyRelations: {},
    tier: null,
    sourceUrl: "",
  };
}
