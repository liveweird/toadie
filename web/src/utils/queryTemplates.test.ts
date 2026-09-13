import { describe, expect, it } from "vitest";
import { ancestorsQuery, descendantsQuery, expandQuery, ownedByQuery } from "./queryTemplates";

describe("queryTemplates", () => {
  const node = { blueprint: "service", identifier: "orders-api" };

  it("expandQuery anchors on the blueprint + $identifier and walks n undirected hops", () => {
    expect(expandQuery(node, 1)).toBe(
      "MATCH (n:service {$identifier: 'orders-api'}) OPTIONAL MATCH (n)-[*1..1]-(m) RETURN n, m",
    );
    expect(expandQuery(node, 3)).toContain("-[*1..3]-(m)");
  });

  it("ancestorsQuery follows the hierarchy's virtual edge up to the hop ceiling", () => {
    expect(ancestorsQuery(node, "composition")).toBe(
      "MATCH (n:service {$identifier: 'orders-api'}) OPTIONAL MATCH (n)-[:composition*1..10]->(a) RETURN n, a",
    );
  });

  it("descendantsQuery walks the same edge type against the arrow", () => {
    expect(descendantsQuery(node, "composition")).toBe(
      "MATCH (n:service {$identifier: 'orders-api'}) OPTIONAL MATCH (n)<-[:composition*1..10]-(d) RETURN n, d",
    );
  });

  it("ownedByQuery anchors on the _team entity and returns everything it owns", () => {
    expect(ownedByQuery("platform")).toBe(
      "MATCH (t:_team {$identifier: 'platform'}) OPTIONAL MATCH (e)-[:$team]->(t) RETURN t, e",
    );
  });

  it("backticks non-identifier blueprint and hierarchy names and escapes the identifier literal", () => {
    const odd = { blueprint: "web-service", identifier: "it's \\ here" };
    expect(expandQuery(odd, 2)).toBe(
      "MATCH (n:`web-service` {$identifier: 'it\\'s \\\\ here'}) OPTIONAL MATCH (n)-[*1..2]-(m) RETURN n, m",
    );
    expect(ancestorsQuery(odd, "org-chart")).toContain("-[:`org-chart`*1..10]->(a)");
    expect(descendantsQuery(odd, "org chart")).toContain("<-[:`org chart`*1..10]-(d)");
    expect(ownedByQuery("team's")).toContain("{$identifier: 'team\\'s'}");
  });
});
