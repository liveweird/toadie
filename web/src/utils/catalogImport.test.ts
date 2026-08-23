import { describe, expect, test } from "vitest";
import type { CatalogFileRequest } from "../api/catalogFiles";
import { normalizeCatalogUrl, parseCatalogYaml } from "./catalogImport";
import { catalogInfoMultiYaml } from "./catalogYaml";

const COMPONENT_YAML = [
  "apiVersion: backstage.io/v1alpha1",
  "kind: Component",
  "metadata:",
  "  name: payments-svc",
  "spec:",
  "  type: service",
  "  lifecycle: production",
  "  owner: group:default/platform",
  "",
].join("\n");

describe("parseCatalogYaml", () => {
  test("parses a multi-document file into documents in order", () => {
    const text = `${COMPONENT_YAML}---\nkind: Group\nmetadata:\n  name: team-a\nspec:\n  type: team\n  children: []\n`;
    const { documents, errors } = parseCatalogYaml(text);
    expect(errors).toEqual([]);
    expect(documents.map((d) => d.kind)).toEqual(["Component", "Group"]);
    expect(documents[0].metadata.name).toBe("payments-svc");
    expect(documents[1].spec.children).toEqual([]);
  });

  test("defaults an omitted namespace and accepts-and-ignores apiVersion", () => {
    const { documents, errors } = parseCatalogYaml(COMPONENT_YAML);
    expect(errors).toEqual([]);
    expect(documents[0].metadata.namespace).toBe("default");
    expect(documents[0]).not.toHaveProperty("apiVersion");
  });

  test("empty input and blank documents yield nothing", () => {
    expect(parseCatalogYaml("")).toEqual({ documents: [], errors: [] });
    expect(parseCatalogYaml("---\n---\n")).toEqual({ documents: [], errors: [] });
  });

  test("canonicalizes a case-variant kind and rejects an unknown one", () => {
    const lower = parseCatalogYaml("kind: component\nmetadata:\n  name: a\nspec: {}\n");
    expect(lower.documents[0].kind).toBe("Component");

    const unknown = parseCatalogYaml("kind: Location\nmetadata:\n  name: a\nspec: {}\n");
    expect(unknown.documents).toEqual([]);
    expect(unknown.errors[0].message).toContain("kind must be one of");
  });

  test("kind and metadata.name are required", () => {
    expect(parseCatalogYaml("metadata:\n  name: a\n").errors[0].message).toBe("kind is required");
    expect(parseCatalogYaml("kind: Component\nmetadata: {}\n").errors[0].message).toBe(
      "metadata.name is required",
    );
  });

  test("an unknown key marks only THAT document invalid, naming the key", () => {
    const text = `kind: Component\nmetadata:\n  name: a\n  color: green\nspec: {}\n---\n${COMPONENT_YAML}`;
    const { documents, errors } = parseCatalogYaml(text);
    expect(errors).toEqual([{ index: 0, message: "unknown key metadata.color" }]);
    expect(documents.map((d) => d.metadata.name)).toEqual(["payments-svc"]);
  });

  test("unknown keys are rejected at every level", () => {
    expect(parseCatalogYaml("kind: Component\nmetadata:\n  name: a\nspec: {}\nstatus: ok\n").errors[0].message)
      .toBe("unknown key status");
    expect(
      parseCatalogYaml("kind: Component\nmetadata:\n  name: a\nspec:\n  replicas: 3\n").errors[0].message,
    ).toBe("unknown key spec.replicas");
    expect(
      parseCatalogYaml(
        "kind: User\nmetadata:\n  name: a\nspec:\n  memberOf: []\n  profile:\n    nickname: x\n",
      ).errors[0].message,
    ).toBe("unknown key spec.profile.nickname");
    expect(
      parseCatalogYaml(
        "kind: Component\nmetadata:\n  name: a\n  links:\n    - url: https://x\n      color: red\nspec: {}\n",
      ).errors[0].message,
    ).toBe("unknown key metadata.links[0].color");
  });

  test("type mismatches name the offending key", () => {
    expect(
      parseCatalogYaml("kind: Component\nmetadata:\n  name: a\n  tags: java\nspec: {}\n").errors[0].message,
    ).toBe("metadata.tags must be a list of strings");
    expect(
      parseCatalogYaml("kind: Component\nmetadata:\n  name: a\n  labels:\n    tier: 3\nspec: {}\n")
        .errors[0].message,
    ).toBe("metadata.labels.tier must be a string");
    expect(
      parseCatalogYaml("kind: Component\nmetadata:\n  name: a\n  title: 42\nspec: {}\n").errors[0].message,
    ).toBe("metadata.title must be a string");
    expect(parseCatalogYaml("kind: Component\nmetadata: [a]\nspec: {}\n").errors[0].message).toBe(
      "metadata must be a YAML mapping",
    );
  });

  test("a YAML syntax error is reported with its document index, sparing its neighbors", () => {
    const text = `${COMPONENT_YAML}---\nkind: [broken\n`;
    const { documents, errors } = parseCatalogYaml(text);
    expect(documents).toHaveLength(1);
    expect(errors).toHaveLength(1);
    expect(errors[0].index).toBe(1);
  });

  test("an absent spec maps to {} — the server owns the per-kind required rules", () => {
    const { documents, errors } = parseCatalogYaml("kind: Domain\nmetadata:\n  name: payments\n");
    expect(errors).toEqual([]);
    expect(documents[0].spec).toEqual({});
  });

  test("the round-trip property: parsing a generated export returns the same documents", () => {
    const files: CatalogFileRequest[] = [
      {
        kind: "Component",
        metadata: {
          name: "svc",
          namespace: "team-a",
          title: "The Service",
          description: "line one\nline two",
          labels: { "example.com/tier": "backend" },
          annotations: { "example.com/note": "free text" },
          tags: ["java"],
          links: [{ url: "https://example.com", title: "Home", icon: "web" }],
        },
        spec: {
          type: "service",
          lifecycle: "production",
          owner: "group:default/platform",
          system: "payments",
          subcomponentOf: "component:parent-svc",
          providesApis: ["billing-api"],
          consumesApis: ["payments-api"],
          dependsOn: ["resource:orders-db"],
          dependencyOf: ["component:reporting"],
        },
      },
      {
        kind: "Group",
        metadata: { name: "team-a", namespace: "default" },
        spec: { type: "team", children: [], members: ["jdoe"] },
      },
      {
        kind: "User",
        metadata: { name: "jdoe", namespace: "default" },
        spec: { profile: { displayName: "J. Doe", email: "jdoe@example.com" }, memberOf: [] },
      },
    ];
    const { documents, errors } = parseCatalogYaml(catalogInfoMultiYaml(files));
    expect(errors).toEqual([]);
    expect(documents).toEqual(files);
  });
});

describe("normalizeCatalogUrl", () => {
  test("rewrites a GitHub blob link to its raw.githubusercontent.com form", () => {
    expect(
      normalizeCatalogUrl("https://github.com/acme/service/blob/main/catalog-info.yaml"),
    ).toBe("https://raw.githubusercontent.com/acme/service/main/catalog-info.yaml");
    expect(
      normalizeCatalogUrl("https://github.com/acme/service/blob/v1.2/nested/dir/catalog-info.yaml"),
    ).toBe("https://raw.githubusercontent.com/acme/service/v1.2/nested/dir/catalog-info.yaml");
  });

  test("rewrites a GitLab blob link to its raw form (self-hosted included)", () => {
    expect(
      normalizeCatalogUrl("https://gitlab.com/acme/service/-/blob/main/catalog-info.yaml"),
    ).toBe("https://gitlab.com/acme/service/-/raw/main/catalog-info.yaml");
    expect(
      normalizeCatalogUrl("https://git.corp.example/group/sub/repo/-/blob/main/catalog-info.yaml"),
    ).toBe("https://git.corp.example/group/sub/repo/-/raw/main/catalog-info.yaml");
  });

  test("leaves raw links and everything else untouched (trim aside)", () => {
    const raw = "https://raw.githubusercontent.com/acme/service/main/catalog-info.yaml";
    expect(normalizeCatalogUrl(raw)).toBe(raw);
    expect(normalizeCatalogUrl("  https://example.com/catalog-info.yaml  ")).toBe(
      "https://example.com/catalog-info.yaml",
    );
    expect(normalizeCatalogUrl("https://example.com/blob/of/text.yaml")).toBe(
      "https://example.com/blob/of/text.yaml",
    );
  });
});
