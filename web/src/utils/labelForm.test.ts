import { describe, expect, test } from "vitest";
import type { TFunction } from "i18next";
import {
  emptyLabelForm,
  labelFormValidation,
  labelSaveErrorMessage,
  toLabelBody,
  toLabelFormValues,
} from "./labelForm";
import { ApiError } from "../api/http";

// Key-echoing translator: assertions match on i18n keys, not rendered English.
const t = ((key: string) => key) as TFunction;

describe("labelForm", () => {
  test("form values round-trip a label and the body trims", () => {
    const label = { id: 3, key: "example.com/tier", values: ["backend"], kinds: ["Component", "API"] };
    expect(toLabelFormValues(label)).toEqual({
      key: "example.com/tier",
      values: ["backend"],
      kinds: ["Component", "API"],
    });
    expect(toLabelBody({ key: "  tier  ", values: [" backend "], kinds: ["Component"] })).toEqual({
      key: "tier",
      values: ["backend"],
      kinds: ["Component"],
    });
    expect(emptyLabelForm()).toEqual({ key: "", values: [], kinds: [] });
  });

  test("validation mirrors the server rules", () => {
    const rules = labelFormValidation(t);
    expect(rules.key("tier")).toBeNull();
    expect(rules.key("example.com/tier")).toBeNull();
    expect(rules.key("")).toBe("labels.validation.key");
    expect(rules.key("has space")).toBe("labels.validation.key");
    expect(rules.key("UPPER.com/tier")).toBe("labels.validation.key");
    expect(rules.key("a/b/c")).toBe("labels.validation.key");

    expect(rules.values(["backend", "tier-1"])).toBeNull();
    expect(rules.values([])).toBe("labels.validation.valuesRequired");
    expect(rules.values(["has space"])).toBe("labels.validation.value");
    expect(rules.values(["dup", "DUP"])).toBe("labels.validation.valuesDuplicate");
    expect(rules.values(Array.from({ length: 101 }, (_, i) => `v${i}`))).toBe("labels.validation.valuesCount");

    expect(rules.kinds(["Component"])).toBeNull();
    expect(rules.kinds([])).toBe("labels.validation.kindsRequired");
  });

  test("save errors map to the fixed vocabulary", () => {
    const problem = { title: "x", status: 0 };
    expect(labelSaveErrorMessage(new ApiError(409, problem), t)).toBe("labels.saveConflict");
    expect(labelSaveErrorMessage(new ApiError(400, problem), t)).toBe("labels.saveInvalid");
    expect(labelSaveErrorMessage(new ApiError(404, problem), t)).toBe("labels.saveGone");
    expect(labelSaveErrorMessage(new ApiError(500, problem), t)).toBe("common.error.actionFailedStatus");
    expect(labelSaveErrorMessage(new Error("boom"), t)).toBe("common.error.actionFailed");
  });
});
