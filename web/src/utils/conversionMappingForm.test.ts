import { describe, expect, test } from "vitest";
import i18n from "../i18n";
import { conversionMappingResponse } from "../test/fixtures";
import {
  conversionMappingFormValidation,
  emptyConversionMappingForm,
  fromConversionMappingResponse,
  pairCount,
  toConversionMappingRequest,
} from "./conversionMappingForm";

const t = i18n.t.bind(i18n);
const validation = conversionMappingFormValidation(t);

describe("conversionMappingFormValidation", () => {
  test("a blank or over-long name is refused, a normal one accepted", () => {
    expect(validation.name("   ")).toMatch(/enter a name/i);
    expect(validation.name("x".repeat(101))).toMatch(/at most 100/i);
    expect(validation.name("Default")).toBeNull();
  });

  test("the document must be non-blank JSON that is an object", () => {
    expect(validation.document("")).toMatch(/enter the mapping document/i);
    expect(validation.document("{ nope")).toMatch(/not valid JSON/i);
    expect(validation.document("[1]")).toMatch(/must be a JSON object/i);
    expect(validation.document("null")).toMatch(/must be a JSON object/i);
    expect(validation.document('{"pairs": []}')).toBeNull();
  });

  test("the starter document is accepted as is", () => {
    expect(validation.document(emptyConversionMappingForm().document)).toBeNull();
  });
});

describe("request mapping", () => {
  test("toConversionMappingRequest trims the name and parses the document", () => {
    expect(toConversionMappingRequest({ name: "  Map  ", document: '{"schemaVersion":1,"pairs":[]}' })).toEqual({
      name: "Map",
      document: { schemaVersion: 1, pairs: [] },
    });
  });

  test("fromConversionMappingResponse round-trips through the request", () => {
    const mapping = conversionMappingResponse({ name: "Round", document: { schemaVersion: 1, pairs: [{ blueprint: "a" }] } });
    const request = toConversionMappingRequest(fromConversionMappingResponse(mapping));
    expect(request).toEqual({ name: "Round", document: mapping.document });
  });
});

describe("pairCount", () => {
  test("counts the pairs array and reads a malformed document as zero", () => {
    expect(pairCount(conversionMappingResponse({ document: { pairs: [{}, {}] } }))).toBe(2);
    expect(pairCount(conversionMappingResponse({ document: { pairs: "x" } }))).toBe(0);
    expect(pairCount(conversionMappingResponse({ document: {} }))).toBe(0);
  });
});
