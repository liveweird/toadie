import { describe, expect, test } from "vitest";
import type { TFunction } from "i18next";
import type { EntityFinding } from "../api/entities";
import { dedupeEntityFindings, entityFindingProps, indexEntityFindings, NO_ENTITY_FINDINGS } from "./entityFieldFindings";

const t = ((key: string) => key) as TFunction;

function finding(overrides: Partial<EntityFinding> = {}): EntityFinding {
  return { code: "REQUIRED_MISSING", field: "properties.name", message: "Required", ...overrides };
}

describe("indexEntityFindings", () => {
  test("groups findings by their wire field, several per field", () => {
    const a = finding({ field: "team" });
    const b = finding({ field: "team", code: "TEAM_NOT_ALLOWED", message: "Not allowed" });
    const c = finding({ field: "relations.owner" });
    const index = indexEntityFindings([a, b, c]);
    expect(index.forField("team")).toEqual([a, b]);
    expect(index.forField("relations.owner")).toEqual([c]);
  });

  test("an unknown field looks up empty", () => {
    expect(indexEntityFindings([finding()]).forField("nope")).toEqual([]);
  });

  test("NO_ENTITY_FINDINGS is the stable empty lookup", () => {
    expect(NO_ENTITY_FINDINGS.forField("team")).toEqual([]);
  });
});

describe("entityFindingProps", () => {
  test("no findings -> no props", () => {
    expect(entityFindingProps([], t)).toEqual({});
  });

  test("a hard error suppresses the finding regardless of findings", () => {
    expect(entityFindingProps([finding()], t, { hardError: "Required" })).toEqual({});
  });

  test("TEAM_TARGET_MISSING is localized", () => {
    const props = entityFindingProps([finding({ code: "TEAM_TARGET_MISSING", field: "team", message: "raw" })], t);
    expect(props).toMatchObject({ error: "entities.finding.teamTargetMissing" });
  });

  test("every other code shows the server's own message verbatim", () => {
    const props = entityFindingProps(
      [finding({ code: "TEAM_NOT_ALLOWED", field: "team", message: "Team not allowed here" })],
      t,
    );
    expect(props).toMatchObject({ error: "Team not allowed here" });
  });

  test("carries the shared finding classNames", () => {
    const props = entityFindingProps([finding()], t) as { classNames: { input: string; error: string } };
    expect(props.classNames.input).toBeTruthy();
    expect(props.classNames.error).toBeTruthy();
  });
});

describe("dedupeEntityFindings", () => {
  test("merges stale and saved, deduping by field+code, first occurrence wins", () => {
    const staleFinding = finding({ field: "team", code: "TEAM_TARGET_MISSING", message: "stale" });
    const savedSame = finding({ field: "team", code: "TEAM_TARGET_MISSING", message: "saved" });
    const savedOther = finding({ field: "properties.name", code: "REQUIRED_MISSING" });
    expect(dedupeEntityFindings([staleFinding], [savedSame, savedOther])).toEqual([staleFinding, savedOther]);
  });

  test("both empty answers empty", () => {
    expect(dedupeEntityFindings([], [])).toEqual([]);
  });

  test("no overlap keeps every entry", () => {
    const a = finding({ field: "team" });
    const b = finding({ field: "properties.name", code: "REQUIRED_MISSING" });
    expect(dedupeEntityFindings([a], [b])).toEqual([a, b]);
  });
});
