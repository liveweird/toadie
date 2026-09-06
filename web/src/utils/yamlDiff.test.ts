import { describe, expect, test, vi } from "vitest";
import type { CatalogFileRequest } from "../api/catalogFiles";
import { parseCatalogYaml } from "./catalogImport";
import { catalogInfoYaml } from "./catalogYaml";
import {
  diffLines,
  YAML_DIFF_CHARACTER_LIMIT,
  YAML_DIFF_LINE_LIMIT,
  YAML_DIFF_MATRIX_CELL_LIMIT,
  type YamlDiff,
} from "./yamlDiff";

function lines(count: number, value = "x") {
  return Array.from({ length: count }, () => value).join("\n");
}

function detailed(before: string, after: string) {
  const result = diffLines(before, after);
  expect(result.kind).toBe("detailed");
  if (result.kind !== "detailed") throw new Error("expected a detailed diff");
  return result.lines;
}

function expectReconstructs(before: string, after: string, diff: YamlDiff) {
  expect(diff.kind).toBe("detailed");
  if (diff.kind !== "detailed") return;
  expect(
    diff.lines
      .filter((line) => line.kind !== "added")
      .map((line) => line.text)
      .join("\n"),
  ).toBe(before);
  expect(
    diff.lines
      .filter((line) => line.kind !== "removed")
      .map((line) => line.text)
      .join("\n"),
  ).toBe(after);
}

describe("diffLines", () => {
  test("keeps the established small-diff ordering and empty-line semantics", () => {
    expect(detailed("a\nold\nc", "a\nnew\nc")).toEqual([
      { kind: "same", text: "a" },
      { kind: "removed", text: "old" },
      { kind: "added", text: "new" },
      { kind: "same", text: "c" },
    ]);
    expect(detailed("a\nc", "a\nb\nc")).toEqual([
      { kind: "same", text: "a" },
      { kind: "added", text: "b" },
      { kind: "same", text: "c" },
    ]);
    expect(detailed("a\nb\nc", "a\nc")).toEqual([
      { kind: "same", text: "a" },
      { kind: "removed", text: "b" },
      { kind: "same", text: "c" },
    ]);
    expect(detailed("", "")).toEqual([{ kind: "same", text: "" }]);
    expect(detailed("a", "")).toEqual([
      { kind: "removed", text: "a" },
      { kind: "added", text: "" },
    ]);
  });

  test("every detailed result reconstructs both inputs, including trailing newlines", () => {
    for (const [before, after] of [
      ["a\nb", "a\nb"],
      ["x", "y\nz"],
      ["a\nb\n", "a\nc\n"],
      ["same\nsame\nold", "same\nnew\nsame"],
    ]) {
      expectReconstructs(before, after, diffLines(before, after));
    }
  });

  test("allows the character ceiling and falls back above it before splitting or allocating", () => {
    const atLimit = "x".repeat(YAML_DIFF_CHARACTER_LIMIT - 1);
    expect(diffLines(atLimit, "x").kind).toBe("detailed");

    const before = "x".repeat(YAML_DIFF_CHARACTER_LIMIT);
    const split = vi.spyOn(String.prototype, "split");
    const allocation = vi.spyOn(Array, "from");
    const result = diffLines(before, "y");
    const splitCalls = split.mock.calls.length;
    const allocationCalls = allocation.mock.calls.length;
    split.mockRestore();
    allocation.mockRestore();

    expect(result).toEqual({ kind: "fallback", before, after: "y" });
    expect(splitCalls).toBe(0);
    expect(allocationCalls).toBe(0);
  });

  test("allows the line ceiling and falls back above it, including asymmetric inputs", () => {
    expect(diffLines(lines(YAML_DIFF_LINE_LIMIT - 1), "").kind).toBe("detailed");
    const before = lines(YAML_DIFF_LINE_LIMIT);
    expect(diffLines(before, "")).toEqual({ kind: "fallback", before, after: "" });
  });

  test("allows the exact matrix ceiling and falls back above it for short repeated lines", () => {
    // 499 lines on each side produce (499 + 1)² sentinel-inclusive cells.
    expect(YAML_DIFF_MATRIX_CELL_LIMIT).toBe(500 * 500);
    expect(diffLines(lines(499, "same"), lines(499, "same")).kind).toBe("detailed");

    const before = lines(500, "same");
    const after = lines(499, "other");
    const allocation = vi.spyOn(Array, "from");
    const result = diffLines(before, after);
    const allocationCalls = allocation.mock.calls.length;
    allocation.mockRestore();

    expect(result).toEqual({ kind: "fallback", before, after });
    expect(allocationCalls).toBe(0);
  });

  test("a large valid API descriptor round-trips and uses the bounded fallback", () => {
    const definition = Array.from(
      { length: 600 },
      (_, index) => `path-${index}: ${"x".repeat(80)}`,
    ).join("\n");
    expect(definition.length).toBeLessThanOrEqual(100_000);
    const api = (suffix: string): CatalogFileRequest => ({
      kind: "API",
      metadata: { name: "large-api", namespace: "default" },
      spec: {
        type: "openapi",
        lifecycle: "production",
        owner: "group:default/platform",
        definition: `${definition}\n${suffix}`,
      },
    });
    const before = catalogInfoYaml(api("version: old"));
    const after = catalogInfoYaml(api("version: new"));

    const parsed = parseCatalogYaml(after);
    expect(parsed.errors).toEqual([]);
    expect(parsed.documents[0].spec.definition).toBe(`${definition}\nversion: new`);
    expect(diffLines(before, after)).toEqual({ kind: "fallback", before, after });
  });
});
