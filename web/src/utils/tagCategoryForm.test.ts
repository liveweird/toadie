import { describe, expect, test } from "vitest";
import type { TFunction } from "i18next";
import {
  emptyTagCategoryForm,
  tagCategoryFormValidation,
  tagCategorySaveErrorMessage,
  toTagCategoryBody,
  toTagCategoryFormValues,
} from "./tagCategoryForm";
import { ApiError } from "../api/http";

// Key-echoing translator: assertions match on i18n keys, not rendered English.
const t = ((key: string) => key) as TFunction;

describe("tagCategoryForm", () => {
  test("form values round-trip a category and the body trims", () => {
    const category = { id: 3, name: "Languages", tags: ["java", "c++"], kinds: ["Component", "API"] };
    expect(toTagCategoryFormValues(category)).toEqual({
      name: "Languages",
      tags: ["java", "c++"],
      kinds: ["Component", "API"],
    });
    expect(toTagCategoryBody({ name: "  Languages  ", tags: [" java "], kinds: ["Component"] })).toEqual({
      name: "Languages",
      tags: ["java"],
      kinds: ["Component"],
    });
    expect(emptyTagCategoryForm()).toEqual({ name: "", tags: [], kinds: [] });
  });

  test("validation mirrors the server rules", () => {
    const rules = tagCategoryFormValidation(t);
    expect(rules.name("Languages")).toBeNull();
    expect(rules.name("")).toBe("tags.validation.name");
    expect(rules.name("x".repeat(64))).toBe("tags.validation.name");

    expect(rules.tags(["java", "c++", "csharp:v2"])).toBeNull();
    expect(rules.tags([])).toBe("tags.validation.tagsRequired");
    expect(rules.tags(["Uppercase"])).toBe("tags.validation.tag");
    expect(rules.tags(["has space"])).toBe("tags.validation.tag");
    expect(rules.tags(["dup", "dup"])).toBe("tags.validation.tagsDuplicate");
    expect(rules.tags(Array.from({ length: 101 }, (_, i) => `v${i}`))).toBe("tags.validation.tagsCount");

    expect(rules.kinds(["Component"])).toBeNull();
    expect(rules.kinds([])).toBe("tags.validation.kindsRequired");
  });

  test("save errors map to the fixed vocabulary", () => {
    const problem = { title: "x", status: 0 };
    expect(tagCategorySaveErrorMessage(new ApiError(409, problem), t)).toBe("tags.saveConflict");
    expect(tagCategorySaveErrorMessage(new ApiError(400, problem), t)).toBe("tags.saveInvalid");
    expect(tagCategorySaveErrorMessage(new ApiError(404, problem), t)).toBe("tags.saveGone");
    expect(tagCategorySaveErrorMessage(new ApiError(500, problem), t)).toBe("common.error.actionFailedStatus");
    expect(tagCategorySaveErrorMessage(new Error("boom"), t)).toBe("common.error.actionFailed");
  });
});
