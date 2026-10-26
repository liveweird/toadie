import { useTranslation } from "react-i18next";
import { Badge, Group, Stack, Text } from "@mantine/core";
import type { TelemetryCurrent, TelemetryProcess, TelemetryRange } from "../api/telemetry";
import { formatBytes, formatPercent, memoryLevel } from "../utils/telemetryFormat";
import { memoryColumns, oneSet } from "../utils/telemetryTrend";
import TelemetryProcessHeader from "./TelemetryProcessHeader";
import TelemetryTrend from "./TelemetryTrend";
import TelemetryValueTable from "./TelemetryValueTable";

/** The first-sample empty state shared by the per-process sections. */
export function FirstSampleNote() {
  const { t } = useTranslation();
  return (
    <Text size="sm" c="dimmed">
      {t("telemetry.firstSample")}
    </Text>
  );
}

/** The share of the container limit, with a TEXT badge from 80 % ("High") and 90 % ("Critical"): never colour alone. */
function PercentOfLimit({ percent }: { percent: number | null }) {
  const { t } = useTranslation();
  const level = memoryLevel(percent);
  return (
    <Group gap={6} justify="flex-end" wrap="nowrap">
      <span>{formatPercent(percent)}</span>
      {level === "critical" && (
        <Badge color="red" variant="light">
          {t("telemetry.memory.critical")}
        </Badge>
      )}
      {level === "high" && (
        <Badge color="orange" variant="light">
          {t("telemetry.memory.high")}
        </Badge>
      )}
    </Group>
  );
}

function ProcessMemory({ process }: { process: TelemetryProcess }) {
  const { t } = useTranslation();
  const m = process.memory;
  const limit = m.containerLimitBytes;
  return (
    <Stack gap="xs">
      <TelemetryProcessHeader process={process} />
      {process.sampledAt == null ? (
        <FirstSampleNote />
      ) : (
        <>
          <TelemetryValueTable
            label={t("telemetry.memory.tableAria", { process: process.instance })}
            rows={[
              { label: t("telemetry.memory.limit"), value: limit == null ? t("telemetry.memory.noLimit") : formatBytes(limit) },
              { label: t("telemetry.memory.workingSet"), value: formatBytes(m.workingSetBytes) },
              { label: t("telemetry.memory.percentOfLimit"), value: <PercentOfLimit percent={m.percentOfLimit} /> },
              { label: t("telemetry.memory.containerPeak"), value: formatBytes(m.cgroupPeakBytes) },
              { label: t("telemetry.memory.heapUsed"), value: formatBytes(m.heapUsedBytes) },
              { label: t("telemetry.memory.heapCommitted"), value: formatBytes(m.heapCommittedBytes) },
              { label: t("telemetry.memory.heapMax"), value: formatBytes(m.heapMaxBytes) },
              { label: t("telemetry.memory.oldGenPeak"), value: formatBytes(m.oldGenPeakBytes) },
              { label: t("telemetry.memory.nonHeap"), value: formatBytes(m.nonHeapBytes) },
              { label: t("telemetry.memory.direct"), value: formatBytes(m.directBytes) },
              { label: t("telemetry.memory.rss"), value: formatBytes(m.rssBytes) },
            ]}
          />
          {limit == null && (
            <Text size="xs" c="dimmed">
              {t("telemetry.memory.noLimitNote")}
            </Text>
          )}
        </>
      )}
    </Stack>
  );
}

/** The container limit the trend draws the working set against: the live process's, else any process's. */
function trendLimit(processes: readonly TelemetryProcess[]): number | null {
  const ordered = [...processes].sort((a, b) => Number(a.stale) - Number(b.stale));
  const limit = ordered.map((process) => process.memory.containerLimitBytes).find((bytes) => bytes != null && bytes > 0);
  return limit ?? null;
}

/** Memory: per process the container working set against its limit, the JVM's own pools, and the trend. */
export default function TelemetryMemorySection({ current, range, active }: { current: TelemetryCurrent; range: TelemetryRange; active: boolean }) {
  const { t } = useTranslation();
  const limit = trendLimit(current.processes);
  return (
    <Stack gap="md">
      {current.processes.length === 0 ? (
        <FirstSampleNote />
      ) : (
        current.processes.map((process) => <ProcessMemory key={process.id} process={process} />)
      )}
      <TelemetryTrend
        group="memory"
        range={range}
        sets={(series, tr) => oneSet(memoryColumns(series, tr, limit))}
        label={t("telemetry.section.memory")}
        active={active}
      />
    </Stack>
  );
}
