import { describe, expect, test } from "vitest";
import {
  ACCOUNT_NAV,
  activeNavPath,
  DEFAULT_WORLD,
  homeOf,
  isWorld,
  sectionsFor,
  worldOf,
} from "./navigation";

describe("sectionsFor", () => {
  test("a regular Backstage session sees Catalog and Dictionaries only", () => {
    const sections = sectionsFor("backstage", false);
    expect(sections.map((s) => s.label)).toEqual([
      "appShell.section.catalog",
      "appShell.section.catalogDictionaries",
    ]);
    const catalog = sections.find((s) => s.label === "appShell.section.catalog");
    expect(catalog?.items[0]?.to).toBe("/hierarchy");
    expect(sections.flatMap((s) => s.items.map((l) => l.to))).not.toContain("/blueprints");
    expect(sections.flatMap((s) => s.items.map((l) => l.to))).not.toContain("/hierarchies");
  });

  test("a regular Port session sees Ontology and Dictionaries only, Import last", () => {
    const sections = sectionsFor("port", false);
    expect(sections.map((s) => s.label)).toEqual([
      "appShell.section.dataModel",
      "appShell.section.portDictionaries",
    ]);
    const dataModel = sections.find((s) => s.label === "appShell.section.dataModel");
    expect(dataModel?.items.map((l) => l.to)).toEqual([
      "/blueprints",
      "/entities",
      "/entity-graph",
      "/entity-hierarchy",
      "/ontology/errors",
      "/ontology/import",
    ]);
    const dictionaries = sections.find((s) => s.label === "appShell.section.portDictionaries");
    expect(dictionaries?.items.map((l) => l.to)).toContain("/hierarchies");
  });

  test("an admin session gets the Administration section in BOTH worlds", () => {
    for (const world of ["backstage", "port"] as const) {
      const admin = sectionsFor(world, true).find((s) => s.label === "appShell.section.administration");
      expect(admin?.items.map((l) => l.to)).toEqual(["/users", "/feature-flags"]);
    }
    for (const world of ["backstage", "port"] as const) {
      expect(
        sectionsFor(world, false).find((s) => s.label === "appShell.section.administration"),
      ).toBeUndefined();
    }
  });

  test("the account leaves never sit in a section", () => {
    for (const world of ["backstage", "port"] as const) {
      const sectionPaths = sectionsFor(world, true).flatMap((s) => s.items.map((l) => l.to));
      for (const leaf of ACCOUNT_NAV) expect(sectionPaths).not.toContain(leaf.to);
    }
    expect(ACCOUNT_NAV.map((l) => l.to)).toEqual(["/change-password", "/changelog"]);
  });
});

describe("activeNavPath", () => {
  const leaves = [...sectionsFor("backstage", true).flatMap((s) => s.items), ...sectionsFor("port", true).flatMap((s) => s.items)];

  test("resolves the longest matching prefix", () => {
    expect(activeNavPath("/files/3/edit", leaves)).toBe("/files");
    expect(activeNavPath("/users/new", leaves)).toBe("/users");
    expect(activeNavPath("/labels", leaves)).toBe("/labels");
  });

  test("/hierarchies resolves to itself, not /hierarchy", () => {
    expect(activeNavPath("/hierarchies", leaves)).toBe("/hierarchies");
  });

  test("/ matches nothing (there is no leaf at /)", () => {
    expect(activeNavPath("/", leaves)).toBeNull();
    expect(activeNavPath("/nowhere", leaves)).toBeNull();
  });
});

describe("worldOf", () => {
  test("Backstage routes", () => {
    for (const path of ["/hierarchy", "/files/3/edit", "/namespaces"]) {
      expect(worldOf(path)).toBe("backstage");
    }
  });

  test("Port routes", () => {
    for (const path of [
      "/hierarchies",
      "/entities/new",
      "/ontology/import",
      "/ontology/errors",
      "/blueprints/4/edit",
    ]) {
      expect(worldOf(path)).toBe("port");
    }
  });

  test("global/unknown routes have no world", () => {
    for (const path of ["/", "/users/new", "/changelog", "/change-password", "/nowhere"]) {
      expect(worldOf(path)).toBeNull();
    }
  });
});

describe("world constants", () => {
  test("homeOf", () => {
    expect(homeOf("backstage")).toBe("/hierarchy");
    expect(homeOf("port")).toBe("/entity-hierarchy");
  });

  test("DEFAULT_WORLD is port — the direction of travel", () => {
    expect(DEFAULT_WORLD).toBe("port");
  });

  test("isWorld", () => {
    expect(isWorld("backstage")).toBe(true);
    expect(isWorld("port")).toBe(true);
    expect(isWorld("nonsense")).toBe(false);
    expect(isWorld(undefined)).toBe(false);
  });
});
