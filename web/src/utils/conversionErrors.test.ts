import { describe, expect, test } from "vitest";
import i18n from "../i18n";
import { ApiError } from "../api/http";
import { conversionErrorMessage, ImportPhaseError, importFlowError } from "./conversionErrors";

const t = i18n.t.bind(i18n);

describe("conversion error vocabulary", () => {
  test("the 400 wording differs per direction", () => {
    expect(conversionErrorMessage(new ApiError(400, {}), t, "export")).toMatch(/entity query is invalid/);
    expect(conversionErrorMessage(new ApiError(400, {}), t, "import")).toMatch(/at most 200/);
  });

  test("a convert-phase failure is a conversion failure, an import-phase one the import's own wording", () => {
    expect(importFlowError(new ImportPhaseError("convert", new ApiError(409, {})), t)).toEqual({
      title: "Conversion failed",
      message: "This mapping has blocking findings and cannot convert until they are fixed.",
    });
    expect(importFlowError(new ImportPhaseError("import", new ApiError(500, {})), t)).toEqual({
      title: "Import failed",
      message: "Import failed (500)",
    });
    expect(importFlowError(new Error("net"), t).title).toBe("Conversion failed");
  });
});
