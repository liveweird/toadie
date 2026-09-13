import { describe, expect, test } from "vitest";
import { ApiError } from "../api/http";
import type { EntityQueryDiagnostic } from "../api/entities";
import { queryProblemDiagnostics, toLintDiagnostics } from "./queryDiagnostics";

function diag(overrides: Partial<EntityQueryDiagnostic> & { code: EntityQueryDiagnostic["code"] }): EntityQueryDiagnostic {
  return { message: "problem", ...overrides };
}

describe("queryProblemDiagnostics", () => {
  test("reads the diagnostics list off a 400 problem body", () => {
    const diagnostics = [diag({ code: "UNKNOWN_LABEL", message: "unknown label 'srv'" })];
    const err = new ApiError(400, { title: "Bad Request", status: 400, diagnostics });
    expect(queryProblemDiagnostics(err)).toEqual(diagnostics);
  });

  test("answers empty for a 400 body without a diagnostics member", () => {
    const err = new ApiError(400, { title: "Bad Request", status: 400 });
    expect(queryProblemDiagnostics(err)).toEqual([]);
  });

  test("answers empty for a non-400 ApiError", () => {
    const err = new ApiError(500, { title: "Server error", status: 500, diagnostics: [diag({ code: "SYNTAX" })] });
    expect(queryProblemDiagnostics(err)).toEqual([]);
  });

  test("answers empty for a null body", () => {
    const err = new ApiError(400, null);
    expect(queryProblemDiagnostics(err)).toEqual([]);
  });

  test("answers empty for a non-ApiError error", () => {
    expect(queryProblemDiagnostics(new Error("network"))).toEqual([]);
    expect(queryProblemDiagnostics("nope")).toEqual([]);
  });
});

describe("toLintDiagnostics", () => {
  test("maps a single-line positioned diagnostic to a character range", () => {
    const doc = "MATCH (a:srv) RETURN a";
    const result = toLintDiagnostics([diag({ code: "UNKNOWN_LABEL", message: "unknown label 'srv'", line: 1, column: 10, endLine: 1, endColumn: 13 })], doc);
    expect(result).toEqual([{ from: 9, to: 12, severity: "error", message: "unknown label 'srv'" }]);
  });

  test("accounts for earlier lines when computing the offset", () => {
    const doc = "MATCH (a:service)\nWHERE a.srv = 1";
    const result = toLintDiagnostics(
      [diag({ code: "UNKNOWN_PROPERTY", message: "unknown property 'srv'", line: 2, column: 9, endLine: 2, endColumn: 12 })],
      doc,
    );
    const lineTwoStart = doc.indexOf("\n") + 1;
    expect(result[0]).toEqual({ from: lineTwoStart + 8, to: lineTwoStart + 11, severity: "error", message: "unknown property 'srv'" });
  });

  test("a positioned diagnostic without an end spans one character", () => {
    const doc = "MATCH (a";
    const result = toLintDiagnostics([diag({ code: "SYNTAX", message: "unexpected end of input", line: 1, column: 8 })], doc);
    expect(result).toEqual([{ from: 7, to: 8, severity: "error", message: "unexpected end of input" }]);
  });

  test("a positionless diagnostic spans the whole document", () => {
    const doc = "MATCH (a:service)-[:depends_on*]->(b) RETURN a, b";
    const result = toLintDiagnostics([diag({ code: "DEADLINE_EXCEEDED", message: "query exceeded its evaluation budget" })], doc);
    expect(result).toEqual([{ from: 0, to: doc.length, severity: "error", message: "query exceeded its evaluation budget" }]);
  });

  test("a positionless diagnostic on an empty document spans 0..0", () => {
    const result = toLintDiagnostics([diag({ code: "BINDING_LIMIT", message: "too many bindings" })], "");
    expect(result).toEqual([{ from: 0, to: 0, severity: "error", message: "too many bindings" }]);
  });

  test("clamps an out-of-range position to the document length", () => {
    const doc = "MATCH (a";
    const result = toLintDiagnostics([diag({ code: "SYNTAX", message: "stale position", line: 5, column: 20 })], doc);
    expect(result[0].from).toBeLessThanOrEqual(doc.length);
    expect(result[0].to).toBeLessThanOrEqual(doc.length);
  });

  test("appends the suggestion when present", () => {
    const doc = "MATCH (a:srv)";
    const result = toLintDiagnostics(
      [diag({ code: "UNKNOWN_LABEL", message: "unknown label 'srv'", line: 1, column: 10, endLine: 1, endColumn: 13, suggestion: "service" })],
      doc,
    );
    expect(result[0].message).toBe("unknown label 'srv' (Did you mean `service`?)");
  });

  test("maps several diagnostics in order", () => {
    const doc = "MATCH (a:srv) RETURN b";
    const result = toLintDiagnostics(
      [
        diag({ code: "UNKNOWN_LABEL", message: "unknown label 'srv'", line: 1, column: 10, endLine: 1, endColumn: 13 }),
        diag({ code: "UNKNOWN_VARIABLE", message: "unknown variable 'b'", line: 1, column: 22, endLine: 1, endColumn: 23 }),
      ],
      doc,
    );
    expect(result).toHaveLength(2);
    expect(result[1].message).toBe("unknown variable 'b'");
  });
});
