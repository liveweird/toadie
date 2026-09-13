import { describe, expect, test } from "vitest";
import type { Blueprint } from "../api/blueprints";
import { queryCompletions, type QueryCompletionSchema } from "./queryCompletion";

function blueprint(overrides: Partial<Blueprint> & { identifier: string }): Blueprint {
  return {
    id: 1,
    title: overrides.identifier,
    schema: { properties: {}, required: [] },
    relations: {},
    mirrorProperties: {},
    calculationProperties: {},
    aggregationProperties: {},
    createdBy: 1,
    creatorName: "admin",
    creatorDeleted: false,
    createdAt: 0,
    updatedAt: 0,
    system: false,
    ...overrides,
  };
}

const service = blueprint({
  identifier: "service",
  title: "Service",
  schema: {
    properties: {
      lifecycle: { type: "string", enum: ["experimental", "production"] },
      name: { type: "string" },
    },
    required: [],
  },
  relations: {
    owned_by: { title: "Owner", target: "_team", required: false, many: false },
    depends_on: { title: "Depends on", target: "service", required: false, many: true },
  },
});

const team = blueprint({ identifier: "_team", title: "Team" });

const schema: QueryCompletionSchema = { blueprints: [service, team], hierarchies: ["composition"] };

describe("queryCompletions", () => {
  test("clause start: empty text suggests every clause keyword", () => {
    const result = queryCompletions("", schema);
    expect(result?.from).toBe(0);
    expect(result?.options.map((o) => o.label)).toEqual(["MATCH", "OPTIONAL MATCH", "WHERE", "RETURN", "LIMIT"]);
  });

  test("clause start: a partial word filters and completes to the canonical keyword", () => {
    const result = queryCompletions("MAT", schema);
    expect(result?.from).toBe(0);
    expect(result?.options).toEqual([{ label: "MATCH", apply: "MATCH" }]);
  });

  test("clause start: right after a closed pattern's ')'", () => {
    const result = queryCompletions("MATCH (a:service) RET", schema);
    expect(result?.from).toBe("MATCH (a:service) ".length);
    expect(result?.options).toEqual([{ label: "RETURN", apply: "RETURN" }]);
  });

  test("clause start: mid-expression is not a clause-start position", () => {
    expect(queryCompletions("MATCH (a:service) WHERE a", schema)).toBeNull();
  });

  test("node label: after '(v:' suggests blueprint identifiers labelled by title", () => {
    const result = queryCompletions("MATCH (a:se", schema);
    expect(result?.from).toBe("MATCH (a:".length);
    expect(result?.options).toEqual([{ label: "service", detail: "Service", apply: "service" }]);
  });

  test("node label: after '|' inside a node suggests the remaining blueprints", () => {
    const result = queryCompletions("MATCH (a:service|", schema);
    expect(result?.options.map((o) => o.label)).toEqual(["service", "_team"]);
  });

  test("node label: quotes an identifier needing backticks on apply", () => {
    const withDash = blueprint({ identifier: "web-service", title: "Web service" });
    const result = queryCompletions("MATCH (a:web", { blueprints: [withDash], hierarchies: [] });
    expect(result?.options).toEqual([{ label: "web-service", detail: "Web service", apply: "`web-service`" }]);
  });

  test("edge body: after '[…:' with a labelled left node suggests that blueprint's relations", () => {
    const result = queryCompletions("MATCH (a:service)-[:", schema);
    expect(result?.from).toBe("MATCH (a:service)-[:".length);
    const labels = result?.options.map((o) => o.label);
    expect(labels).toContain("owned_by");
    expect(labels).toContain("depends_on");
    expect(labels).toContain("composition");
    expect(labels).toContain("$team");
  });

  test("edge body: an unlabelled left node unions relation keys across every blueprint", () => {
    const result = queryCompletions("MATCH (a)-[:", schema);
    const labels = result?.options.map((o) => o.label);
    expect(labels).toEqual(expect.arrayContaining(["owned_by", "depends_on"]));
  });

  test("edge body: filters by the partial already typed, including '$team'", () => {
    const result = queryCompletions("MATCH (a:service)-[:$", schema);
    expect(result?.options).toEqual([{ label: "$team", apply: "$team" }]);
  });

  test("edge body: after '|' continues suggesting relation keys", () => {
    const result = queryCompletions("MATCH (a:service)-[:owned_by|dep", schema);
    expect(result?.options.map((o) => o.label)).toEqual(["depends_on"]);
  });

  test("property: after 'v.' suggests the declared label's properties plus the seven metas", () => {
    const result = queryCompletions("MATCH (a:service) WHERE a.", schema);
    expect(result?.from).toBe("MATCH (a:service) WHERE a.".length);
    const labels = result?.options.map((o) => o.label);
    expect(labels).toEqual(["lifecycle", "name", "$identifier", "$title", "$blueprint", "$team", "$icon", "$createdAt", "$updatedAt"]);
  });

  test("property: filters by the partial word after the dot", () => {
    const result = queryCompletions("MATCH (a:service) WHERE a.li", schema);
    expect(result?.options).toEqual([{ label: "lifecycle", detail: undefined, apply: "lifecycle" }]);
  });

  test("property: an unresolved variable falls back to the seven metas only", () => {
    const result = queryCompletions("MATCH (a) WHERE a.", schema);
    expect(result?.options.map((o) => o.label)).toEqual(["$identifier", "$title", "$blueprint", "$team", "$icon", "$createdAt", "$updatedAt"]);
  });

  test("enum literal: after 'v.prop =' suggests the property's enum values as string literals", () => {
    const result = queryCompletions("MATCH (a:service) WHERE a.lifecycle = ", schema);
    expect(result?.from).toBe("MATCH (a:service) WHERE a.lifecycle = ".length);
    expect(result?.options).toEqual([{ label: "'experimental'" }, { label: "'production'" }]);
  });

  test("enum literal: after 'v.prop IN [' suggests the same enum values", () => {
    const result = queryCompletions("MATCH (a:service) WHERE a.lifecycle IN [", schema);
    expect(result?.options).toEqual([{ label: "'experimental'" }, { label: "'production'" }]);
  });

  test("enum literal: a property without an enum falls through to no completion", () => {
    expect(queryCompletions("MATCH (a:service) WHERE a.name = ", schema)).toBeNull();
  });

  test("unmatched context returns null", () => {
    expect(queryCompletions("MATCH (a:service) WHERE a.name = 'x' AND", schema)).toBeNull();
  });

  test("a bracket inside a string literal is text, not a pattern context", () => {
    // The unmatched '(' lives inside the string; the later ':' inside another string must not
    // pop a blueprint list as if the cursor were in a node body.
    expect(queryCompletions("MATCH (a:service) WHERE a.note CONTAINS 'sad :(' AND a.other = 'x:", schema)).toBeNull();
    // A backticked name with a bracket inside is a name, not a bracket either.
    expect(queryCompletions("MATCH (a:`odd(name`) WHERE a.x = 'y:", schema)).toBeNull();
  });
});
