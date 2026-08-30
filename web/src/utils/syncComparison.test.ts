import { describe, expect, test } from "vitest";
import { compareSyncSides, hasLocalChanges } from "./syncComparison";

describe("hasLocalChanges", () => {
  test("true only for a SYNCED row whose updatedAt moved past lastSyncedAt", () => {
    expect(hasLocalChanges({ updatedAt: 2000, lastSyncedAt: 1000 })).toBe(true);
    expect(hasLocalChanges({ updatedAt: 1000, lastSyncedAt: 1000 })).toBe(false);
  });

  test("a never-synced row (lastSyncedAt = 0) never reads as locally changed", () => {
    expect(hasLocalChanges({ updatedAt: 2000, lastSyncedAt: 0 })).toBe(false);
  });
});

describe("compareSyncSides", () => {
  const base = {
    currentYaml: "a\nb\n",
    repoYaml: "a\nc\n",
    baselineYaml: "a\nb\n",
    updatedAt: 1000,
    lastSyncedAt: 1000,
  };

  test("identical renders are in sync with no diff", () => {
    const result = compareSyncSides({ ...base, repoYaml: "a\nb\n" });
    expect(result.inSync).toBe(true);
    expect(result.diff).toBeNull();
    expect(result.repoChanged).toBe(false);
  });

  test("a repo drift from the baseline lights repoChanged and yields the diff", () => {
    const result = compareSyncSides(base);
    expect(result.inSync).toBe(false);
    expect(result.repoChanged).toBe(true);
    expect(result.dbChanged).toBe(false);
    expect(result.diff).toEqual([
      { kind: "same", text: "a" },
      { kind: "removed", text: "b" },
      { kind: "added", text: "c" },
      { kind: "same", text: "" },
    ]);
  });

  test("a DB move past the sync stamp lights dbChanged; both sides can light together", () => {
    const result = compareSyncSides({ ...base, currentYaml: "a\nd\n", updatedAt: 2000 });
    expect(result.dbChanged).toBe(true);
    expect(result.repoChanged).toBe(true);
  });

  test("a missing side yields no verdict material — no diff, nothing in sync", () => {
    const result = compareSyncSides({ ...base, repoYaml: null, baselineYaml: null, updatedAt: null });
    expect(result.inSync).toBe(false);
    expect(result.diff).toBeNull();
    expect(result.repoChanged).toBe(false);
    expect(result.dbChanged).toBe(false);
  });
});
