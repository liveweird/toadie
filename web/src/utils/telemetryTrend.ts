import type { TFunction } from "i18next";
import type { TelemetrySeries } from "../api/telemetry";
import { formatBytes, formatCount, formatDuration, formatPercent } from "./telemetryFormat";
import { workspaceGroupName, workspaceTableName } from "./telemetryNames";

// Turns the history endpoint's series into the columns of one section's trend: a few plotted lines (ONE unit per chart, one axis)
// and the full numbers table beside them. Pure and per section; `components/TelemetryTrend.tsx` queries and renders. The server
// merges every process into one series per (metric, label), so there is no per-process naming here.

/** A ratio series is shown in percent; every other unit is the wire's. */
export type TrendUnit = "BYTES" | "MILLISECONDS" | "COUNT" | "PERCENT";

export interface TrendColumn {
  id: string;
  label: string;
  unit: TrendUnit;
  /** Drawn as a line (all plotted columns of one set share a unit); every column is in the table. */
  plot: boolean;
  /** Bucket start (epoch millis) to the figure. */
  values: ReadonlyMap<number, number>;
}

/** One chart and its table: a section with two units (the Workspace's sizes and counts) has two sets, never a dual axis. */
export interface TrendSet {
  id: string;
  /** A heading over the set when a section has several; null for the section's only one. */
  title: string | null;
  columns: TrendColumn[];
}

/** The common case: one set carrying the whole section. */
export function oneSet(columns: TrendColumn[]): TrendSet[] {
  return [{ id: "all", title: null, columns }];
}

type Field = "avg" | "max";

export function formatTrendValue(unit: TrendUnit, value: number): string {
  switch (unit) {
    case "BYTES":
      return formatBytes(value);
    case "MILLISECONDS":
      return formatDuration(value);
    case "PERCENT":
      return formatPercent(value);
    case "COUNT":
      return formatCount(value);
  }
}

function trendUnit(series: TelemetrySeries): TrendUnit {
  return series.unit === "RATIO" ? "PERCENT" : series.unit;
}

function toColumn(series: TelemetrySeries, label: string, field: Field, plot: boolean): TrendColumn {
  const scale = series.unit === "RATIO" ? 100 : 1;
  return {
    id: `${series.key}:${field}`,
    label,
    unit: trendUnit(series),
    plot,
    values: new Map(series.points.map((point) => [point.t, point[field] * scale])),
  };
}

function columnsOf(series: readonly TelemetrySeries[], metric: string, label: string, field: Field, plot: boolean): TrendColumn[] {
  return series.filter((s) => s.metric === metric).map((s) => toColumn(s, label, field, plot));
}

/** The sum of several same-metric series (a metric sampled per GC collector) at each bucket, `avg` field. */
function summed(series: readonly TelemetrySeries[], metric: string, label: string): TrendColumn[] {
  const parts = series.filter((s) => s.metric === metric);
  const first = parts[0];
  if (first === undefined) return [];
  const values = new Map<number, number>();
  for (const part of parts) {
    for (const point of part.points) values.set(point.t, (values.get(point.t) ?? 0) + point.avg);
  }
  return [{ id: `${metric}:sum`, label, unit: trendUnit(first), plot: false, values }];
}

/**
 * Memory: the container working set (the figure the kubelet evicts on). With a known container limit (`limitBytes`, from the
 * current answer) the DRAWN line is its share of the limit in percent - proximity to the limit is the point - and the table adds
 * the bytes; without one the bytes are drawn, and without any working set the heap is.
 */
export function memoryColumns(series: readonly TelemetrySeries[], t: TFunction, limitBytes: number | null): TrendColumn[] {
  const workingSets = series.filter((s) => s.metric === "mem.cgroup_working_set");
  const hasWorkingSet = workingSets.length > 0;
  const shares = hasWorkingSet && limitBytes != null && limitBytes > 0;
  const share = (field: Field, label: string, plot: boolean): TrendColumn[] =>
    shares
      ? workingSets.map((s) => {
          const column = toColumn(s, label, field, plot);
          return { ...column, id: `${column.id}:share`, unit: "PERCENT", values: new Map([...column.values].map(([at, bytes]) => [at, (bytes / limitBytes) * 100])) };
        })
      : [];
  return [
    ...share("avg", t("telemetry.trend.shareOfLimit"), true),
    ...share("max", t("telemetry.trend.shareOfLimitPeak"), false),
    ...columnsOf(series, "mem.cgroup_working_set", t("telemetry.trend.workingSet"), "avg", !shares),
    ...columnsOf(series, "mem.cgroup_working_set", t("telemetry.trend.workingSetPeak"), "max", false),
    ...columnsOf(series, "mem.heap_used", t("telemetry.trend.heapUsed"), "avg", !hasWorkingSet),
    ...columnsOf(series, "mem.old_gen_peak", t("telemetry.trend.oldGenPeak"), "max", false),
  ];
}

/** Workspace: two sets from one answer - bytes (the database and every table group) and counts (every counted table), all drawn. */
export function workspaceSets(series: readonly TelemetrySeries[], t: TFunction): TrendSet[] {
  const sizes = [
    ...columnsOf(series, "db.size", t("telemetry.trend.database"), "avg", true),
    ...series.filter((s) => s.metric === "db.group.size").map((s) => toColumn(s, workspaceGroupName(s.label, t), "avg", true)),
  ];
  const rows = series.filter((s) => s.metric === "ws.rows").map((s) => toColumn(s, workspaceTableName(s.label, t), "avg", true));
  return [
    { id: "size", title: t("telemetry.trend.sizeTitle"), columns: sizes },
    { id: "rows", title: t("telemetry.trend.rowsTitle"), columns: rows },
  ].filter((set) => set.columns.length > 0);
}

/**
 * Pressure: the refusals of the bounded things per bucket are drawn (a count of requests turned away - the one figure that says a
 * limit was HIT); the peaks of the gauges sit beside them in the table, each in its own unit.
 */
export function pressureColumns(series: readonly TelemetrySeries[], t: TFunction): TrendColumn[] {
  return [
    ...columnsOf(series, "ledger.refusals", t("telemetry.trend.ledgerRefusals"), "avg", true),
    ...columnsOf(series, "query.refusals", t("telemetry.trend.queryRefusals"), "avg", true),
    ...columnsOf(series, "integration.refusals", t("telemetry.trend.integrationRefusals"), "avg", true),
    ...columnsOf(series, "ledger.peak", t("telemetry.trend.ledgerPeak"), "max", false),
    ...columnsOf(series, "query.in_use", t("telemetry.trend.queryInUse"), "max", false),
    ...columnsOf(series, "jq.quarantined", t("telemetry.trend.jqQuarantined"), "max", false),
    ...columnsOf(series, "jq.queued", t("telemetry.trend.jqQueued"), "max", false),
    ...columnsOf(series, "fetch.queued", t("telemetry.trend.fetchQueued"), "max", false),
    ...columnsOf(series, "integration.in_use", t("telemetry.trend.integrationInUse"), "max", false),
  ];
}

/** Requests: the all-routes total, p50 and p95 drawn (milliseconds), the counts beside them. */
export function requestsColumns(series: readonly TelemetrySeries[], t: TFunction): TrendColumn[] {
  const total = series.filter((s) => s.label === "*");
  return [
    ...columnsOf(total, "http.p50", t("telemetry.trend.p50"), "avg", true),
    ...columnsOf(total, "http.p95", t("telemetry.trend.p95"), "avg", true),
    ...columnsOf(total, "http.count", t("telemetry.trend.requests"), "avg", false),
    ...columnsOf(total, "http.5xx", t("telemetry.trend.serverErrors"), "avg", false),
    ...columnsOf(total, "http.429", t("telemetry.trend.rateLimited"), "avg", false),
  ];
}

/** JVM: CPU (percent of the available processors) drawn; threads, GC and the connection pool beside it. */
export function jvmColumns(series: readonly TelemetrySeries[], t: TFunction): TrendColumn[] {
  return [
    ...columnsOf(series, "jvm.cpu_ratio", t("telemetry.trend.cpu"), "avg", true),
    ...columnsOf(series, "jvm.cpu_ratio", t("telemetry.trend.cpuPeak"), "max", false),
    ...columnsOf(series, "jvm.threads", t("telemetry.trend.threads"), "avg", false),
    ...summed(series, "jvm.gc_count", t("telemetry.trend.gcCount")),
    ...summed(series, "jvm.gc_time_ms", t("telemetry.trend.gcTime")),
    ...columnsOf(series, "pool.acquired", t("telemetry.trend.poolAcquired"), "max", false),
    ...columnsOf(series, "pool.pending", t("telemetry.trend.poolPending"), "max", false),
  ];
}

/** The buckets any column has a figure for, ascending. */
export function trendTimes(columns: readonly TrendColumn[]): number[] {
  const times = new Set<number>();
  for (const column of columns) for (const time of column.values.keys()) times.add(time);
  return [...times].sort((a, b) => a - b);
}

/** One recharts row per bucket: the bucket start as `t` and `s<i>` for the i-th plotted column (absent = a gap). */
export function chartRows(plotted: readonly TrendColumn[], times: readonly number[]): Record<string, number | undefined>[] {
  return times.map((time) => {
    const row: Record<string, number | undefined> = { t: time };
    plotted.forEach((column, index) => {
      row[`s${index}`] = column.values.get(time);
    });
    return row;
  });
}
