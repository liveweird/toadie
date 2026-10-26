import { describe, expect, test } from "vitest";
import { countRows, exportDisplayRows, importDisplayRows } from "./conversionRows";

describe("conversion row mappers", () => {
  test("exportDisplayRows reads Port -> Backstage and links the merged catalog file", () => {
    const [converted, skipped] = exportDisplayRows([
      { index: 0, status: "CONVERTED", blueprint: "svc", identifier: "a", entityId: 1, kind: "Component", namespace: "default", name: "a", existingId: 5, findings: [], unmapped: ["team"] },
      { index: 1, status: "SKIPPED", blueprint: "w", identifier: "x", entityId: 2, findings: [], unmapped: [] },
    ]);
    expect(converted).toMatchObject({ from: "svc / a", to: "Component:default/a", existingPath: "/files/5/edit", unmapped: ["team"] });
    expect(skipped).toMatchObject({ from: "w / x", to: null, existingPath: null });
  });

  test("importDisplayRows reads Backstage -> Port and links the merged entity", () => {
    const [converted, skipped] = importDisplayRows([
      { index: 0, status: "CONVERTED_WITH_FINDINGS", kind: "Component", namespace: "default", name: "a", blueprint: "svc", identifier: "a", existingId: 9, findings: [], unmapped: [] },
      { index: 1, status: "SKIPPED", kind: "API", namespace: "default", name: "b", findings: [], unmapped: [] },
    ]);
    expect(converted).toMatchObject({ from: "Component:default/a", to: "svc / a", existingPath: "/entities/9/edit" });
    expect(skipped).toMatchObject({ from: "API:default/b", to: null, existingPath: null });
  });

  test("countRows buckets every status", () => {
    expect(
      countRows([
        { status: "CONVERTED" },
        { status: "CONVERTED" },
        { status: "CONVERTED_WITH_FINDINGS" },
        { status: "SKIPPED" },
        { status: "INVALID" },
      ]),
    ).toEqual({ total: 5, converted: 2, withFindings: 1, skipped: 1, invalid: 1 });
  });
});
