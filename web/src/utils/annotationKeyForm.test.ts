import { describe, expect, test } from "vitest";
import type { TFunction } from "i18next";
import {
  annotationKeyFormValidation,
  annotationKeySaveErrorMessage,
  emptyAnnotationKeyForm,
  toAnnotationKeyBody,
  toAnnotationKeyFormValues,
} from "./annotationKeyForm";
import { ApiError } from "../api/http";

// Key-echoing translator: assertions match on i18n keys, not rendered English.
const t = ((key: string) => key) as TFunction;

describe("annotationKeyForm", () => {
  test("form values round-trip a row and the body trims", () => {
    const row = { id: 3, key: "github.com/project-slug", kinds: ["Component", "API"] };
    expect(toAnnotationKeyFormValues(row)).toEqual({
      key: "github.com/project-slug",
      kinds: ["Component", "API"],
    });
    expect(toAnnotationKeyBody({ key: "  team  ", kinds: ["Group"] })).toEqual({
      key: "team",
      kinds: ["Group"],
    });
    expect(emptyAnnotationKeyForm()).toEqual({ key: "", kinds: [] });
  });

  test("validation mirrors the server rules", () => {
    const rules = annotationKeyFormValidation(t);
    expect(rules.key("github.com/project-slug")).toBeNull();
    expect(rules.key("plain-name")).toBeNull();
    expect(rules.key("")).toBe("annotations.validation.key");
    expect(rules.key("has space")).toBe("annotations.validation.key");
    expect(rules.key("UPPER.example.com/name")).toBe("annotations.validation.key");

    expect(rules.kinds(["Component"])).toBeNull();
    expect(rules.kinds([])).toBe("annotations.validation.kindsRequired");
  });

  test("save errors map to the fixed vocabulary", () => {
    const problem = { title: "x", status: 0 };
    expect(annotationKeySaveErrorMessage(new ApiError(409, problem), t)).toBe("annotations.saveConflict");
    expect(annotationKeySaveErrorMessage(new ApiError(403, problem), t)).toBe("annotations.saveForbidden");
    expect(annotationKeySaveErrorMessage(new ApiError(400, problem), t)).toBe("annotations.saveInvalid");
    expect(annotationKeySaveErrorMessage(new ApiError(404, problem), t)).toBe("annotations.saveGone");
    expect(annotationKeySaveErrorMessage(new ApiError(500, problem), t)).toBe("common.error.actionFailedStatus");
    expect(annotationKeySaveErrorMessage(new Error("boom"), t)).toBe("common.error.actionFailed");
  });
});
