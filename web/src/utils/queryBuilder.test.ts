import { describe, expect, test } from "vitest";
import type { Blueprint } from "../api/blueprints";
import { blueprintResponse } from "../test/fixtures";
import type { QueryCompletionSchema } from "./queryCompletion";
import {
  buildQuery,
  createQueryBuilderModel,
  getQueryBuilderOperators,
  getQueryBuilderProperties,
  QUERY_BUILDER_MAX_CONDITIONS,
  type QueryBuilderConnection,
  type QueryBuilderModel,
} from "./queryBuilder";

function blueprint(overrides: Partial<Blueprint> & { identifier: string }): Blueprint {
  return blueprintResponse({ title: overrides.identifier, ...overrides });
}

const service = blueprint({
  identifier: "service",
  title: "Service",
  schema: {
    properties: {
      lifecycle: { type: "string", title: "Lifecycle", enum: ["production", "deprecated"] },
      score: { type: "number", title: "Score" },
      enabled: { type: "boolean", title: "Enabled" },
      payload: { type: "object", title: "Payload" },
      order: { type: "string", title: "Order" },
    },
    required: [],
  },
  relations: {
    owned_by: { title: "Owner", target: "_team", required: false, many: false },
    depends_on: { title: "Dependency", target: "service", required: false, many: true },
  },
});
const team = blueprint({ identifier: "_team", title: "Team" });
const system = blueprint({ identifier: "system", title: "System" });
const schema: QueryCompletionSchema = {
  blueprints: [service, team, system],
  hierarchies: ["composition"],
};

function model(overrides: Partial<QueryBuilderModel> = {}): QueryBuilderModel {
  return { ...createQueryBuilderModel(), blueprint: "service", ...overrides };
}

function connection(overrides: Partial<QueryBuilderConnection> = {}): QueryBuilderConnection {
  return {
    kind: "relation",
    name: "depends_on",
    direction: "out",
    targetBlueprint: "service",
    targetIdentifier: "",
    maxHops: "1",
    optional: false,
    ...overrides,
  };
}

describe("query builder schema model", () => {
  test("creates the documented empty model", () => {
    expect(createQueryBuilderModel()).toEqual({
      blueprint: "",
      conditions: [],
      connection: null,
      returns: "start",
      limit: "",
    });
  });

  test("offers only stored scalar properties plus identifier and title metadata", () => {
    expect(getQueryBuilderProperties(schema, "service")).toEqual([
      { id: "lifecycle", title: "Lifecycle", source: "stored", type: "string", enumValues: ["production", "deprecated"] },
      { id: "score", title: "Score", source: "stored", type: "number", enumValues: [] },
      { id: "enabled", title: "Enabled", source: "stored", type: "boolean", enumValues: [] },
      { id: "order", title: "Order", source: "stored", type: "string", enumValues: [] },
      { id: "$identifier", title: "$identifier", source: "meta", type: "string", enumValues: [] },
      { id: "$title", title: "$title", source: "meta", type: "string", enumValues: [] },
    ]);
    expect(getQueryBuilderProperties(schema, "missing")).toEqual([]);
  });

  test("limits operators by scalar type", () => {
    const properties = getQueryBuilderProperties(schema, "service");
    expect(getQueryBuilderOperators(properties.find((property) => property.id === "score")!)).toEqual([
      "eq", "neq", "lt", "lte", "gt", "gte", "isNull", "isNotNull",
    ]);
    expect(getQueryBuilderOperators(properties.find((property) => property.id === "enabled")!)).toEqual([
      "eq", "neq", "isNull", "isNotNull",
    ]);
  });
});

describe("buildQuery", () => {
  test("serializes typed conditions, escaping names and string values", () => {
    expect(buildQuery(model({
      conditions: [
        { property: "order", operator: "eq", value: "it's\\live" },
        { property: "score", operator: "gte", value: "-2.5" },
        { property: "enabled", operator: "eq", value: "true" },
        { property: "$title", operator: "isNotNull", value: "ignored" },
      ],
      limit: "25",
    }), schema)).toEqual({
      query: "MATCH (n:service) WHERE n.`order` = 'it\\'s\\\\live' AND n.score >= -2.5 AND n.enabled = TRUE AND n.$title IS NOT NULL RETURN n LIMIT 25",
      errors: [],
    });
  });

  test("places source filters on the required match before an optional relation", () => {
    const result = buildQuery(model({
      conditions: [{ property: "lifecycle", operator: "eq", value: "production" }],
      connection: connection({ optional: true, targetIdentifier: "billing'api" }),
      returns: "both",
    }), schema);
    expect(result).toEqual({
      query: "MATCH (n:service) WHERE n.lifecycle = 'production' OPTIONAL MATCH (n)-[:depends_on]->(m:service {$identifier: 'billing\\'api'}) RETURN n, m",
      errors: [],
    });
  });

  test("serializes a required relation as a second plain match", () => {
    expect(buildQuery(model({ connection: connection(), returns: "target" }), schema).query).toBe(
      "MATCH (n:service) MATCH (n)-[:depends_on]->(m:service) RETURN m",
    );
  });

  test("serializes hierarchy direction and bounded hops with an optional target label", () => {
    const result = buildQuery(model({
      connection: connection({
        kind: "hierarchy",
        name: "composition",
        direction: "in",
        targetBlueprint: "system",
        maxHops: "10",
        optional: true,
      }),
      returns: "both",
    }), schema);
    expect(result.query).toBe(
      "MATCH (n:service) OPTIONAL MATCH (n)<-[:composition*1..10]-(m:system) RETURN n, m",
    );
  });

  test("serializes outgoing and incoming ownership with their allowed anchors", () => {
    const outgoing = buildQuery(model({
      connection: connection({ kind: "ownership", name: "$team", targetBlueprint: "_team" }),
      returns: "both",
    }), schema);
    expect(outgoing.query).toBe("MATCH (n:service) MATCH (n)-[:$team]->(m:_team) RETURN n, m");

    const incoming = buildQuery(model({
      blueprint: "_team",
      connection: connection({
        kind: "ownership",
        name: "$team",
        direction: "in",
        targetBlueprint: "service",
        optional: true,
      }),
      returns: "both",
    }), schema);
    expect(incoming.query).toBe("MATCH (n:_team) OPTIONAL MATCH (n)<-[:$team]-(m:service) RETURN n, m");
  });

  test("returns no query when the live schema no longer contains selected fields", () => {
    expect(buildQuery(model({
      conditions: [{ property: "removed", operator: "eq", value: "x" }],
      connection: connection({ name: "removed_relation" }),
    }), schema)).toEqual({
      query: "",
      errors: [
        { path: "conditions.0.property", code: "unknownProperty" },
        { path: "connection.name", code: "connectionUnsupported" },
      ],
    });
  });

  test("rejects malformed scalar values, unsupported operators, enums, limits, and hop counts", () => {
    const result = buildQuery(model({
      conditions: [
        { property: "score", operator: "contains", value: "1" },
        { property: "score", operator: "eq", value: "9223372036854775808" },
        { property: "enabled", operator: "eq", value: "yes" },
        { property: "lifecycle", operator: "eq", value: "retired" },
      ],
      connection: connection({ kind: "hierarchy", name: "composition", maxHops: "11" }),
      limit: "10001",
    }), schema);
    expect(result.query).toBe("");
    expect(result.errors).toEqual([
      { path: "conditions.0.operator", code: "operatorUnsupported" },
      { path: "conditions.1.value", code: "valueInvalid" },
      { path: "conditions.2.value", code: "valueInvalid" },
      { path: "conditions.3.value", code: "valueInvalid" },
      { path: "limit", code: "limitInvalid" },
      { path: "connection.maxHops", code: "maxHopsInvalid" },
    ]);
  });

  test("matches the backend integer literal bounds, including its asymmetric negative limit", () => {
    expect(buildQuery(model({
      conditions: [
        { property: "score", operator: "gte", value: "-9223372036854775807" },
        { property: "score", operator: "lte", value: "9223372036854775807" },
      ],
    }), schema).query).toBe(
      "MATCH (n:service) WHERE n.score >= -9223372036854775807 AND n.score <= 9223372036854775807 RETURN n",
    );
    expect(buildQuery(model({
      conditions: [{ property: "score", operator: "eq", value: "-9223372036854775808" }],
    }), schema)).toEqual({
      query: "",
      errors: [{ path: "conditions.0.value", code: "valueInvalid" }],
    });
  });

  test("rejects targets that cannot be disambiguated and invalid return selection", () => {
    expect(buildQuery(model({
      connection: connection({
        kind: "hierarchy",
        name: "composition",
        targetBlueprint: "",
        targetIdentifier: "billing",
      }),
    }), schema).errors).toContainEqual({
      path: "connection.targetIdentifier",
      code: "targetIdentifierRequiresBlueprint",
    });
    expect(buildQuery(model({ returns: "target" }), schema)).toEqual({
      query: "",
      errors: [{ path: "returns", code: "returnUnsupported" }],
    });
    expect(buildQuery(model({
      connection: connection({ targetBlueprint: "" }),
    }), schema).errors).toContainEqual({
      path: "connection.targetBlueprint",
      code: "targetBlueprintUnknown",
    });
  });

  test("refuses relation/hierarchy name collisions because the backend resolves their union", () => {
    const colliding = { ...schema, hierarchies: ["depends_on"] };
    expect(buildQuery(model({ connection: connection() }), colliding)).toEqual({
      query: "",
      errors: [{ path: "connection.name", code: "connectionUnsupported" }],
    });

    const intermediateCollision = blueprint({
      identifier: "domain",
      relations: { composition: { title: "Collision", target: "system", required: false, many: false } },
    });
    expect(buildQuery(model({
      connection: connection({
        kind: "hierarchy",
        name: "composition",
        targetBlueprint: "system",
        maxHops: "2",
      }),
    }), { ...schema, blueprints: [...schema.blueprints, intermediateCollision] }).errors).toContainEqual({
      path: "connection.name",
      code: "connectionUnsupported",
    });
  });

  test("caps conditions and total serialized query length", () => {
    const conditions = Array.from({ length: QUERY_BUILDER_MAX_CONDITIONS + 1 }, () => ({
      property: "score",
      operator: "eq" as const,
      value: "1",
    }));
    expect(buildQuery(model({ conditions }), schema).errors).toContainEqual({
      path: "conditions",
      code: "tooManyConditions",
    });

    expect(buildQuery(model({
      conditions: [{ property: "order", operator: "contains", value: "x".repeat(2000) }],
    }), schema)).toEqual({ query: "", errors: [{ path: "query", code: "queryTooLong" }] });
  });

  test("rejects an unsupported embedded backtick instead of emitting a query the backend misreads", () => {
    const malformed = blueprint({
      identifier: "odd",
      schema: { properties: { "a`b": { type: "string" } }, required: [] },
    });
    expect(buildQuery(model({
      blueprint: "odd",
      conditions: [{ property: "a`b", operator: "eq", value: "x" }],
    }), { blueprints: [malformed], hierarchies: [] })).toEqual({
      query: "",
      errors: [{ path: "conditions.0.property", code: "unsupportedName" }],
    });
  });
});
