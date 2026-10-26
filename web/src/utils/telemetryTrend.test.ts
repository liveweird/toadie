import { describe, expect, test } from "vitest";
import type { TFunction } from "i18next";
import i18n from "../i18n";
import { GENERATED_AT, MB, seriesFixture } from "../test/telemetryFixtures";
import { CATEGORY_PALETTE } from "./chartColors";
import {
  chartRows,
  formatTrendValue,
  jvmColumns,
  memoryColumns,
  oneSet,
  pressureColumns,
  requestsColumns,
  trendTimes,
  workspaceSets,
  type TrendColumn,
} from "./telemetryTrend";

const t = i18n.t.bind(i18n) as TFunction;
const HOUR = 3_600_000;

/** The one unit a chart may draw: every plotted column of a set shares it. */
const plottedUnits = (columns: readonly TrendColumn[]) => new Set(columns.filter((c) => c.plot).map((c) => c.unit));

describe("telemetryTrend", () => {
  test("memory draws the working set; the peak and the heap stay in the table", () => {
    const columns = memoryColumns([seriesFixture("mem.cgroup_working_set", [100 * MB, 200 * MB]), seriesFixture("mem.heap_used", [50 * MB])], t, null);
    expect(columns.map((c) => [c.label, c.plot])).toEqual([
      ["Working set (mean)", true],
      ["Working set (peak)", false],
      ["Heap used (mean)", false],
    ]);
    // The peak column reads the bucket's largest sample (1.5 x the mean in the fixture).
    expect(columns[1]?.values.get(GENERATED_AT - HOUR)).toBe(300 * MB);
  });

  test("without a container the heap is what memory draws when there is no working set either", () => {
    const columns = memoryColumns([seriesFixture("mem.heap_used", [10])], t, null);
    expect(columns.filter((c) => c.plot).map((c) => c.label)).toEqual(["Heap used (mean)"]);
  });

  test("with a container limit the DRAWN memory line is the share of it, and the bytes stay in the table", () => {
    const columns = memoryColumns([seriesFixture("mem.cgroup_working_set", [256 * MB])], t, 512 * MB);
    const plotted = columns.filter((c) => c.plot);
    expect(plotted.map((c) => [c.label, c.unit])).toEqual([["Share of the limit (mean)", "PERCENT"]]);
    expect([...plotted[0]!.values.values()]).toEqual([50]);
    // The peak share is the largest sample over the limit (1.5 x in the fixture); the bytes columns follow, undrawn.
    expect([...columns.find((c) => c.label === "Share of the limit (peak)")!.values.values()]).toEqual([75]);
    expect(columns.find((c) => c.label === "Working set (mean)")).toMatchObject({ unit: "BYTES", plot: false });
    expect(plottedUnits(columns).size).toBe(1);
  });

  test("workspace has two sets - bytes and counts - each drawn in one unit; a set with nothing is left out", () => {
    const sets = workspaceSets(
      [
        seriesFixture("db.size", [5]),
        seriesFixture("db.group.size", [3], { label: "catalog" }),
        seriesFixture("db.group.size", [2], { label: "mystery" }),
        seriesFixture("ws.rows", [9], { label: "catalog_files", unit: "COUNT" }),
        seriesFixture("ws.rows", [4], { label: "entities", unit: "COUNT" }),
      ],
      t,
    );
    expect(sets.map((s) => [s.id, s.title])).toEqual([["size", "Size"], ["rows", "Rows"]]);
    expect(sets[0]?.columns.map((c) => c.label)).toEqual(["Database", "Catalog", "mystery"]);
    expect(sets[1]?.columns.map((c) => c.label)).toEqual(["Catalog files", "Entities"]);
    for (const set of sets) {
      expect(plottedUnits(set.columns).size).toBe(1);
      expect(set.columns.every((c) => c.plot)).toBe(true);
    }
    expect(workspaceSets([seriesFixture("ws.rows", [1], { label: "lenses", unit: "COUNT" })], t).map((s) => s.id)).toEqual(["rows"]);
    expect(workspaceSets([], t)).toEqual([]);
  });

  test("pressure draws the refusals per bucket and keeps the gauge peaks in the table", () => {
    const columns = pressureColumns(
      [
        seriesFixture("ledger.refusals", [0, 2], { unit: "COUNT", kind: "DELTA" }),
        seriesFixture("query.refusals", [1], { unit: "COUNT", kind: "DELTA" }),
        seriesFixture("ledger.peak", [8 * MB]),
        seriesFixture("jq.quarantined", [1], { unit: "COUNT" }),
      ],
      t,
    );
    expect(columns.filter((c) => c.plot).map((c) => c.label)).toEqual(["Read budget refusals", "Entity query refusals"]);
    expect(plottedUnits(columns)).toEqual(new Set(["COUNT"]));
    expect(columns.map((c) => c.label)).toContain("Read budget peak");
    expect(columns.map((c) => c.label)).toContain("jq quarantined (peak)");
  });

  test("requests keep the all-routes series: p50 and p95 drawn, the counts beside them", () => {
    const columns = requestsColumns(
      [
        seriesFixture("http.p50", [10], { label: "*", unit: "MILLISECONDS", kind: "PERCENTILE" }),
        seriesFixture("http.p95", [90], { label: "*", unit: "MILLISECONDS", kind: "PERCENTILE" }),
        seriesFixture("http.count", [7], { label: "*", unit: "COUNT", kind: "DELTA" }),
        seriesFixture("http.p95", [400], { label: "/api/v1/entities/graph", unit: "MILLISECONDS", kind: "PERCENTILE" }),
      ],
      t,
    );
    expect(columns.map((c) => [c.label, c.plot])).toEqual([["p50", true], ["p95", true], ["Requests", false]]);
    expect(plottedUnits(columns)).toEqual(new Set(["MILLISECONDS"]));
  });

  test("jvm shows CPU as a percentage and sums the GC collectors", () => {
    const columns = jvmColumns(
      [
        seriesFixture("jvm.cpu_ratio", [0.25], { unit: "RATIO" }),
        seriesFixture("jvm.gc_time_ms", [10, 20], { unit: "MILLISECONDS", kind: "DELTA", label: "Copy" }),
        seriesFixture("jvm.gc_time_ms", [5, 5], { unit: "MILLISECONDS", kind: "DELTA", label: "MarkSweepCompact" }),
        seriesFixture("jvm.gc_count", [1], { unit: "COUNT", kind: "DELTA", label: "Copy" }),
        seriesFixture("jvm.threads", [40], { unit: "COUNT" }),
      ],
      t,
    );
    const cpu = columns.find((c) => c.label === "CPU (mean)");
    expect(cpu).toMatchObject({ unit: "PERCENT", plot: true });
    expect([...(cpu?.values.values() ?? [])]).toEqual([25]);
    expect([...(columns.find((c) => c.label === "GC time")?.values.values() ?? [])]).toEqual([15, 25]);
    expect(columns.map((c) => c.label)).toEqual(["CPU (mean)", "CPU (peak)", "Threads (mean)", "GC runs", "GC time"]);
  });

  test("a set without a plotted column has no chart rows to draw", () => {
    expect(oneSet([]).at(0)?.columns).toEqual([]);
    expect(oneSet([]).at(0)?.title).toBeNull();
  });

  test("times are the union of the columns' buckets, and chart rows leave a gap where a column has none", () => {
    const a = seriesFixture("db.size", [1, 2]);
    const b = seriesFixture("db.group.size", [9], { label: "catalog" });
    const columns = workspaceSets([a, b], t)[0]!.columns;
    const times = trendTimes(columns);
    expect(times).toEqual([GENERATED_AT - 2 * HOUR, GENERATED_AT - HOUR]);
    const rows = chartRows(columns, times);
    expect(rows[0]).toEqual({ t: GENERATED_AT - 2 * HOUR, s0: 1, s1: undefined });
    expect(rows[1]).toEqual({ t: GENERATED_AT - HOUR, s0: 2, s1: 9 });
  });

  test("the palette covers the most lines any set draws", () => {
    // 1 database + 6 table groups is the widest chart today.
    expect(CATEGORY_PALETTE.length).toBeGreaterThanOrEqual(7);
  });

  test("values format by unit", () => {
    expect(formatTrendValue("BYTES", 1536)).toBe("1.5 KB");
    expect(formatTrendValue("MILLISECONDS", 1500)).toBe("1.5 s");
    expect(formatTrendValue("PERCENT", 12.34)).toBe("12.3%");
    expect(formatTrendValue("COUNT", 12.4)).toBe("12");
  });
});
