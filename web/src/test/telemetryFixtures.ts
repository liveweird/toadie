import type { TelemetryCurrent, TelemetryHistory, TelemetryProcess, TelemetrySeries } from "../api/telemetry";

// Shared telemetry answers for the panel, section and trend tests (shaped like the server's `Telemetry*` schemas).

export const MB = 1024 * 1024;
export const GENERATED_AT = Date.UTC(2026, 9, 24, 12, 0, 0);
export const SAMPLED_AT = Date.UTC(2026, 9, 24, 11, 55, 0);

export function processFixture(overrides: Partial<TelemetryProcess> = {}): TelemetryProcess {
  return {
    id: 1,
    instance: "toadie-1",
    startedAt: Date.UTC(2026, 9, 23, 8, 0, 0),
    lastSeenAt: SAMPLED_AT,
    sampledAt: SAMPLED_AT,
    stale: false,
    version: "2.28.0",
    commit: "abc1234",
    memory: {
      heapUsedBytes: 120 * MB,
      heapCommittedBytes: 180 * MB,
      heapMaxBytes: 256 * MB,
      oldGenPeakBytes: 90 * MB,
      nonHeapBytes: 80 * MB,
      directBytes: 4 * MB,
      rssBytes: 400 * MB,
      cgroupCurrentBytes: 420 * MB,
      cgroupAnonBytes: 380 * MB,
      workingSetBytes: 410 * MB,
      cgroupPeakBytes: 430 * MB,
      containerLimitBytes: 512 * MB,
      percentOfLimit: 80.1,
    },
    jvm: {
      cpuRatio: 0.123,
      threads: 41,
      gcCount: 3,
      gcTimeMs: 25,
      poolAcquired: 2,
      poolIdle: 5,
      poolPending: 0,
      poolAllocated: 7,
    },
    pressure: {
      ledgerInFlightBytes: 8 * MB,
      ledgerPeakBytes: 16 * MB,
      ledgerRefusals: 0,
      queryInUse: 1,
      queryRefusals: 2,
      jqQuarantined: 1,
      jqActive: 0,
      jqQueued: 0,
      fetchActive: 0,
      fetchQueued: 0,
      integrationInUse: null,
      integrationRetainedBytes: null,
      integrationRefusals: null,
    },
    ...overrides,
  };
}

export function currentFixture(overrides: Partial<TelemetryCurrent> = {}): TelemetryCurrent {
  return {
    generatedAt: GENERATED_AT,
    sampleIntervalSeconds: 300,
    limits: {
      readLedgerBytes: 64 * MB,
      entityQueryPermits: 4,
      jqWorkers: 4,
      jqQueueCapacity: 64,
      fetchWorkers: 4,
      fetchQueueCapacity: 16,
      integrationEnabled: false,
      integrationPermits: null,
      integrationRetainedBytes: null,
      poolMaxSize: 20,
    },
    processes: [processFixture()],
    workspace: {
      measuredAt: Date.UTC(2026, 9, 24, 11, 0, 0),
      databaseBytes: 1_610_612_736,
      groups: [
        { name: "catalog", bytes: 900 * MB },
        { name: "ontology", bytes: 300 * MB },
        { name: "mystery", bytes: 1 * MB },
      ],
      rows: [
        { name: "catalog_files", count: 64_000 },
        { name: "entities", count: 120 },
        { name: "new_table", count: 3 },
      ],
    },
    requests: {
      windowSeconds: 300,
      percentileWindowSeconds: 300,
      measuredAt: SAMPLED_AT,
      total: { route: "*", count: 1500, p50Ms: 12, p95Ms: 480, serverErrors: 2, rateLimited: 0 },
      routes: [
        { route: "/api/v1/entities/graph", count: 900, p50Ms: 40, p95Ms: 900, serverErrors: 0, rateLimited: 3 },
        { route: "/api/v1/files/errors", count: 100, p50Ms: 30, p95Ms: 200, serverErrors: 0, rateLimited: 0 },
      ],
    },
    ...overrides,
  };
}

const HOUR = 3_600_000;

export function seriesFixture(metric: string, values: number[], extra: Partial<TelemetrySeries> = {}): TelemetrySeries {
  const label = extra.label ?? "";
  return {
    key: [metric, label || null].filter((part) => part !== null).join(":"),
    metric,
    label,
    unit: "BYTES",
    kind: "GAUGE",
    points: values.map((avg, index) => ({ t: GENERATED_AT - (values.length - index) * HOUR, avg, max: avg * 1.5 })),
    ...extra,
  };
}

export function historyFixture(
  group: TelemetryHistory["group"],
  range: TelemetryHistory["range"],
  series: TelemetrySeries[],
  overrides: Partial<TelemetryHistory> = {},
): TelemetryHistory {
  return {
    group,
    range,
    bucketSeconds: 300,
    from: GENERATED_AT - 86_400_000,
    to: GENERATED_AT,
    historyFrom: GENERATED_AT - 86_400_000,
    truncated: false,
    series,
    ...overrides,
  };
}
