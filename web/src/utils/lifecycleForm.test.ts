import { describe, expect, test } from "vitest";
import type { TFunction } from "i18next";
import {
  emptyLifecycleDraft,
  lifecycleFormValidation,
  lifecycleSaveErrorMessage,
  toLifecycleFormValues,
  toLifecycleUpdateBody,
  type LifecycleFormValues,
} from "./lifecycleForm";
import { ApiError } from "../api/http";

// Key-echoing translator: assertions match on i18n keys, not rendered English.
const t = ((key: string) => key) as TFunction;

function values(...entries: Array<{ id?: number; value: string }>): LifecycleFormValues {
  return { entries: entries.map((e, i) => ({ key: `k${i}`, id: e.id, value: e.value })) };
}

describe("lifecycleForm", () => {
  test("drafts round-trip the dictionary and the body folds values without default flags", () => {
    const loaded = toLifecycleFormValues([
      { id: 1, value: "experimental", isDefault: false },
      { id: 2, value: "production", isDefault: false },
    ]);
    expect(loaded.entries.map((e) => ({ id: e.id, value: e.value }))).toEqual([
      { id: 1, value: "experimental" },
      { id: 2, value: "production" },
    ]);
    // Local keys are unique (React list identity across reorders).
    expect(new Set(loaded.entries.map((e) => e.key)).size).toBe(2);

    const body = toLifecycleUpdateBody(values({ id: 1, value: "  Production " }, { value: "sunset" }));
    expect(body).toEqual({
      items: [
        { id: 1, value: "production", isDefault: false },
        { id: undefined, value: "sunset", isDefault: false },
      ],
    });
    expect(emptyLifecycleDraft().value).toBe("");
  });

  test("validation mirrors the server's shared dictionary rules", () => {
    const rules = lifecycleFormValidation(t).entries.value;
    const doc = values({ value: "production" }, { value: "Production" }, { value: "" });
    expect(rules("production", doc, "entries.0.value")).toBeNull();
    // The duplicate lands on the LATER row (case-folded).
    expect(rules("Production", doc, "entries.1.value")).toBe("lifecycles.valueDuplicate");
    expect(rules("", doc, "entries.2.value")).toBe("lifecycles.valueRequired");
    expect(rules("Bad Value", doc, "entries.2.value")).toBe("lifecycles.valueInvalid");
    expect(rules("x".repeat(64), doc, "entries.2.value")).toBe("lifecycles.valueInvalid");
  });

  test("save errors map to the fixed vocabulary", () => {
    const problem = { title: "x", status: 0 };
    expect(lifecycleSaveErrorMessage(new ApiError(403, problem), t)).toBe("lifecycles.error.permission");
    expect(lifecycleSaveErrorMessage(new ApiError(409, problem), t)).toBe("lifecycles.error.conflict");
    expect(lifecycleSaveErrorMessage(new ApiError(400, problem), t)).toBe("lifecycles.error.validation");
    expect(lifecycleSaveErrorMessage(new ApiError(500, problem), t)).toBe("lifecycles.error.saveFailedStatus");
    expect(lifecycleSaveErrorMessage(new Error("boom"), t)).toBe("lifecycles.error.saveFailed");
  });
});
