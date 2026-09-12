import { describe, expect, test } from "vitest";
import type { TFunction } from "i18next";
import {
  emptyHierarchyDraft,
  hierarchiesFormValidation,
  hierarchiesSaveErrorMessage,
  toHierarchiesFormValues,
  toHierarchiesUpdateBody,
  type HierarchiesFormValues,
} from "./hierarchiesForm";
import { ApiError } from "../api/http";

// Key-echoing translator: assertions match on i18n keys, not rendered English.
const t = ((key: string) => key) as TFunction;

function values(...entries: Array<{ id?: number; value: string }>): HierarchiesFormValues {
  return { entries: entries.map((e, i) => ({ key: `k${i}`, id: e.id, value: e.value })) };
}

describe("hierarchiesForm", () => {
  test("drafts round-trip the dictionary and the body folds values without default flags", () => {
    const loaded = toHierarchiesFormValues([
      { id: 1, value: "composition", isDefault: false },
      { id: 2, value: "ownership", isDefault: false },
    ]);
    expect(loaded.entries.map((e) => ({ id: e.id, value: e.value }))).toEqual([
      { id: 1, value: "composition" },
      { id: 2, value: "ownership" },
    ]);
    // Local keys are unique (React list identity across reorders).
    expect(new Set(loaded.entries.map((e) => e.key)).size).toBe(2);

    const body = toHierarchiesUpdateBody(values({ id: 1, value: "  Ownership " }, { value: "cost-center" }));
    expect(body).toEqual({
      items: [
        { id: 1, value: "ownership", isDefault: false },
        { id: undefined, value: "cost-center", isDefault: false },
      ],
    });
    expect(emptyHierarchyDraft().value).toBe("");
  });

  test("validation mirrors the server's shared dictionary rules", () => {
    const rules = hierarchiesFormValidation(t).entries.value;
    const doc = values({ value: "ownership" }, { value: "Ownership" }, { value: "" });
    expect(rules("ownership", doc, "entries.0.value")).toBeNull();
    // The duplicate lands on the LATER row (case-folded).
    expect(rules("Ownership", doc, "entries.1.value")).toBe("hierarchies.valueDuplicate");
    expect(rules("", doc, "entries.2.value")).toBe("hierarchies.valueRequired");
    expect(rules("Bad Value", doc, "entries.2.value")).toBe("hierarchies.valueInvalid");
    expect(rules("x".repeat(64), doc, "entries.2.value")).toBe("hierarchies.valueInvalid");
  });

  test("save errors map to the fixed vocabulary", () => {
    const problem = { title: "x", status: 0 };
    expect(hierarchiesSaveErrorMessage(new ApiError(403, problem), t)).toBe("hierarchies.error.permission");
    expect(hierarchiesSaveErrorMessage(new ApiError(409, problem), t)).toBe("hierarchies.error.conflict");
    expect(hierarchiesSaveErrorMessage(new ApiError(400, problem), t)).toBe("hierarchies.error.validation");
    expect(hierarchiesSaveErrorMessage(new ApiError(500, problem), t)).toBe(
      "hierarchies.error.saveFailedStatus",
    );
    expect(hierarchiesSaveErrorMessage(new Error("boom"), t)).toBe("hierarchies.error.saveFailed");
  });
});
