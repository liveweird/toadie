import type { FormErrors } from "@mantine/form";
import type { TFunction } from "i18next";
import type { Blueprint, BlueprintBody } from "../api/blueprints";
import { saveErrorMessage } from "./saveError";

// Wire shapes, derived structurally from the generated contract (the catalogFileForm.ts
// EntitySpecWire idiom) rather than hand-typed — a drift between this file and the actual
// OpenAPI spec surfaces as a type error at the usage site, not a silent mismatch.
type SchemaWire = NonNullable<BlueprintBody["schema"]>;
type PropertyDefinitionWire = SchemaWire["properties"][string];
type RelationDefinitionWire = NonNullable<BlueprintBody["relations"]>[string];
type MirrorPropertyDefinitionWire = NonNullable<BlueprintBody["mirrorProperties"]>[string];
type CalculationPropertyDefinitionWire = NonNullable<BlueprintBody["calculationProperties"]>[string];
type AggregationPropertyDefinitionWire = NonNullable<BlueprintBody["aggregationProperties"]>[string];
type OwnershipWire = NonNullable<BlueprintBody["ownership"]>;

// Port's rules (.claude/docs/port-data-model.md) mirrored client-side.
export const MAX_IDENTIFIER_LENGTH = 100;
export const MAX_TITLE_LENGTH = 100;
export const MAX_DESCRIPTION_LENGTH = 2000;
export const MAX_ICON_LENGTH = 100;
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
};

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
function newDraftKey(prefix: string): string {
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
  return { key: newDraftKey("rel"), id: "", title: "", description: "", target: "", required: false, many: false };
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
  };
}

/** A JSON textarea's raw text -> parsed value, or undefined for blank/invalid input — so the
 *  live preview never throws mid-typing. The value's own validity rule reports the error. */
export function safeJsonParse<T = unknown>(text: string): T | undefined {
  const trimmed = text.trim();
  if (!trimmed) return undefined;
  try {
    return JSON.parse(trimmed) as T;
  } catch {
    return undefined;
  }
}

const orUndefined = (value: string) => value.trim() || undefined;

function toNumberOrUndefined(text: string): number | undefined {
  const trimmed = text.trim();
  if (!trimmed) return undefined;
  const n = Number(trimmed);
  return Number.isFinite(n) ? n : undefined;
}

function numberToText(value: number | undefined | null): string {
  return value === undefined || value === null ? "" : String(value);
}

// -- Draft -> wire (toBlueprintRequest) --------------------------------------------------

function enumFor(draft: PropertyDraft): (string | number)[] | undefined {
  if (draft.enumValues.length === 0) return undefined;
  if (draft.type === "number") {
    const numbers = draft.enumValues.map((v) => Number(v)).filter((n) => Number.isFinite(n));
    return numbers.length > 0 ? numbers : undefined;
  }
  return [...draft.enumValues];
}

function enumColorsFor(draft: PropertyDraft): Record<string, string> | undefined {
  const entries = Object.entries(draft.enumColors).filter(([key]) => draft.enumValues.includes(key));
  return entries.length > 0 ? Object.fromEntries(entries) : undefined;
}

function coerceArrayItem(value: string, itemsType: string): unknown {
  if (itemsType === "number") return Number(value);
  if (itemsType === "boolean") return value === "true";
  return value;
}

function defaultFor(draft: PropertyDraft): unknown {
  switch (draft.type) {
    case "boolean":
      return draft.defaultBool === "" ? undefined : draft.defaultBool === "true";
    case "array":
      return draft.defaultList.length > 0
        ? draft.defaultList.map((v) => coerceArrayItem(v, draft.itemsType))
        : undefined;
    case "number":
      return toNumberOrUndefined(draft.defaultText);
    case "object":
      return safeJsonParse(draft.defaultText);
    default:
      return draft.defaultText.trim() || undefined;
  }
}

type ObjectSchemaJson = {
  properties?: Record<string, unknown>;
  patternProperties?: Record<string, unknown>;
  additionalProperties?: unknown;
};

function specAuthenticationFor(draft: PropertyDraft) {
  const { specAuthorizationUrl, specTokenUrl, specClientId, specAuthorizationScope } = draft;
  if (!specAuthorizationUrl.trim() && !specTokenUrl.trim() && !specClientId.trim() && specAuthorizationScope.length === 0) {
    return undefined;
  }
  return {
    authorizationUrl: orUndefined(specAuthorizationUrl),
    tokenUrl: orUndefined(specTokenUrl),
    clientId: orUndefined(specClientId),
    authorizationScope: specAuthorizationScope.length > 0 ? [...specAuthorizationScope] : undefined,
  };
}

function itemsFor(draft: PropertyDraft) {
  if (!draft.itemsType) return undefined;
  return {
    type: draft.itemsType,
    format: draft.itemsType === "string" ? orUndefined(draft.itemsFormat) : undefined,
  };
}

function propertyDefinitionFor(draft: PropertyDraft): PropertyDefinitionWire {
  const type = draft.type;
  const objectSchema = type === "object" ? safeJsonParse<ObjectSchemaJson>(draft.objectSchemaJson) : undefined;
  return {
    type,
    title: orUndefined(draft.title),
    description: orUndefined(draft.description),
    icon: orUndefined(draft.icon),
    format: type === "string" ? orUndefined(draft.format) : type === "object" ? orUndefined(draft.objectFormat) : undefined,
    date_format: type === "string" && draft.format === "date-time" ? orUndefined(draft.dateFormat) : undefined,
    pattern: type === "string" ? orUndefined(draft.pattern) : undefined,
    minLength: type === "string" ? toNumberOrUndefined(draft.minLength) : undefined,
    maxLength: type === "string" ? toNumberOrUndefined(draft.maxLength) : undefined,
    enum: type === "string" || type === "number" ? enumFor(draft) : undefined,
    enumColors: type === "string" || type === "number" ? enumColorsFor(draft) : undefined,
    spec: type === "string" ? orUndefined(draft.spec) : type === "object" ? orUndefined(draft.objectSpec) : undefined,
    specAuthentication: type === "string" && draft.spec === "embedded-url" ? specAuthenticationFor(draft) : undefined,
    minimum: type === "number" ? toNumberOrUndefined(draft.minimum) : undefined,
    maximum: type === "number" ? toNumberOrUndefined(draft.maximum) : undefined,
    exclusiveMinimum: type === "number" ? toNumberOrUndefined(draft.exclusiveMinimum) : undefined,
    exclusiveMaximum: type === "number" ? toNumberOrUndefined(draft.exclusiveMaximum) : undefined,
    items: type === "array" ? itemsFor(draft) : undefined,
    minItems: type === "array" ? toNumberOrUndefined(draft.minItems) : undefined,
    maxItems: type === "array" ? toNumberOrUndefined(draft.maxItems) : undefined,
    uniqueItems: type === "array" && draft.uniqueItems ? true : undefined,
    properties: type === "object" ? objectSchema?.properties : undefined,
    patternProperties: type === "object" ? objectSchema?.patternProperties : undefined,
    additionalProperties: type === "object" ? objectSchema?.additionalProperties : undefined,
    default: defaultFor(draft),
  } as PropertyDefinitionWire;
}

function relationDefinitionFor(draft: RelationDraft): RelationDefinitionWire {
  return {
    title: draft.title.trim(),
    description: orUndefined(draft.description),
    target: draft.target.trim(),
    required: draft.required,
    many: draft.many,
  } as RelationDefinitionWire;
}

function mirrorDefinitionFor(draft: MirrorDraft): MirrorPropertyDefinitionWire {
  return { title: draft.title.trim(), path: draft.path.trim() } as MirrorPropertyDefinitionWire;
}

function calculationDefinitionFor(draft: CalculationDraft): CalculationPropertyDefinitionWire {
  return {
    title: draft.title.trim(),
    type: draft.type,
    format: draft.type === "string" ? orUndefined(draft.format) : undefined,
    spec: draft.type === "string" || draft.type === "object" ? orUndefined(draft.spec) : undefined,
    calculation: draft.calculation.trim(),
    colorized: draft.colorized ? true : undefined,
    colors: safeJsonParse(draft.colorsJson),
  } as CalculationPropertyDefinitionWire;
}

function aggregationDefinitionFor(draft: AggregationDraft): AggregationPropertyDefinitionWire {
  return {
    title: draft.title.trim(),
    target: draft.target.trim(),
    calculationSpec: {
      calculationBy: draft.calculationBy,
      func: draft.func,
      property: draft.calculationBy === "property" ? orUndefined(draft.property) : undefined,
      averageOf: draft.func === "average" ? orUndefined(draft.averageOf) : undefined,
      measureTimeBy: draft.func === "average" ? orUndefined(draft.measureTimeBy) : undefined,
    },
    query: safeJsonParse(draft.queryJson),
    pathFilter: safeJsonParse(draft.pathFilterJson),
  } as AggregationPropertyDefinitionWire;
}

function ownershipFor(values: BlueprintFormValues): OwnershipWire | undefined {
  if (!values.ownershipType) return undefined;
  return {
    type: values.ownershipType,
    title: orUndefined(values.ownershipTitle),
    path: values.ownershipType === "Inherited" ? orUndefined(values.ownershipPath) : undefined,
  } as OwnershipWire;
}

/** Form values -> the Port-shaped wire request; blank-id rows are dropped (the submit path
 *  never sends a half-typed row — the field validators block submit on a blank REQUIRED id
 *  before this ever runs, but the preview calls this on every keystroke). */
export function toBlueprintRequest(values: BlueprintFormValues): BlueprintBody {
  const properties: Record<string, PropertyDefinitionWire> = {};
  const required: string[] = [];
  for (const draft of values.properties) {
    const id = draft.id.trim();
    if (!id) continue;
    properties[id] = propertyDefinitionFor(draft);
    if (draft.required) required.push(id);
  }
  const relations: Record<string, RelationDefinitionWire> = {};
  for (const draft of values.relations) {
    const id = draft.id.trim();
    if (id) relations[id] = relationDefinitionFor(draft);
  }
  const mirrorProperties: Record<string, MirrorPropertyDefinitionWire> = {};
  for (const draft of values.mirrorProperties) {
    const id = draft.id.trim();
    if (id) mirrorProperties[id] = mirrorDefinitionFor(draft);
  }
  const calculationProperties: Record<string, CalculationPropertyDefinitionWire> = {};
  for (const draft of values.calculationProperties) {
    const id = draft.id.trim();
    if (id) calculationProperties[id] = calculationDefinitionFor(draft);
  }
  const aggregationProperties: Record<string, AggregationPropertyDefinitionWire> = {};
  for (const draft of values.aggregationProperties) {
    const id = draft.id.trim();
    if (id) aggregationProperties[id] = aggregationDefinitionFor(draft);
  }
  return {
    identifier: values.identifier.trim(),
    title: values.title.trim(),
    description: orUndefined(values.description),
    icon: orUndefined(values.icon),
    schema: { properties, required },
    relations,
    mirrorProperties,
    calculationProperties,
    aggregationProperties,
    ownership: ownershipFor(values),
  } as BlueprintBody;
}

// -- Wire -> draft (fromBlueprintResponse) ------------------------------------------------

function enumValuesFrom(def: PropertyDefinitionWire): string[] {
  return (def.enum ?? []).map((v) => String(v));
}

function enumColorsFrom(def: PropertyDefinitionWire): Record<string, string> {
  return { ...(def.enumColors ?? {}) };
}

function defaultTextFrom(def: PropertyDefinitionWire, type: PropertyType): string {
  if (type === "object") return def.default !== undefined ? JSON.stringify(def.default, null, 2) : "";
  if (type === "number") return typeof def.default === "number" ? String(def.default) : "";
  if (type === "string") return typeof def.default === "string" ? def.default : "";
  return "";
}

function defaultBoolFrom(def: PropertyDefinitionWire): "" | "true" | "false" {
  return typeof def.default === "boolean" ? (def.default ? "true" : "false") : "";
}

function defaultListFrom(def: PropertyDefinitionWire): string[] {
  return Array.isArray(def.default) ? (def.default as unknown[]).map((v) => String(v)) : [];
}

function objectSchemaJsonFrom(def: PropertyDefinitionWire): string {
  const schema: ObjectSchemaJson = {};
  if (def.properties !== undefined) schema.properties = def.properties;
  if (def.patternProperties !== undefined) schema.patternProperties = def.patternProperties;
  if (def.additionalProperties !== undefined) schema.additionalProperties = def.additionalProperties;
  return Object.keys(schema).length > 0 ? JSON.stringify(schema, null, 2) : "";
}

function asPropertyType(value: string): PropertyType {
  return (PROPERTY_TYPES as readonly string[]).includes(value) ? (value as PropertyType) : "string";
}

function propertyDraftFrom(id: string, def: PropertyDefinitionWire, required: Set<string>): PropertyDraft {
  const type = asPropertyType(def.type);
  return {
    key: newDraftKey("prop"),
    id,
    type,
    title: def.title ?? "",
    description: def.description ?? "",
    icon: def.icon ?? "",
    required: required.has(id),
    format: type === "string" ? (def.format ?? "") : "",
    dateFormat: def.date_format ?? "",
    pattern: def.pattern ?? "",
    minLength: numberToText(def.minLength),
    maxLength: numberToText(def.maxLength),
    spec: type === "string" ? (def.spec ?? "") : "",
    specAuthorizationUrl: def.specAuthentication?.authorizationUrl ?? "",
    specTokenUrl: def.specAuthentication?.tokenUrl ?? "",
    specClientId: def.specAuthentication?.clientId ?? "",
    specAuthorizationScope: [...(def.specAuthentication?.authorizationScope ?? [])],
    minimum: numberToText(def.minimum),
    maximum: numberToText(def.maximum),
    exclusiveMinimum: numberToText(def.exclusiveMinimum),
    exclusiveMaximum: numberToText(def.exclusiveMaximum),
    enumValues: enumValuesFrom(def),
    enumColors: enumColorsFrom(def),
    itemsType: def.items?.type ?? "",
    itemsFormat: def.items?.format ?? "",
    minItems: numberToText(def.minItems),
    maxItems: numberToText(def.maxItems),
    uniqueItems: def.uniqueItems ?? false,
    objectFormat: type === "object" ? (def.format ?? "") : "",
    objectSpec: type === "object" ? (def.spec ?? "") : "",
    objectSchemaJson: objectSchemaJsonFrom(def),
    defaultText: defaultTextFrom(def, type),
    defaultBool: defaultBoolFrom(def),
    defaultList: defaultListFrom(def),
  };
}

function relationDraftFrom(id: string, def: RelationDefinitionWire): RelationDraft {
  return {
    key: newDraftKey("rel"),
    id,
    title: def.title,
    description: def.description ?? "",
    target: def.target,
    required: def.required,
    many: def.many,
  };
}

function mirrorDraftFrom(id: string, def: MirrorPropertyDefinitionWire): MirrorDraft {
  return { key: newDraftKey("mirror"), id, title: def.title, path: def.path };
}

function calculationDraftFrom(id: string, def: CalculationPropertyDefinitionWire): CalculationDraft {
  const type = asPropertyType(def.type);
  return {
    key: newDraftKey("calc"),
    id,
    title: def.title,
    type,
    format: def.format ?? "",
    spec: def.spec ?? "",
    calculation: def.calculation,
    colorized: def.colorized ?? false,
    colorsJson: def.colors && Object.keys(def.colors).length > 0 ? JSON.stringify(def.colors, null, 2) : "",
  };
}

function aggregationDraftFrom(id: string, def: AggregationPropertyDefinitionWire): AggregationDraft {
  return {
    key: newDraftKey("agg"),
    id,
    title: def.title,
    target: def.target,
    calculationBy: def.calculationSpec.calculationBy,
    func: def.calculationSpec.func,
    property: def.calculationSpec.property ?? "",
    averageOf: def.calculationSpec.averageOf ?? "",
    measureTimeBy: def.calculationSpec.measureTimeBy ?? "",
    queryJson: def.query ? JSON.stringify(def.query, null, 2) : "",
    pathFilterJson: def.pathFilter && def.pathFilter.length > 0 ? JSON.stringify(def.pathFilter, null, 2) : "",
  };
}

/** Wire shape -> form values (the edit page's prefill). */
export function fromBlueprintResponse(blueprint: Blueprint): BlueprintFormValues {
  const required = new Set(blueprint.schema.required ?? []);
  return {
    identifier: blueprint.identifier,
    title: blueprint.title,
    description: blueprint.description ?? "",
    icon: blueprint.icon ?? "",
    properties: Object.entries(blueprint.schema.properties ?? {}).map(([id, def]) =>
      propertyDraftFrom(id, def, required),
    ),
    relations: Object.entries(blueprint.relations ?? {}).map(([id, def]) => relationDraftFrom(id, def)),
    mirrorProperties: Object.entries(blueprint.mirrorProperties ?? {}).map(([id, def]) =>
      mirrorDraftFrom(id, def),
    ),
    calculationProperties: Object.entries(blueprint.calculationProperties ?? {}).map(([id, def]) =>
      calculationDraftFrom(id, def),
    ),
    aggregationProperties: Object.entries(blueprint.aggregationProperties ?? {}).map(([id, def]) =>
      aggregationDraftFrom(id, def),
    ),
    ownershipType: blueprint.ownership?.type ?? "",
    ownershipTitle: blueprint.ownership?.title ?? "",
    ownershipPath: blueprint.ownership?.path ?? "",
  };
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

/** Validation rules for the create/edit page (mirrors the server's checks). */
export function blueprintFormValidation(t: TFunction) {
  return {
    identifier: (value: string) => (isValidBlueprintIdentifier(value.trim()) ? null : t("blueprints.validation.identifier")),
    title: (value: string) => {
      const v = value.trim();
      return v && v.length <= MAX_TITLE_LENGTH ? null : t("blueprints.validation.title");
    },
    description: (value: string) =>
      value.length <= MAX_DESCRIPTION_LENGTH ? null : t("blueprints.validation.descriptionLength"),
    icon: (value: string) => (value.length <= MAX_ICON_LENGTH ? null : t("blueprints.validation.iconLength")),
    properties: {
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
    },
    relations: {
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
    },
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
    ownershipPath: (value: string, values: BlueprintFormValues) =>
      values.ownershipType === "Inherited"
        ? pathRule(value, new Set(values.relations.map((r) => r.id.trim())), t)
        : null,
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
