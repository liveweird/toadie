import { afterEach, beforeEach, describe, expect, test } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { notifyAuthChange } from "../auth";
import { useEntityQuery } from "./useEntityQuery";

const PREFIX = "toadie.viewSettings.entityQuery.account.5.";

describe("useEntityQuery", () => {
  beforeEach(() => {
    localStorage.clear();
    localStorage.setItem("toadie.auth.token", "test-token");
    localStorage.setItem("toadie.auth.userId", "5");
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
    localStorage.setItem(`${PREFIX}applied`, JSON.stringify("MATCH (a)"));
    localStorage.setItem(`${PREFIX}text`, JSON.stringify("MATCH (a)"));
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
    expect(localStorage.getItem(`${PREFIX}applied`)).toBe(JSON.stringify(""));
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
    localStorage.setItem(`${PREFIX}text`, JSON.stringify(""));
    localStorage.setItem(`${PREFIX}applied`, JSON.stringify("MATCH (a)"));
    const { result } = renderHook(() => useEntityQuery());
    expect(result.current.applied).toBe("");
    expect(localStorage.getItem(`${PREFIX}applied`)).toBe(JSON.stringify(""));
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
    expect(localStorage.getItem(`${PREFIX}open`)).toBe(JSON.stringify(true));

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

  test("partitions draft and applied state across logout and an account switch", () => {
    const { result } = renderHook(() => useEntityQuery());
    act(() => result.current.runText("MATCH (privateA)"));
    expect(result.current.applied).toBe("MATCH (privateA)");

    act(() => {
      localStorage.removeItem("toadie.auth.token");
      localStorage.removeItem("toadie.auth.userId");
      notifyAuthChange();
    });
    expect(result.current.draft).toBe("");
    expect(result.current.applied).toBe("");

    act(() => {
      localStorage.setItem("toadie.auth.token", "test-token-b");
      localStorage.setItem("toadie.auth.userId", "8");
      notifyAuthChange();
    });
    expect(result.current.draft).toBe("");
    expect(result.current.applied).toBe("");
    act(() => result.current.runText("MATCH (privateB)"));

    expect(localStorage.getItem(`${PREFIX}text`)).toBe(JSON.stringify("MATCH (privateA)"));
    expect(localStorage.getItem("toadie.viewSettings.entityQuery.account.8.text")).toBe(
      JSON.stringify("MATCH (privateB)"),
    );
  });

  test("discards ownerless legacy global query keys", () => {
    localStorage.setItem("toadie.viewSettings.entityQuery.text", JSON.stringify("MATCH (legacy)"));
    localStorage.setItem("toadie.viewSettings.entityQuery.applied", JSON.stringify("MATCH (legacy)"));
    localStorage.setItem("toadie.viewSettings.entityQuery.picked", JSON.stringify("99"));
    localStorage.setItem("toadie.viewSettings.entityQuery.open", JSON.stringify(true));

    const { result } = renderHook(() => useEntityQuery());
    expect(result.current.draft).toBe("");
    expect(result.current.applied).toBe("");
    expect(localStorage.getItem("toadie.viewSettings.entityQuery.text")).toBeNull();
    expect(localStorage.getItem("toadie.viewSettings.entityQuery.applied")).toBeNull();
    expect(localStorage.getItem("toadie.viewSettings.entityQuery.picked")).toBeNull();
    expect(localStorage.getItem("toadie.viewSettings.entityQuery.open")).toBeNull();
  });
});
