import { describe, expect, it } from "vitest";
import enEntityErrors from "./en/entityErrors.json";

// Guards the Port-world Errors report's localized vocabulary (v2.5.0): every code the two
// generated unions in schema.ts can produce — `EntityFindingCode` (entity/blueprint findings)
// and `QueryDiagnostic`'s `code` (saved-query diagnostics) — must have BOTH a `code.<CODE>`
// label and an `explain.<CODE>` sentence in entityErrors.json (EN; PL parity is
// locales/parity.test.ts's job). Reads schema.ts's raw text via Vite's import.meta.glob (the
// unusedKeys.test.ts idiom) rather than importing the module, so this test needs no server
// running and never drifts from what `npm run gen:api` actually wrote.

const SCHEMA_MODULES = import.meta.glob<string>("../api/schema.ts", {
  eager: true,
  query: "?raw",
  import: "default",
});
const SCHEMA_TEXT = Object.values(SCHEMA_MODULES)[0] ?? "";

function codesFrom(pattern: RegExp): string[] {
  const match = pattern.exec(SCHEMA_TEXT);
  if (!match) throw new Error(`entityErrorsCodes.test.ts: pattern not found in schema.ts: ${pattern}`);
  return [...match[0].matchAll(/"([A-Z_]+)"/g)].map((m) => m[1]);
}

// `EntityFindingCode: "UNKNOWN_PROPERTY" | ... | "CALCULATION_QUARANTINED";` — one literal line.
const ENTITY_FINDING_CODES = codesFrom(/EntityFindingCode: "[^;]+;/);
// `code: "SYNTAX" | ... | "WORKSPACE_TOO_LARGE";` — QueryDiagnostic's own `code` field, the
// only `code: "SYNTAX"`-leading union in the file.
const QUERY_DIAGNOSTIC_CODES = codesFrom(/code: "SYNTAX"[^;]+;/);

const ALL_CODES = [...new Set([...ENTITY_FINDING_CODES, ...QUERY_DIAGNOSTIC_CODES])];

describe("entityErrors.json covers every EntityFindingCode and QueryDiagnostic code", () => {
  it("found a non-trivial set of codes in schema.ts (the extraction itself didn't silently break)", () => {
    expect(ENTITY_FINDING_CODES.length).toBeGreaterThan(20);
    expect(QUERY_DIAGNOSTIC_CODES.length).toBeGreaterThan(10);
  });

  it.each(ALL_CODES)("%s has a code label", (code) => {
    expect(enEntityErrors.code).toHaveProperty(code);
    expect(typeof (enEntityErrors.code as Record<string, string>)[code]).toBe("string");
    expect((enEntityErrors.code as Record<string, string>)[code].length).toBeGreaterThan(0);
  });

  it.each(ALL_CODES)("%s has an explanation", (code) => {
    expect(enEntityErrors.explain).toHaveProperty(code);
    expect(typeof (enEntityErrors.explain as Record<string, string>)[code]).toBe("string");
    expect((enEntityErrors.explain as Record<string, string>)[code].length).toBeGreaterThan(0);
  });
});
