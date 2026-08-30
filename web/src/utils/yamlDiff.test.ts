import { describe, expect, test } from "vitest";
import { diffLines } from "./yamlDiff";

describe("diffLines", () => {
  test("identical inputs are all same-lines", () => {
    expect(diffLines("a\nb", "a\nb")).toEqual([
      { kind: "same", text: "a" },
      { kind: "same", text: "b" },
    ]);
  });

  test("a changed line is a removed/added pair at the divergence", () => {
    expect(diffLines("a\nold\nc", "a\nnew\nc")).toEqual([
      { kind: "same", text: "a" },
      { kind: "removed", text: "old" },
      { kind: "added", text: "new" },
      { kind: "same", text: "c" },
    ]);
  });

  test("pure additions and removals keep the common context", () => {
    expect(diffLines("a\nc", "a\nb\nc")).toEqual([
      { kind: "same", text: "a" },
      { kind: "added", text: "b" },
      { kind: "same", text: "c" },
    ]);
    expect(diffLines("a\nb\nc", "a\nc")).toEqual([
      { kind: "same", text: "a" },
      { kind: "removed", text: "b" },
      { kind: "same", text: "c" },
    ]);
  });

  test("wholly different inputs remove everything and add everything", () => {
    expect(diffLines("x", "y\nz")).toEqual([
      { kind: "removed", text: "x" },
      { kind: "added", text: "y" },
      { kind: "added", text: "z" },
    ]);
  });

  test("empty sides behave", () => {
    expect(diffLines("", "")).toEqual([{ kind: "same", text: "" }]);
    expect(diffLines("a", "")).toEqual([
      { kind: "removed", text: "a" },
      { kind: "added", text: "" },
    ]);
  });
});
