import type { Blueprint } from "../api/blueprints";
import type { QueryCompletionSchema } from "./queryCompletion";
import { quoteIfNeeded, stringLiteral } from "./queryLanguage";

export const QUERY_BUILDER_MAX_CONDITIONS = 8;

export type QueryBuilderOperator =
  | "eq"
  | "neq"
  | "lt"
  | "lte"
  | "gt"
  | "gte"
  | "contains"
  | "startsWith"
  | "endsWith"
  | "isNull"
  | "isNotNull";

export type QueryBuilderCondition = {
  property: string;
  operator: QueryBuilderOperator;
  value: string;
};

export type QueryBuilderConnection = {
  kind: "relation" | "ownership" | "hierarchy";
  name: string;
  direction: "out" | "in";
  targetBlueprint: string;
  targetIdentifier: string;
  maxHops: string;
  optional: boolean;
};

export type QueryBuilderModel = {
  blueprint: string;
  conditions: QueryBuilderCondition[];
  connection: QueryBuilderConnection | null;
  returns: "start" | "target" | "both";
  limit: string;
};

export type QueryBuilderProperty = {
  id: string;
  title: string;
  source: "stored" | "meta";
  type: "string" | "number" | "boolean";
  enumValues: (string | number)[];
};

export type QueryBuilderIssueCode =
  | "blueprintRequired"
  | "unknownBlueprint"
  | "tooManyConditions"
  | "propertyRequired"
  | "unknownProperty"
  | "operatorUnsupported"
  | "valueRequired"
  | "valueInvalid"
  | "connectionNameRequired"
  | "connectionUnsupported"
  | "directionUnsupported"
  | "targetBlueprintUnknown"
  | "targetIdentifierRequiresBlueprint"
  | "maxHopsInvalid"
  | "returnUnsupported"
  | "limitInvalid"
  | "queryTooLong"
  | "unsupportedName";

type QueryBuilderIssue = { path: string; code: QueryBuilderIssueCode };

export type QueryBuilderResult = { query: string; errors: QueryBuilderIssue[] };

const META_PROPERTIES: QueryBuilderProperty[] = [
  { id: "$identifier", title: "$identifier", source: "meta", type: "string", enumValues: [] },
  { id: "$title", title: "$title", source: "meta", type: "string", enumValues: [] },
];

const STRING_OPERATORS: QueryBuilderOperator[] = [
  "eq",
  "neq",
  "contains",
  "startsWith",
  "endsWith",
  "isNull",
  "isNotNull",
];
const NUMBER_OPERATORS: QueryBuilderOperator[] = ["eq", "neq", "lt", "lte", "gt", "gte", "isNull", "isNotNull"];
const BOOLEAN_OPERATORS: QueryBuilderOperator[] = ["eq", "neq", "isNull", "isNotNull"];

const OPERATOR_TEXT: Record<QueryBuilderOperator, string> = {
  eq: "=",
  neq: "<>",
  lt: "<",
  lte: "<=",
  gt: ">",
  gte: ">=",
  contains: "CONTAINS",
  startsWith: "STARTS WITH",
  endsWith: "ENDS WITH",
  isNull: "IS NULL",
  isNotNull: "IS NOT NULL",
};

const INTEGER_RE = /^\d+$/;
const NUMBER_RE = /^-?\d+(?:\.\d+)?$/;
const MAX_QUERY_LENGTH = 2000;
const MAX_LIMIT = 10_000;
const MAX_HOPS = 10;
// The backend parses a leading minus separately, but first requires its unsigned magnitude to
// fit in Long. Consequently Long.MIN_VALUE itself cannot be represented by the query grammar.
const QUERY_INTEGER_MIN = -(2n ** 63n - 1n);
const LONG_MAX = 2n ** 63n - 1n;

export function createQueryBuilderModel(): QueryBuilderModel {
  return { blueprint: "", conditions: [], connection: null, returns: "start", limit: "" };
}

function findBlueprint(schema: QueryCompletionSchema, identifier: string): Blueprint | undefined {
  const folded = identifier.toLowerCase();
  return schema.blueprints.find((blueprint) => blueprint.identifier.toLowerCase() === folded);
}

export function getQueryBuilderProperties(
  schema: QueryCompletionSchema,
  blueprintIdentifier: string,
): QueryBuilderProperty[] {
  const blueprint = findBlueprint(schema, blueprintIdentifier);
  if (!blueprint) return [];
  const stored = Object.entries(blueprint.schema.properties).flatMap(([id, property]) => {
    if (property.type !== "string" && property.type !== "number" && property.type !== "boolean") return [];
    return [{
      id,
      title: property.title ?? id,
      source: "stored" as const,
      type: property.type,
      enumValues: property.enum ? [...property.enum] : [],
    }];
  });
  return [...stored, ...META_PROPERTIES.map((property) => ({ ...property, enumValues: [] }))];
}

export function getQueryBuilderOperators(property: QueryBuilderProperty): QueryBuilderOperator[] {
  if (property.type === "number") return [...NUMBER_OPERATORS];
  if (property.type === "boolean") return [...BOOLEAN_OPERATORS];
  return [...STRING_OPERATORS];
}

function issue(errors: QueryBuilderIssue[], path: string, code: QueryBuilderIssueCode): void {
  errors.push({ path, code });
}

function hasUnsupportedBacktick(name: string): boolean {
  return name.includes("`");
}

function propertyExpression(property: QueryBuilderProperty): string {
  return property.source === "meta" ? `n.${property.id}` : `n.${quoteIfNeeded(property.id)}`;
}

function conditionLiteral(property: QueryBuilderProperty, value: string): string | null {
  if (property.type === "string") return stringLiteral(value);
  const trimmed = value.trim();
  if (property.type === "boolean") {
    if (trimmed !== "true" && trimmed !== "false") return null;
    return trimmed.toUpperCase();
  }
  if (!NUMBER_RE.test(trimmed)) return null;
  if (trimmed.includes(".")) {
    if (!Number.isFinite(Number(trimmed))) return null;
  } else {
    const integer = BigInt(trimmed);
    if (integer < QUERY_INTEGER_MIN || integer > LONG_MAX) return null;
  }
  return trimmed;
}

function buildConditions(
  conditions: QueryBuilderCondition[],
  properties: QueryBuilderProperty[],
  errors: QueryBuilderIssue[],
): string[] {
  if (conditions.length > QUERY_BUILDER_MAX_CONDITIONS) issue(errors, "conditions", "tooManyConditions");
  return conditions.map((condition, index) => {
    const base = `conditions.${index}`;
    if (!condition.property) {
      issue(errors, `${base}.property`, "propertyRequired");
      return "";
    }
    const property = properties.find((candidate) => candidate.id === condition.property);
    if (!property) {
      issue(errors, `${base}.property`, "unknownProperty");
      return "";
    }
    if (hasUnsupportedBacktick(property.id)) issue(errors, `${base}.property`, "unsupportedName");
    if (!getQueryBuilderOperators(property).includes(condition.operator)) {
      issue(errors, `${base}.operator`, "operatorUnsupported");
      return "";
    }
    const left = propertyExpression(property);
    if (condition.operator === "isNull" || condition.operator === "isNotNull") {
      return `${left} ${OPERATOR_TEXT[condition.operator]}`;
    }
    if (condition.value === "") {
      issue(errors, `${base}.value`, "valueRequired");
      return "";
    }
    const literal = conditionLiteral(property, condition.value);
    if (literal == null) {
      issue(errors, `${base}.value`, "valueInvalid");
      return "";
    }
    if (property.enumValues.length > 0 && !property.enumValues.some((value) => String(value) === condition.value)) {
      issue(errors, `${base}.value`, "valueInvalid");
    }
    return `${left} ${OPERATOR_TEXT[condition.operator]} ${literal}`;
  });
}

function validateTarget(
  connection: QueryBuilderConnection,
  schema: QueryCompletionSchema,
  expectedBlueprint: string | null,
  errors: QueryBuilderIssue[],
): Blueprint | undefined {
  if (connection.targetIdentifier && !connection.targetBlueprint) {
    issue(errors, "connection.targetIdentifier", "targetIdentifierRequiresBlueprint");
  }
  if (!connection.targetBlueprint) {
    if (expectedBlueprint) issue(errors, "connection.targetBlueprint", "targetBlueprintUnknown");
    return undefined;
  }
  const target = findBlueprint(schema, connection.targetBlueprint);
  if (!target) issue(errors, "connection.targetBlueprint", "targetBlueprintUnknown");
  else if (expectedBlueprint && target.identifier.toLowerCase() !== expectedBlueprint.toLowerCase()) {
    issue(errors, "connection.targetBlueprint", "connectionUnsupported");
  }
  if (hasUnsupportedBacktick(connection.targetBlueprint)) {
    issue(errors, "connection.targetBlueprint", "unsupportedName");
  }
  return target;
}

function hasEdgeCollision(name: string, sources: Blueprint[]): boolean {
  return sources.some((source) => Object.hasOwn(source.relations, name));
}

function hierarchySources(
  connection: QueryBuilderConnection,
  source: Blueprint,
  schema: QueryCompletionSchema,
  hopValue: number,
): Blueprint[] {
  if (hopValue > 1) return schema.blueprints;
  if (connection.direction === "out") return [source];
  if (!connection.targetBlueprint) return schema.blueprints;
  const target = findBlueprint(schema, connection.targetBlueprint);
  return target ? [target] : [];
}

function validateConnection(
  connection: QueryBuilderConnection,
  source: Blueprint,
  schema: QueryCompletionSchema,
  errors: QueryBuilderIssue[],
): { edge: string; hops: string; target: Blueprint | undefined } {
  if (!connection.name) issue(errors, "connection.name", "connectionNameRequired");
  if (hasUnsupportedBacktick(connection.name)) issue(errors, "connection.name", "unsupportedName");

  let expectedTarget: string | null = null;
  let edge = connection.name;
  let hops = "";

  if (connection.kind === "relation") {
    const relation = source.relations[connection.name];
    if (!relation) issue(errors, "connection.name", "connectionUnsupported");
    else expectedTarget = relation.target;
    if (connection.direction !== "out") issue(errors, "connection.direction", "directionUnsupported");
    if (schema.hierarchies.some((name) => name.toLowerCase() === connection.name.toLowerCase())) {
      issue(errors, "connection.name", "connectionUnsupported");
    }
    if (connection.maxHops !== "1") issue(errors, "connection.maxHops", "maxHopsInvalid");
  } else if (connection.kind === "ownership") {
    edge = "$team";
    if (connection.name !== "$team") issue(errors, "connection.name", "connectionUnsupported");
    if (connection.maxHops !== "1") issue(errors, "connection.maxHops", "maxHopsInvalid");
    if (connection.direction === "out") expectedTarget = "_team";
    else if (source.identifier.toLowerCase() !== "_team") {
      issue(errors, "connection.direction", "directionUnsupported");
    }
  } else {
    const hierarchy = schema.hierarchies.find((name) => name.toLowerCase() === connection.name.toLowerCase());
    if (!hierarchy) issue(errors, "connection.name", "connectionUnsupported");
    else edge = hierarchy;
    const hopValue = INTEGER_RE.test(connection.maxHops) ? Number(connection.maxHops) : Number.NaN;
    if (!Number.isInteger(hopValue) || hopValue < 1 || hopValue > MAX_HOPS) {
      issue(errors, "connection.maxHops", "maxHopsInvalid");
    } else {
      hops = `*1..${hopValue}`;
    }
    const possibleSources = hierarchySources(connection, source, schema, hopValue);
    if (hierarchy && hasEdgeCollision(hierarchy, possibleSources)) {
      issue(errors, "connection.name", "connectionUnsupported");
    }
  }

  const target = validateTarget(connection, schema, expectedTarget, errors);
  return { edge, hops, target };
}

function targetPattern(connection: QueryBuilderConnection, target: Blueprint | undefined): string {
  const label = target ? `:${quoteIfNeeded(target.identifier)}` : "";
  const identifier = connection.targetIdentifier
    ? ` {$identifier: ${stringLiteral(connection.targetIdentifier)}}`
    : "";
  return `(m${label}${identifier})`;
}

function connectionPattern(connection: QueryBuilderConnection, edge: string, hops: string, target: Blueprint | undefined): string {
  const body = `[:${edge === "$team" ? edge : quoteIfNeeded(edge)}${hops}]`;
  const targetNode = targetPattern(connection, target);
  return connection.direction === "out" ? `(n)-${body}->${targetNode}` : `(n)<-${body}-${targetNode}`;
}

function validateLimit(raw: string, errors: QueryBuilderIssue[]): string {
  if (raw === "") return "";
  if (!INTEGER_RE.test(raw)) {
    issue(errors, "limit", "limitInvalid");
    return "";
  }
  const value = Number(raw);
  if (!Number.isSafeInteger(value) || value < 1 || value > MAX_LIMIT) {
    issue(errors, "limit", "limitInvalid");
    return "";
  }
  return String(value);
}

export function buildQuery(model: QueryBuilderModel, schema: QueryCompletionSchema): QueryBuilderResult {
  const errors: QueryBuilderIssue[] = [];
  if (!model.blueprint) issue(errors, "blueprint", "blueprintRequired");
  const source = model.blueprint ? findBlueprint(schema, model.blueprint) : undefined;
  if (model.blueprint && !source) issue(errors, "blueprint", "unknownBlueprint");
  if (hasUnsupportedBacktick(model.blueprint)) issue(errors, "blueprint", "unsupportedName");

  const properties = source ? getQueryBuilderProperties(schema, source.identifier) : [];
  const conditions = buildConditions(model.conditions, properties, errors);
  const limit = validateLimit(model.limit, errors);
  if (!model.connection && model.returns !== "start") issue(errors, "returns", "returnUnsupported");

  let connectionClause = "";
  if (model.connection && source) {
    const { edge, hops, target } = validateConnection(model.connection, source, schema, errors);
    const keyword = model.connection.optional ? "OPTIONAL MATCH" : "MATCH";
    connectionClause = `${keyword} ${connectionPattern(model.connection, edge, hops, target)}`;
  }

  if (!source || errors.length > 0) return { query: "", errors };
  const parts = [`MATCH (n:${quoteIfNeeded(source.identifier)})`];
  if (conditions.length > 0) parts.push(`WHERE ${conditions.join(" AND ")}`);
  if (connectionClause) parts.push(connectionClause);
  const returns = model.returns === "start" ? "n" : model.returns === "target" ? "m" : "n, m";
  parts.push(`RETURN ${returns}`);
  if (limit) parts.push(`LIMIT ${limit}`);
  const query = parts.join(" ");
  if (query.length > MAX_QUERY_LENGTH) return { query: "", errors: [{ path: "query", code: "queryTooLong" }] };
  return { query, errors: [] };
}
