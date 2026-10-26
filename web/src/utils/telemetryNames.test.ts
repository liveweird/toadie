import { describe, expect, test } from "vitest";
import type { TFunction } from "i18next";
import i18n from "../i18n";
import { formatBytes } from "./telemetryFormat";
import { usageOf, workspaceGroupName, workspaceTableName } from "./telemetryNames";

const t = i18n.t.bind(i18n) as TFunction;

describe("telemetryNames", () => {
  test("known groups and tables read in words, an unknown name reads as itself", () => {
    expect(workspaceGroupName("registries", t)).toBe("Registries");
    expect(workspaceGroupName("future_group", t)).toBe("future_group");
    expect(workspaceTableName("catalog_file_events", t)).toBe("History events");
    expect(workspaceTableName("future_table", t)).toBe("future_table");
  });

  test("a bounded figure reads 'n of N', and an unmeasured one is the bare dash", () => {
    expect(usageOf(t, 3, 4)).toBe("3 of 4");
    expect(usageOf(t, 8 * 1024 * 1024, 64 * 1024 * 1024, formatBytes)).toBe("8 MB of 64 MB");
    expect(usageOf(t, null, 4)).toBe("—");
  });
});
