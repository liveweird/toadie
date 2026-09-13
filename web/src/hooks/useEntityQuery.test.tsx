import { afterEach, beforeEach, describe, expect, test } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { useEntityQuery } from "./useEntityQuery";

describe("useEntityQuery", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  afterEach(() => {
    localStorage.clear();
  });

  test("starts blank, closed, with nothing applied", () => {
    const { result } = renderHook(() => useEntityQuery());
    expect(result.current.draft).toBe("");
    expect(result.current.applied).toBe("");
    expect(result.current.open).toBe(false);
  });

  test("run() copies the draft into applied", () => {
    const { result } = renderHook(() => useEntityQuery());
    act(() => result.current.setDraft("MATCH (a)"));
    act(() => result.current.run());
    expect(result.current.applied).toBe("MATCH (a)");
  });

  test("run() no-ops on a blank (or whitespace-only) draft", () => {
    localStorage.setItem("toadie.viewSettings.entityQuery.applied", JSON.stringify("MATCH (a)"));
    localStorage.setItem("toadie.viewSettings.entityQuery.text", JSON.stringify("MATCH (a)"));
    const { result } = renderHook(() => useEntityQuery());
    act(() => result.current.setDraft("   "));
    act(() => result.current.run());
    // The blank draft already cleared `applied` (the invariant below); run() must not resurrect
    // it from a stale draft that no longer exists.
    expect(result.current.applied).toBe("");
  });

  test("setDraft('') clears the applied query immediately, even mid-session", () => {
    const { result } = renderHook(() => useEntityQuery());
    act(() => result.current.setDraft("MATCH (a)"));
    act(() => result.current.run());
    expect(result.current.applied).toBe("MATCH (a)");

    act(() => result.current.setDraft(""));
    expect(result.current.applied).toBe("");
    expect(localStorage.getItem("toadie.viewSettings.entityQuery.applied")).toBe(JSON.stringify(""));
  });

  test("a blank draft always derives an empty applied query, regardless of the stored value", () => {
    const { result } = renderHook(() => useEntityQuery());
    act(() => result.current.setDraft("MATCH (a)"));
    act(() => result.current.run());
    // Editing the draft back to blank via a whitespace-only value (not the setDraft("") fast
    // path) still derives applied === "" — the invariant, not just the eager clear.
    act(() => result.current.setDraft("   "));
    expect(result.current.applied).toBe("");
  });

  test("a legacy stale pair (blank draft, non-blank stored applied) is normalized on mount", () => {
    localStorage.setItem("toadie.viewSettings.entityQuery.text", JSON.stringify(""));
    localStorage.setItem("toadie.viewSettings.entityQuery.applied", JSON.stringify("MATCH (a)"));
    const { result } = renderHook(() => useEntityQuery());
    expect(result.current.applied).toBe("");
    expect(localStorage.getItem("toadie.viewSettings.entityQuery.applied")).toBe(JSON.stringify(""));
  });

  test("clear() empties both the draft and the applied query", () => {
    const { result } = renderHook(() => useEntityQuery());
    act(() => result.current.setDraft("MATCH (a)"));
    act(() => result.current.run());
    act(() => result.current.clear());
    expect(result.current.draft).toBe("");
    expect(result.current.applied).toBe("");
  });

  test("runText sets both the draft and the applied query, and opens the section", () => {
    const { result } = renderHook(() => useEntityQuery());
    expect(result.current.open).toBe(false);
    act(() => result.current.runText("MATCH (b)"));
    expect(result.current.draft).toBe("MATCH (b)");
    expect(result.current.applied).toBe("MATCH (b)");
    expect(result.current.open).toBe(true);
  });

  test("open persists under entityQuery.open and restores on remount", () => {
    const { result } = renderHook(() => useEntityQuery());
    act(() => result.current.setOpen(true));
    expect(localStorage.getItem("toadie.viewSettings.entityQuery.open")).toBe(JSON.stringify(true));

    const { result: remounted } = renderHook(() => useEntityQuery());
    expect(remounted.current.open).toBe(true);
  });

  test("run()/clear() leave `open` untouched", () => {
    const { result } = renderHook(() => useEntityQuery());
    act(() => result.current.setOpen(true));
    act(() => result.current.setDraft("MATCH (a)"));
    act(() => result.current.run());
    expect(result.current.open).toBe(true);
    act(() => result.current.clear());
    expect(result.current.open).toBe(true);
  });
});
