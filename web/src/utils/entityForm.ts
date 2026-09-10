import type { TFunction } from "i18next";
import type { Blueprint } from "../api/blueprints";
import type { Entity, EntityBody } from "../api/entities";
import { safeJsonParse } from "./blueprintForm";
import { saveErrorMessage } from "./saveError";

// Wire shapes, derived structurally from the generated contract (the blueprintForm.ts idiom)
// rather than hand-typed.
export type PropertyDefinitionWire = Blueprint["schema"]["properties"][string];
export type RelationDefinitionWire = Blueprint["relations"][string];

/** `team`'s wire shape — a free string or string array, exactly as sent/stored. */
type TeamWire = string | string[] | undefined;

// Port's entity rules (.claude/docs/port-data-model.md's "Entities" section) mirrored
// client-side — grammar only, the server is the gate.
export const MAX_IDENTIFIER_LENGTH = 200;
export const MAX_TITLE_LENGTH = 200;
export const MAX_ICON_LENGTH = 100;
const MAX_TEAM_ENTRIES = 50;
const MAX_TEAM_ENTRY_LENGTH = 100;

// Wider than the blueprint identifier charset: unicode letters, `+`, `'`, `\` — Port's stated
// pattern, never exactly "." or "..".
const IDENTIFIER_RE = /^(?!\.{1,2}$)[\p{L}0-9@_.+:\\/='-]+$/u;

export function isValidEntityIdentifier(value: string): boolean {
  return value.length >= 1 && value.length <= MAX_IDENTIFIER_LENGTH && IDENTIFIER_RE.test(value);
}

// -- Drafts: INDEX-addressed arrays, never id-keyed records — property/relation ids may
// contain `.`, which Mantine form paths split on (the plan's load-bearing rule). Unlike the
// blueprint editor's rows, these lists are FIXED size: one draft per schema property/relation
// (plus any stored keys the schema no longer declares) — an entity cannot invent new property
// or relation ids, so there is no add/remove/reorder here.

export type PropertyValueDraft = {
  id: string;
  /** string/number/enum widgets. */
  text: string;
  /** boolean tri-state: "" = unset. */
  bool: "" | "true" | "false";
  /** array of string/number/enum-item widgets (TagsInput/MultiSelect). */
  list: string[];
  /** object widgets (JSON Textarea, incl. labeled-url) and array-of-object widgets. */
  json: string;
  /** True when this property id is no longer declared by the blueprint's schema (an extra
   *  stored key from before a blueprint edit) — rendered as a raw JSON row, coerced by no
   *  type we don't know, so the server's 400 can still name it. */
  unknown: boolean;
};

export type RelationValueDraft = {
  id: string;
  /** `many: false` widget (Select). */
  single: string;
  /** `many: true` widget (MultiSelect). */
  many: string[];
};

export type EntityFormValues = {
  /** The blueprint identifier — fixed for the lifetime of the form (create seeds it from the
   *  `?blueprint=` param, edit seeds it from the stored entity; the field itself is read-only
   *  in the UI, PUT 400s a change per the server's rule). */
  blueprint: string;
  identifier: string;
  title: string;
  icon: string;
  team: string[];
  properties: PropertyValueDraft[];
  relations: RelationValueDraft[];
};

function emptyPropertyValueDraft(id: string): PropertyValueDraft {
  return { id, text: "", bool: "", list: [], json: "", unknown: false };
}

function emptyRelationValueDraft(id: string): RelationValueDraft {
  return { id, single: "", many: [] };
}

// -- Draft <- schema/value (emptyEntityForm / fromEntityResponse) ------------------------

function valueToDraft(id: string, def: PropertyDefinitionWire, value: unknown): PropertyValueDraft {
  const base = emptyPropertyValueDraft(id);
  switch (def.type) {
    case "string":
      return { ...base, text: typeof value === "string" ? value : "" };
    case "number":
      return { ...base, text: typeof value === "number" && Number.isFinite(value) ? String(value) : "" };
    case "boolean":
      return { ...base, bool: typeof value === "boolean" ? (value ? "true" : "false") : "" };
    case "array":
      if (def.items?.type === "object") {
        return { ...base, json: value !== undefined ? JSON.stringify(value, null, 2) : "" };
      }
      return { ...base, list: Array.isArray(value) ? value.map((v) => String(v)) : [] };
    case "object":
      return { ...base, json: value !== undefined ? JSON.stringify(value, null, 2) : "" };
    default:
      return base;
  }
}

function unknownPropertyDraft(id: string, value: unknown): PropertyValueDraft {
  return { id, text: "", bool: "", list: [], json: JSON.stringify(value, null, 2), unknown: true };
}

function relationValueToDraft(def: RelationDefinitionWire, id: string, value: unknown): RelationValueDraft {
  if (def.many) {
    return { id, single: "", many: Array.isArray(value) ? value.map((v) => String(v)) : [] };
  }
  return { id, single: typeof value === "string" ? value : "", many: [] };
}

/** A fresh entity form for a blueprint: one draft per schema property/relation, in schema
 *  order, defaults prefilled (Port entities start from the blueprint's declared defaults). */
export function emptyEntityForm(blueprint: Blueprint): EntityFormValues {
  return {
    blueprint: blueprint.identifier,
    identifier: "",
    title: "",
    icon: "",
    team: [],
    properties: Object.entries(blueprint.schema.properties).map(([id, def]) =>
      valueToDraft(id, def, def.default),
    ),
    relations: Object.entries(blueprint.relations).map(([id]) => emptyRelationValueDraft(id)),
  };
}

/** `team`'s wire shape (a scalar, an array, or absent) -> the form's array-only draft — also
 *  used directly by `EntityTeamField` to normalize the STORED/EFFECTIVE `team` an entity
 *  response carries into the pills it renders. */
export function teamValuesOf(team: TeamWire): string[] {
  if (team === undefined) return [];
  return Array.isArray(team) ? [...team] : [team];
}

/** Wire entity -> form values (the edit page's prefill): one draft per schema
 *  property/relation seeded from the STORED value (blank when unset — never the schema
 *  default, which only applies to a brand-new entity), plus one extra JSON draft per stored
 *  property key the blueprint no longer declares (`unknown: true`) so the server's 400 can
 *  still name it. */
export function fromEntityResponse(entity: Entity, blueprint: Blueprint): EntityFormValues {
  const schemaIds = Object.keys(blueprint.schema.properties);
  const storedProperties = (entity.properties ?? {}) as Record<string, unknown>;
  const extraPropertyIds = Object.keys(storedProperties).filter((id) => !schemaIds.includes(id));
  const properties = [
    ...schemaIds.map((id) => valueToDraft(id, blueprint.schema.properties[id], storedProperties[id])),
    ...extraPropertyIds.map((id) => unknownPropertyDraft(id, storedProperties[id])),
  ];

  const relationIds = Object.keys(blueprint.relations);
  const storedRelations = (entity.relations ?? {}) as Record<string, unknown>;
  const relations = relationIds.map((id) =>
    relationValueToDraft(blueprint.relations[id], id, storedRelations[id]),
  );

  return {
    blueprint: entity.blueprint,
    identifier: entity.identifier,
    title: entity.title,
    icon: entity.icon ?? "",
    team: teamValuesOf(entity.team as TeamWire),
    properties,
    relations,
  };
}

// -- Draft -> wire (toEntityRequest) ------------------------------------------------------

function coerceListItems(list: string[], itemsType: string | undefined): unknown[] {
  if (itemsType === "number") return list.map(Number);
  if (itemsType === "boolean") return list.map((v) => v === "true");
  return [...list];
}

/** Blank-is-absent: blank text / "" bool / empty list / blank JSON -> the key is OMITTED,
 *  never sent as an explicit null (the plan's rule — a non-required blank field must not
 *  look like an intentional clear). */
function propertyValueToWire(draft: PropertyValueDraft, def: PropertyDefinitionWire | undefined): unknown {
  if (draft.unknown) return safeJsonParse(draft.json);
  if (!def) return undefined;
  switch (def.type) {
    case "string": {
      const v = draft.text.trim();
      return v === "" ? undefined : v;
    }
    case "number": {
      const v = draft.text.trim();
      if (v === "") return undefined;
      const n = Number(v);
      return Number.isFinite(n) ? n : undefined;
    }
    case "boolean":
      return draft.bool === "" ? undefined : draft.bool === "true";
    case "array":
      if (def.items?.type === "object") return safeJsonParse(draft.json);
      return draft.list.length === 0 ? undefined : coerceListItems(draft.list, def.items?.type);
    case "object":
      return safeJsonParse(draft.json);
    default:
      return undefined;
  }
}

function relationValueToWire(def: RelationDefinitionWire, draft: RelationValueDraft): unknown {
  if (def.many) return draft.many.length > 0 ? [...draft.many] : undefined;
  const v = draft.single.trim();
  return v === "" ? undefined : v;
}

function teamToWire(team: string[]): TeamWire {
  const trimmed = team.map((t) => t.trim()).filter((t) => t !== "");
  return trimmed.length > 0 ? trimmed : undefined;
}

/** Form values -> the wire create/replace body. `blueprint` here is the FULL resolved
 *  blueprint (its schema/relations drive coercion) — distinct from `values.blueprint`, the
 *  identifier string the request actually carries.
 *
 *  Phase 4 ownership: an Inherited-ownership blueprint computes `team` from a related
 *  blueprint's Direct ownership at READ time (`entities/EntityOwnership.kt`) and rejects a
 *  supplied one (`TEAM_NOT_ALLOWED`) — `team` is therefore always OMITTED here, never sent as
 *  an explicit empty value, regardless of what the (non-editable) form field happens to hold. */
export function toEntityRequest(values: EntityFormValues, blueprint: Blueprint): EntityBody {
  const properties: Record<string, unknown> = {};
  for (const draft of values.properties) {
    const id = draft.id.trim();
    if (!id) continue;
    const value = propertyValueToWire(draft, blueprint.schema.properties[id]);
    if (value !== undefined) properties[id] = value;
  }
  const relations: Record<string, unknown> = {};
  for (const draft of values.relations) {
    const id = draft.id.trim();
    const def = blueprint.relations[id];
    if (!id || !def) continue;
    const value = relationValueToWire(def, draft);
    if (value !== undefined) relations[id] = value;
  }
  const team = blueprint.ownership?.type === "Inherited" ? undefined : teamToWire(values.team);
  return {
    blueprint: values.blueprint.trim(),
    identifier: values.identifier.trim(),
    title: values.title.trim(),
    icon: values.icon.trim() || undefined,
    team,
    properties,
    relations,
  } as EntityBody;
}

// -- Validation (mirrors the server's entityFindings rule table) -------------------------

function rowIndex(path: string): number {
  return Number(path.split(".")[1]);
}

function isAbsoluteUrl(value: string): boolean {
  try {
    return Boolean(new URL(value).protocol);
  } catch {
    return false;
  }
}

function isValidEmailish(value: string): boolean {
  const at = value.lastIndexOf("@");
  return at > 0 && at < value.length - 1;
}

// Mirrors the server's `date-time` rule (`OffsetDateTime.parse`): an explicit offset is
// required — `Z` or `±HH:MM` — seconds/fraction optional.
const DATE_TIME_OFFSET_RE = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:\d{2})$/;

// Mirrors the server's `timer` rule (`Instant.parse`): the literal `Z` designator only, no
// arbitrary offset.
const TIMER_ZULU_RE = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?Z$/;

function isValidDateTimeOffset(value: string): boolean {
  return DATE_TIME_OFFSET_RE.test(value) && !Number.isNaN(Date.parse(value));
}

function isValidTimerZulu(value: string): boolean {
  return TIMER_ZULU_RE.test(value) && !Number.isNaN(Date.parse(value));
}

function isValidIpv4(value: string): boolean {
  const parts = value.split(".");
  if (parts.length !== 4) return false;
  return parts.every((p) => {
    if (!/^\d{1,3}$/.test(p)) return false;
    const n = Number(p);
    return n >= 0 && n <= 255 && String(n) === p;
  });
}

/** JSON-Schema `pattern` semantics: unanchored `test`, invalid patterns never block the
 *  client (guarded — the server is the pattern's real gate). */
function matchesPattern(pattern: string, value: string): boolean {
  try {
    return new RegExp(pattern, "u").test(value);
  } catch {
    return true;
  }
}

function stringRule(t: TFunction, def: PropertyDefinitionWire, value: string): string | null {
  const length = [...value].length;
  if (def.minLength !== undefined && length < def.minLength) return t("entities.validation.minLength", { min: def.minLength });
  if (def.maxLength !== undefined && length > def.maxLength) return t("entities.validation.maxLength", { max: def.maxLength });
  if (def.pattern && !matchesPattern(def.pattern, value)) return t("entities.validation.pattern");
  if (def.enum && def.enum.length > 0 && !def.enum.map(String).includes(value)) return t("entities.validation.enum");
  if (def.format === "url" && !isAbsoluteUrl(value)) return t("entities.validation.url");
  if ((def.format === "email" || def.format === "idn-email") && !isValidEmailish(value)) return t("entities.validation.email");
  if (def.format === "date-time" && !isValidDateTimeOffset(value)) return t("entities.validation.dateTimeOffset");
  if (def.format === "timer" && !isValidTimerZulu(value)) return t("entities.validation.timerZulu");
  if (def.format === "ipv4" && !isValidIpv4(value)) return t("entities.validation.ipv4");
  return null;
}

function numberRule(t: TFunction, def: PropertyDefinitionWire, value: number): string | null {
  if (def.enum && def.enum.length > 0 && !def.enum.map(Number).includes(value)) return t("entities.validation.enum");
  if (def.minimum !== undefined && value < def.minimum) return t("entities.validation.minimum", { min: def.minimum });
  if (def.maximum !== undefined && value > def.maximum) return t("entities.validation.maximum", { max: def.maximum });
  if (def.exclusiveMinimum !== undefined && value <= def.exclusiveMinimum) {
    return t("entities.validation.exclusiveMinimum", { min: def.exclusiveMinimum });
  }
  if (def.exclusiveMaximum !== undefined && value >= def.exclusiveMaximum) {
    return t("entities.validation.exclusiveMaximum", { max: def.exclusiveMaximum });
  }
  return null;
}

function arrayRule(t: TFunction, def: PropertyDefinitionWire, value: unknown[]): string | null {
  if (def.minItems !== undefined && value.length < def.minItems) return t("entities.validation.minItems", { min: def.minItems });
  if (def.maxItems !== undefined && value.length > def.maxItems) return t("entities.validation.maxItems", { max: def.maxItems });
  if (def.uniqueItems) {
    const serialized = value.map((v) => JSON.stringify(v));
    if (new Set(serialized).size !== serialized.length) return t("entities.validation.uniqueItems");
  }
  if (def.items?.enum && def.items.enum.length > 0) {
    const allowed = def.items.enum.map(String);
    if (value.some((v) => !allowed.includes(String(v)))) return t("entities.validation.enum");
  }
  return null;
}

function objectRule(t: TFunction, def: PropertyDefinitionWire, value: unknown): string | null {
  if (def.format !== "labeled-url") return null; // else verbatim, no JSON-Schema evaluation
  if (typeof value !== "object" || value === null || Array.isArray(value)) return t("entities.validation.objectShape");
  const obj = value as Record<string, unknown>;
  const allowed = new Set(["url", "displayText"]);
  if (Object.keys(obj).some((k) => !allowed.has(k))) return t("entities.validation.objectShape");
  if (typeof obj.url !== "string" || !isAbsoluteUrl(obj.url)) return t("entities.validation.url");
  if (obj.displayText !== undefined && typeof obj.displayText !== "string") return t("entities.validation.objectShape");
  return null;
}

function jsonSyntaxError(value: string, t: TFunction): string | null {
  if (!value.trim()) return null;
  return safeJsonParse(value) === undefined ? t("entities.validation.jsonInvalid") : null;
}

function isRequired(blueprint: Blueprint, id: string): boolean {
  return blueprint.schema.required.includes(id);
}

function textFieldError(t: TFunction, blueprint: Blueprint, values: EntityFormValues, path: string): string | null {
  const draft = values.properties[rowIndex(path)];
  if (!draft || draft.unknown) return null;
  const def = blueprint.schema.properties[draft.id];
  if (!def || (def.type !== "string" && def.type !== "number")) return null;
  const trimmed = draft.text.trim();
  if (trimmed === "") return isRequired(blueprint, draft.id) ? t("entities.validation.required") : null;
  if (def.type === "number") {
    const n = Number(trimmed);
    if (!Number.isFinite(n)) return t("entities.validation.numberInvalid");
    return numberRule(t, def, n);
  }
  return stringRule(t, def, trimmed);
}

function listFieldError(t: TFunction, blueprint: Blueprint, values: EntityFormValues, path: string): string | null {
  const draft = values.properties[rowIndex(path)];
  if (!draft || draft.unknown) return null;
  const def = blueprint.schema.properties[draft.id];
  if (!def || def.type !== "array" || def.items?.type === "object") return null;
  if (draft.list.length === 0) return isRequired(blueprint, draft.id) ? t("entities.validation.required") : null;
  if (def.items?.type === "number" && draft.list.some((v) => !Number.isFinite(Number(v)))) {
    return t("entities.validation.numberInvalid");
  }
  if (def.items?.type === "boolean" && draft.list.some((v) => v !== "true" && v !== "false")) {
    return t("entities.validation.enum");
  }
  return arrayRule(t, def, coerceListItems(draft.list, def.items?.type));
}

function jsonFieldError(t: TFunction, blueprint: Blueprint, values: EntityFormValues, path: string): string | null {
  const draft = values.properties[rowIndex(path)];
  if (!draft) return null;
  if (draft.unknown) return jsonSyntaxError(draft.json, t);
  const def = blueprint.schema.properties[draft.id];
  if (!def) return null;
  const usesJson = def.type === "object" || (def.type === "array" && def.items?.type === "object");
  if (!usesJson) return null;
  const trimmed = draft.json.trim();
  if (trimmed === "") return isRequired(blueprint, draft.id) ? t("entities.validation.required") : null;
  const syntax = jsonSyntaxError(draft.json, t);
  if (syntax) return syntax;
  const parsed = safeJsonParse(draft.json);
  return def.type === "array" ? arrayRule(t, def, Array.isArray(parsed) ? parsed : []) : objectRule(t, def, parsed);
}

function relationFieldError(
  t: TFunction,
  blueprint: Blueprint,
  field: "single" | "many",
  values: EntityFormValues,
  path: string,
): string | null {
  const draft = values.relations[rowIndex(path)];
  if (!draft) return null;
  const def = blueprint.relations[draft.id];
  if (!def) return null;
  if (def.many && field === "many") {
    return def.required && draft.many.length === 0 ? t("entities.validation.required") : null;
  }
  if (!def.many && field === "single") {
    return def.required && draft.single.trim() === "" ? t("entities.validation.required") : null;
  }
  return null;
}

/** Validation rules for the create/edit page — grammar and the property/relation table only
 *  (mirrors the server's `entityFindings`); target resolution is left to the server, same as
 *  every other reference field in this app. */
export function entityFormValidation(t: TFunction, blueprint: Blueprint) {
  return {
    identifier: (value: string) => (isValidEntityIdentifier(value.trim()) ? null : t("entities.validation.identifier")),
    title: (value: string) => {
      const v = value.trim();
      return v && v.length <= MAX_TITLE_LENGTH ? null : t("entities.validation.title");
    },
    icon: (value: string) => (value.length <= MAX_ICON_LENGTH ? null : t("entities.validation.iconLength")),
    team: (value: string[]) => {
      if (value.length > MAX_TEAM_ENTRIES) return t("entities.validation.teamCount");
      return value.some((v) => v.length > MAX_TEAM_ENTRY_LENGTH) ? t("entities.validation.teamEntryLength") : null;
    },
    properties: {
      text: (_value: string, values: EntityFormValues, path: string) => textFieldError(t, blueprint, values, path),
      list: (_value: string[], values: EntityFormValues, path: string) => listFieldError(t, blueprint, values, path),
      json: (_value: string, values: EntityFormValues, path: string) => jsonFieldError(t, blueprint, values, path),
    },
    relations: {
      single: (_value: string, values: EntityFormValues, path: string) =>
        relationFieldError(t, blueprint, "single", values, path),
      many: (_value: string[], values: EntityFormValues, path: string) =>
        relationFieldError(t, blueprint, "many", values, path),
    },
  };
}

/** The entity save's fixed error vocabulary. */
export function entitySaveErrorMessage(err: unknown, t: TFunction): string {
  return saveErrorMessage(err, t, {
    conflict: "entities.saveConflict",
    invalid: "entities.saveInvalid",
    notFound: "entities.saveGone",
    failedStatus: "common.error.actionFailedStatus",
    failed: "common.error.actionFailed",
  });
}

/** The entity delete's fixed error vocabulary (409 = still targeted by another entity's
 *  relation — the problem detail names the referrers). */
export function entityDeleteErrorMessage(err: unknown, t: TFunction): string {
  return saveErrorMessage(err, t, {
    conflict: "entities.deleteConflict",
    failedStatus: "common.error.actionFailedStatus",
    failed: "common.error.actionFailed",
  });
}
