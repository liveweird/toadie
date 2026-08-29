import { describe, expect, test } from "vitest";
import type { TFunction } from "i18next";
import {
  emptyEntityTypesForm,
  entityTypesFormValidation,
  entityTypesSaveErrorMessage,
  toEntityTypesBody,
  toEntityTypesFormValues,
  TYPE_BEARING_KINDS,
} from "./entityTypeForm";
import { ApiError } from "../api/http";

// Key-echoing translator: assertions match on i18n keys, not rendered English.
const t = ((key: string) => key) as TFunction;

describe("entityTypeForm", () => {
  test("the type-bearing kinds are every kind but User", () => {
    expect(TYPE_BEARING_KINDS).toEqual(["Component", "API", "System", "Domain", "Resource", "Group"]);
  });

  test("form values round-trip a dictionary and the body trims", () => {
    const dictionary = { id: 3, kind: "Component", types: ["service", "website"] };
    expect(toEntityTypesFormValues(dictionary)).toEqual({ kind: "Component", types: ["service", "website"] });
    expect(toEntityTypesBody({ kind: "Component", types: [" service "] })).toEqual({
      kind: "Component",
      types: ["service"],
    });
    expect(emptyEntityTypesForm()).toEqual({ kind: "", types: [] });
  });

  test("validation mirrors the server rules", () => {
    const rules = entityTypesFormValidation(t);
    expect(rules.kind("Component")).toBeNull();
    expect(rules.kind("")).toBe("types.validation.kind");
    expect(rules.kind("User")).toBe("types.validation.kind");
    expect(rules.kind("Location")).toBe("types.validation.kind");

    expect(rules.types(["service", "feature-set"])).toBeNull();
    expect(rules.types([])).toBe("types.validation.typesRequired");
    expect(rules.types(["has space"])).toBe("types.validation.type");
    expect(rules.types(["x".repeat(64)])).toBe("types.validation.type");
    expect(rules.types(["dup", "DUP"])).toBe("types.validation.typesDuplicate");
    expect(rules.types(Array.from({ length: 101 }, (_, i) => `v${i}`))).toBe("types.validation.typesCount");
  });

  test("save errors map to the fixed vocabulary", () => {
    const problem = { title: "x", status: 0 };
    expect(entityTypesSaveErrorMessage(new ApiError(409, problem), t)).toBe("types.saveConflict");
    expect(entityTypesSaveErrorMessage(new ApiError(400, problem), t)).toBe("types.saveInvalid");
    expect(entityTypesSaveErrorMessage(new ApiError(404, problem), t)).toBe("types.saveGone");
    expect(entityTypesSaveErrorMessage(new ApiError(500, problem), t)).toBe("common.error.actionFailedStatus");
    expect(entityTypesSaveErrorMessage(new Error("boom"), t)).toBe("common.error.actionFailed");
  });
});
