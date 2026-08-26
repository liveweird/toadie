import { describe, expect, test } from "vitest";
import type { TFunction } from "i18next";
import {
  emptyEntryDraft,
  foldNamespaceValue,
  missingDefault,
  namespaceFormValidation,
  namespaceSaveErrorMessage,
  toFormValues,
  toUpdateBody,
  type NamespaceFormValues,
} from "./namespaceForm";
import { ApiError } from "../api/http";

// Key-echoing translator: assertions match on i18n keys, not rendered English.
const t = ((key: string) => key) as TFunction;

function values(
  ...entries: Array<{ id?: number; value: string; isDefault?: boolean }>
): NamespaceFormValues {
  return { entries: entries.map((e, i) => ({ key: `k${i}`, isDefault: false, ...e })) };
}

describe("namespaceForm", () => {
  test("toFormValues mints stable local keys and keeps server ids", () => {
    const form = toFormValues([
      { id: 7, value: "default", isDefault: true },
      { id: 9, value: "team-a", isDefault: false },
    ]);
    expect(form.entries.map((e) => e.id)).toEqual([7, 9]);
    expect(form.entries.map((e) => e.value)).toEqual(["default", "team-a"]);
    expect(form.entries.map((e) => e.isDefault)).toEqual([true, false]);
    const keys = form.entries.map((e) => e.key);
    expect(new Set(keys).size).toBe(2);
    expect(keys).not.toContain(emptyEntryDraft().key);
  });

  test("toUpdateBody strips keys, folds values, and preserves ids and flags in visible order", () => {
    const body = toUpdateBody(
      values({ id: 7, value: "  Team-B ", isDefault: true }, { value: "team-c" }),
    );
    expect(body).toEqual({
      items: [
        { id: 7, value: "team-b", isDefault: true },
        { id: undefined, value: "team-c", isDefault: false },
      ],
    });
    expect(JSON.parse(JSON.stringify(body))).toEqual({
      items: [{ id: 7, value: "team-b", isDefault: true }, { value: "team-c", isDefault: false }],
    });
  });

  test("missingDefault flags a non-empty document with no default and nothing else", () => {
    expect(missingDefault(values({ value: "a" }, { value: "b" }))).toBe(true);
    expect(missingDefault(values({ value: "a", isDefault: true }, { value: "b" }))).toBe(false);
    expect(missingDefault({ entries: [] })).toBe(false);
    expect(emptyEntryDraft().isDefault).toBe(false);
  });

  test("folding trims and lowercases — the server's stored form", () => {
    expect(foldNamespaceValue("  MiXeD-Case  ")).toBe("mixed-case");
  });

  test("validation mirrors the server rules", () => {
    const rule = namespaceFormValidation(t).entries.value;
    const doc = values({ value: "team-a" }, { value: " TEAM-A " }, { value: "ok" });
    expect(rule("team-a", doc, "entries.0.value")).toBeNull();
    expect(rule("", doc, "entries.0.value")).toBe("namespaces.valueRequired");
    expect(rule("under_score", doc, "entries.0.value")).toBe("namespaces.valueInvalid");
    expect(rule("double--dash", doc, "entries.0.value")).toBe("namespaces.valueInvalid");
    expect(rule("a".repeat(64), doc, "entries.0.value")).toBe("namespaces.valueInvalid");
    // The duplicate flag lands on the LATER row (folded comparison) — the first stays valid.
    expect(rule(" TEAM-A ", doc, "entries.1.value")).toBe("namespaces.valueDuplicate");
  });

  test("save errors map to the fixed vocabulary", () => {
    const problem = { title: "x", status: 0 };
    expect(namespaceSaveErrorMessage(new ApiError(403, problem), t)).toBe("namespaces.error.permission");
    expect(namespaceSaveErrorMessage(new ApiError(409, problem), t)).toBe("namespaces.error.conflict");
    expect(namespaceSaveErrorMessage(new ApiError(400, problem), t)).toBe("namespaces.error.validation");
    expect(namespaceSaveErrorMessage(new ApiError(500, problem), t)).toBe("namespaces.error.saveFailedStatus");
    expect(namespaceSaveErrorMessage(new Error("boom"), t)).toBe("namespaces.error.saveFailed");
  });
});
