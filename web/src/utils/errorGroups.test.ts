import { describe, expect, test } from "vitest";
import { countByClass, groupFindingsByFile, type ErrorFinding } from "./errorGroups";

const finding = (over: Partial<ErrorFinding>): ErrorFinding => ({
  fileId: 1,
  fileName: "svc-a",
  fileKind: "Component",
  fileNamespace: "default",
  field: "spec.owner",
  reference: "group:default/x",
  status: "MISSING",
  message: null,
  ...over,
});

describe("groupFindingsByFile", () => {
  test("one row per file in first-seen order, every finding kept in wire order", () => {
    const rows = groupFindingsByFile([
      finding({ fileId: 2, fileName: "b", field: "source", reference: "", status: "SOURCE_MISSING" }),
      finding({ fileId: 1, field: "spec.owner" }),
      finding({ fileId: 2, fileName: "b", field: "spec.system", status: "WRONG_KIND" }),
    ]);
    expect(rows.map((r) => r.fileName)).toEqual(["b", "svc-a"]);
    expect(rows[0].findings.map((f) => f.field)).toEqual(["source", "spec.system"]);
    expect(rows[1].findings).toHaveLength(1);
  });

  test("an empty report groups to nothing", () => {
    expect(groupFindingsByFile([])).toEqual([]);
  });
});

describe("countByClass", () => {
  test("folds statuses into their class and reports every class, zeros included", () => {
    const counts = countByClass([
      finding({ status: "MISSING" }),
      finding({ status: "WRONG_KIND" }),
      finding({ status: "SOURCE_MISSING" }),
    ]);
    expect(counts.references).toBe(2);
    expect(counts.source).toBe(1);
    expect(counts.structure).toBe(0);
    expect(Object.keys(counts)).toHaveLength(9);
  });
});
