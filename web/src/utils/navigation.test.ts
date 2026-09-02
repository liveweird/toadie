import { describe, expect, test } from "vitest";
import { ACCOUNT_NAV, activeNavPath, visibleSections } from "./navigation";

describe("visibleSections", () => {
  test("a regular session sees Catalog and Registries only — the empty Administration section is dropped", () => {
    const sections = visibleSections(false);
    expect(sections.map((s) => s.label)).toEqual(["appShell.section.catalog", "appShell.section.registries"]);
    expect(sections.flatMap((s) => s.items.map((l) => l.to))).not.toContain("/users");
  });

  test("an admin session gets the Administration section with both leaves", () => {
    const admin = visibleSections(true).find((s) => s.label === "appShell.section.administration");
    expect(admin?.items.map((l) => l.to)).toEqual(["/users", "/feature-flags"]);
  });

  test("the account leaves never sit in a section", () => {
    const sectionPaths = visibleSections(true).flatMap((s) => s.items.map((l) => l.to));
    for (const leaf of ACCOUNT_NAV) expect(sectionPaths).not.toContain(leaf.to);
    expect(ACCOUNT_NAV.map((l) => l.to)).toEqual(["/change-password", "/changelog"]);
  });
});

describe("activeNavPath", () => {
  const leaves = visibleSections(true).flatMap((s) => s.items);

  test("resolves the longest matching prefix", () => {
    expect(activeNavPath("/files/3/edit", leaves)).toBe("/files");
    expect(activeNavPath("/users/new", leaves)).toBe("/users");
    expect(activeNavPath("/labels", leaves)).toBe("/labels");
  });

  test("the root matches only exactly", () => {
    expect(activeNavPath("/", leaves)).toBe("/");
    expect(activeNavPath("/nowhere", leaves)).toBeNull();
  });
});
