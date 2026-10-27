import type { Blueprint, BlueprintBody } from "../api/blueprints";
import {
  PROPERTY_TYPES,
  newDraftKey,
  singleRelationIds,
  type AggregationDraft,
  type BlueprintFormValues,
  type CalculationDraft,
  type MirrorDraft,
  type PropertyDraft,
  type PropertyType,
  type RelationDraft,
} from "./blueprintFormModel";

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

/** The Toadie-only `tiers` object: tiers live on the rows, so a row with a blank id (dropped
 *  above) contributes nothing; the whole field is omitted once no tier is set (the server's
 *  "empty means absent" rule). Computed rows carry no tier. */
function tiersFor(values: BlueprintFormValues): BlueprintBody["tiers"] {
  const rowTiers = (rows: { id: string; tier: number | null }[]): Record<string, number> => {
    const out: Record<string, number> = {};
    for (const row of rows) {
      const id = row.id.trim();
      if (id && row.tier != null) out[id] = row.tier;
    }
    return out;
  };
  const properties = rowTiers(values.properties);
  const relations = rowTiers(values.relations);
  if (values.tier == null && Object.keys(properties).length === 0 && Object.keys(relations).length === 0) {
    return undefined;
  }
  return { blueprint: values.tier ?? undefined, properties, relations };
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
  // Derive, don't clear, PER ENTRY: a hierarchy's value is emitted only while it STILL names a
  // current single relation, so flipping that relation to `many` or removing it silently drops
  // just this entry from the request without any effect keeping the two in sync (each Select
  // in `HierarchyFieldset` guards its own displayed value the same way). Whether the KEY still
  // names an active `hierarchies` dictionary entry is NOT checked here — an entry the admin
  // hasn't cleared still reaches the wire so the server's own 400 surfaces the problem instead
  // of the client silently making it disappear. The whole field is omitted (never an empty
  // object) once no entry survives.
  const singleRelations = new Set(singleRelationIds(values.relations));
  const hierarchyRelationEntries = Object.entries(values.hierarchyRelations)
    .map(([hierarchyId, relationId]) => [hierarchyId, relationId.trim()] as const)
    .filter(([, relationId]) => singleRelations.has(relationId));
  const hierarchyRelations =
    hierarchyRelationEntries.length > 0 ? Object.fromEntries(hierarchyRelationEntries) : undefined;
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
    hierarchyRelations,
    tiers: tiersFor(values),
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

function propertyDraftFrom(
  id: string,
  def: PropertyDefinitionWire,
  required: Set<string>,
  tier: number | null,
): PropertyDraft {
  const type = asPropertyType(def.type);
  return {
    key: newDraftKey("prop"),
    id,
    tier,
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

function relationDraftFrom(id: string, def: RelationDefinitionWire, tier: number | null): RelationDraft {
  return {
    key: newDraftKey("rel"),
    id,
    title: def.title,
    description: def.description ?? "",
    target: def.target,
    required: def.required,
    many: def.many,
    tier,
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
      propertyDraftFrom(id, def, required, blueprint.tiers?.properties?.[id] ?? null),
    ),
    relations: Object.entries(blueprint.relations ?? {}).map(([id, def]) =>
      relationDraftFrom(id, def, blueprint.tiers?.relations?.[id] ?? null),
    ),
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
    hierarchyRelations: { ...(blueprint.hierarchyRelations ?? {}) },
    tier: blueprint.tiers?.blueprint ?? null,
    sourceUrl: blueprint.sourceUrl ?? "",
  };
}
