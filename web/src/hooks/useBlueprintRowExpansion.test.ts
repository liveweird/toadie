import { describe, expect, test } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { useForm } from "@mantine/form";
import { useBlueprintRowExpansion } from "./useBlueprintRowExpansion";
import { rowDomId } from "../components/EditorRowList";
import {
  emptyBlueprintForm,
  emptyPropertyDraft,
  emptyRelationDraft,
  type BlueprintFormValues,
} from "../utils/blueprintForm";

function useHarness(initialValues: BlueprintFormValues) {
  const form = useForm<BlueprintFormValues>({ initialValues });
  const expansion = useBlueprintRowExpansion(form);
  return { form, expansion };
}

describe("useBlueprintRowExpansion", () => {
  test("seeds only the first row's id of every non-empty family", () => {
    const { result } = renderHook(() =>
      useHarness({
        ...emptyBlueprintForm(),
        properties: [{ ...emptyPropertyDraft(), key: "p0" }, { ...emptyPropertyDraft(), key: "p1" }],
        relations: [{ ...emptyRelationDraft(), key: "r0" }],
      }),
    );

    expect(result.current.expansion.isExpanded(rowDomId("properties", "p0"))).toBe(true);
    expect(result.current.expansion.isExpanded(rowDomId("properties", "p1"))).toBe(false);
    expect(result.current.expansion.isExpanded(rowDomId("relations", "r0"))).toBe(true);
    // mirrorProperties is empty — nothing to seed, and asking about it must not throw.
    expect(result.current.expansion.isExpanded(rowDomId("mirrorProperties", "anything"))).toBe(false);
  });

  test("re-seeds exactly once when the form initializes after this hook has already mounted", () => {
    const { result } = renderHook(() => useHarness(emptyBlueprintForm()));

    expect(result.current.expansion.isExpanded(rowDomId("properties", "p0"))).toBe(false);

    act(() => {
      result.current.form.initialize({
        ...emptyBlueprintForm(),
        properties: [{ ...emptyPropertyDraft(), key: "p0" }, { ...emptyPropertyDraft(), key: "p1" }],
      });
    });

    expect(result.current.expansion.isExpanded(rowDomId("properties", "p0"))).toBe(true);
    expect(result.current.expansion.isExpanded(rowDomId("properties", "p1"))).toBe(false);

    // Collapse the seeded row by hand, then re-initialize (e.g. a background refetch) — the
    // ref guard keeps the reseed to exactly once, so an ordinary later change never
    // silently re-expands/re-collapses anything.
    act(() => result.current.expansion.toggle(rowDomId("properties", "p0")));
    expect(result.current.expansion.isExpanded(rowDomId("properties", "p0"))).toBe(false);

    act(() => {
      result.current.form.initialize({
        ...emptyBlueprintForm(),
        properties: [{ ...emptyPropertyDraft(), key: "p0" }, { ...emptyPropertyDraft(), key: "p1" }],
      });
    });

    expect(result.current.expansion.isExpanded(rowDomId("properties", "p0"))).toBe(false);
  });

  test("toggle expands a collapsed row and collapses an expanded one", () => {
    const { result } = renderHook(() =>
      useHarness({
        ...emptyBlueprintForm(),
        properties: [{ ...emptyPropertyDraft(), key: "p0" }, { ...emptyPropertyDraft(), key: "p1" }],
      }),
    );
    const collapsedId = rowDomId("properties", "p1");
    expect(result.current.expansion.isExpanded(collapsedId)).toBe(false);

    act(() => result.current.expansion.toggle(collapsedId));
    expect(result.current.expansion.isExpanded(collapsedId)).toBe(true);

    act(() => result.current.expansion.toggle(collapsedId));
    expect(result.current.expansion.isExpanded(collapsedId)).toBe(false);
  });

  test("expandAll/collapseAll act on every id given", () => {
    const { result } = renderHook(() =>
      useHarness({
        ...emptyBlueprintForm(),
        properties: [{ ...emptyPropertyDraft(), key: "p0" }, { ...emptyPropertyDraft(), key: "p1" }],
      }),
    );
    const ids = [rowDomId("properties", "p0"), rowDomId("properties", "p1")];

    act(() => result.current.expansion.expandAll(ids));
    expect(ids.every((id) => result.current.expansion.isExpanded(id))).toBe(true);

    act(() => result.current.expansion.collapseAll(ids));
    expect(ids.every((id) => result.current.expansion.isExpanded(id))).toBe(false);
  });

  test("reveal expands the row and queues one pendingFocus; focusHandled clears it", () => {
    const { result } = renderHook(() =>
      useHarness({ ...emptyBlueprintForm(), properties: [{ ...emptyPropertyDraft(), key: "p0" }] }),
    );
    const id = rowDomId("properties", "p0");
    expect(result.current.expansion.pendingFocus).toBeNull();

    act(() => result.current.expansion.reveal(id));
    expect(result.current.expansion.isExpanded(id)).toBe(true);
    expect(result.current.expansion.pendingFocus).toBe(id);

    act(() => result.current.expansion.focusHandled());
    expect(result.current.expansion.pendingFocus).toBeNull();
  });

  test("revealErrors expands every offending row in family-then-index order and focuses the first", () => {
    const { result } = renderHook(() =>
      useHarness({
        ...emptyBlueprintForm(),
        properties: [{ ...emptyPropertyDraft(), key: "p0" }, { ...emptyPropertyDraft(), key: "p1" }],
        relations: [{ ...emptyRelationDraft(), key: "r0" }],
      }),
    );

    act(() =>
      result.current.expansion.revealErrors({
        "relations.0.title": "Required",
        "properties.1.id": "Required",
      }),
    );

    expect(result.current.expansion.isExpanded(rowDomId("properties", "p1"))).toBe(true);
    expect(result.current.expansion.isExpanded(rowDomId("relations", "r0"))).toBe(true);
    // "properties" precedes "relations" in ROW_FAMILIES order, so it is focused first
    // regardless of the errors object's own key order.
    expect(result.current.expansion.pendingFocus).toBe(rowDomId("properties", "p1"));
  });

  test("revealErrors is a no-op when no key names a row", () => {
    const { result } = renderHook(() =>
      useHarness({ ...emptyBlueprintForm(), properties: [{ ...emptyPropertyDraft(), key: "p0" }] }),
    );

    act(() => result.current.expansion.revealErrors({ identifier: "Required" }));
    expect(result.current.expansion.pendingFocus).toBeNull();
  });
});
