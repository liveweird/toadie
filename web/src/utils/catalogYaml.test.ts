import { afterEach, describe, expect, test, vi } from "vitest";
import { parse } from "yaml";
import type { CatalogFileRequest } from "../api/catalogFiles";
import { catalogInfoYaml, downloadYaml } from "./catalogYaml";

const minimal: CatalogFileRequest = {
  kind: "Component",
  metadata: { name: "my-svc", namespace: "default" },
  spec: { type: "service", lifecycle: "production", owner: "group:default/platform" },
};

describe("catalogInfoYaml", () => {
  test("renders the minimal document in canonical order with empties omitted", () => {
    expect(catalogInfoYaml(minimal)).toBe(
      [
        "apiVersion: backstage.io/v1alpha1",
        "kind: Component",
        "metadata:",
        "  name: my-svc",
        "spec:",
        "  type: service",
        "  lifecycle: production",
        "  owner: group:default/platform",
        "",
      ].join("\n"),
    );
  });

  test("leaves the default namespace implicit but renders a custom one", () => {
    const withDefault = catalogInfoYaml({
      ...minimal,
      metadata: { ...minimal.metadata, namespace: "default" },
    });
    expect(withDefault).not.toContain("namespace");

    const custom = catalogInfoYaml({
      ...minimal,
      metadata: { ...minimal.metadata, namespace: "team-a" },
    });
    expect(custom).toContain("namespace: team-a");
  });

  test("round-trips the full surface through a YAML parser", () => {
    const full: CatalogFileRequest = {
      kind: "Component",
      metadata: {
        name: "svc",
        namespace: "team-a",
        title: "Żółty serwis: #1",
        description: "line one\nline two",
        labels: { "example.com/tier": "backend" },
        annotations: { "github.com/project-slug": "acme/svc" },
        tags: ["java", "c++"],
        links: [{ url: "https://example.com", title: "Home", icon: "dashboard" }],
      },
      spec: {
        type: "service",
        lifecycle: "experimental",
        owner: "team-a",
        system: "payments",
        subcomponentOf: "component:parent",
        providesApis: ["svc-api"],
        consumesApis: ["billing-api"],
        dependsOn: ["resource:default/svc-db"],
        dependencyOf: ["component:consumer"],
      },
    };
    const parsed = parse(catalogInfoYaml(full)) as Record<string, unknown>;
    expect(parsed.apiVersion).toBe("backstage.io/v1alpha1");
    expect(parsed.kind).toBe("Component");
    expect(parsed.metadata).toEqual({
      name: "svc",
      namespace: "team-a",
      // The tricky characters (diacritics, colon+space, hash) must survive quoting.
      title: "Żółty serwis: #1",
      description: "line one\nline two",
      labels: { "example.com/tier": "backend" },
      annotations: { "github.com/project-slug": "acme/svc" },
      tags: ["java", "c++"],
      links: [{ url: "https://example.com", title: "Home", icon: "dashboard" }],
    });
    expect(parsed.spec).toEqual(full.spec);
  });

  test("omits a link's absent title and icon", () => {
    const yaml = catalogInfoYaml({
      ...minimal,
      metadata: { ...minimal.metadata, links: [{ url: "https://example.com" }] },
    });
    expect(yaml).toContain("url: https://example.com");
    expect(yaml).not.toContain("title:");
    expect(yaml).not.toContain("icon:");
  });
});

describe("catalogInfoYaml per kind", () => {
  test("a Group renders children even when empty, and the profile compactly", () => {
    const yaml = catalogInfoYaml({
      kind: "Group",
      metadata: { name: "team-a", namespace: "default" },
      spec: {
        type: "team",
        profile: { displayName: "Team A" },
        children: [],
        members: ["user:jdoe"],
      },
    });
    expect(yaml).toContain("kind: Group");
    expect(yaml).toContain("children: []");
    expect(yaml).toContain("displayName: Team A");
    expect(yaml).not.toContain("email");
    const parsed = parse(yaml) as { spec: Record<string, unknown> };
    expect(parsed.spec.children).toEqual([]);
    expect(parsed.spec.members).toEqual(["user:jdoe"]);
  });

  test("a User renders memberOf even when empty", () => {
    const yaml = catalogInfoYaml({
      kind: "User",
      metadata: { name: "jdoe", namespace: "default" },
      spec: { memberOf: [] },
    });
    expect(yaml).toContain("kind: User");
    expect(yaml).toContain("memberOf: []");
    expect(yaml).not.toContain("profile");
  });

  test("an API renders its definition as a block", () => {
    const yaml = catalogInfoYaml({
      kind: "API",
      metadata: { name: "billing-api", namespace: "default" },
      spec: {
        type: "openapi",
        lifecycle: "production",
        owner: "team-a",
        definition: "openapi: 3.0.0\ninfo:\n  title: Billing",
      },
    });
    expect(yaml).toContain("kind: API");
    const parsed = parse(yaml) as { spec: { definition: string } };
    expect(parsed.spec.definition).toBe("openapi: 3.0.0\ninfo:\n  title: Billing");
  });
});

describe("downloadYaml", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  test("hands the text to the browser as catalog-info.yaml", () => {
    const createObjectURL = vi.fn().mockReturnValue("blob:fake");
    const revokeObjectURL = vi.fn();
    vi.stubGlobal("URL", Object.assign(URL, { createObjectURL, revokeObjectURL }));
    const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});

    downloadYaml("kind: Component\n");

    expect(createObjectURL).toHaveBeenCalledOnce();
    const blob = createObjectURL.mock.calls[0][0] as Blob;
    expect(blob.type).toBe("application/yaml");
    expect(click).toHaveBeenCalledOnce();
    expect(revokeObjectURL).toHaveBeenCalledWith("blob:fake");
    click.mockRestore();
  });
});

