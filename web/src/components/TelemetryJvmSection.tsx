import { useTranslation } from "react-i18next";
import { Stack } from "@mantine/core";
import type { TelemetryCurrent, TelemetryRange } from "../api/telemetry";
import { formatCount, formatDuration, formatRatio } from "../utils/telemetryFormat";
import { usageOf } from "../utils/telemetryNames";
import { jvmColumns, oneSet } from "../utils/telemetryTrend";
import { FirstSampleNote } from "./TelemetryMemorySection";
import TelemetryProcessHeader from "./TelemetryProcessHeader";
import TelemetryTrend from "./TelemetryTrend";
import TelemetryValueTable from "./TelemetryValueTable";

/** JVM: per process the CPU, thread count, GC work of the latest interval and the database pool (of its maximum), and the trend. */
export default function TelemetryJvmSection({ current, range, active }: { current: TelemetryCurrent; range: TelemetryRange; active: boolean }) {
  const { t } = useTranslation();
  const poolMax = current.limits.poolMaxSize;
  return (
    <Stack gap="md">
      {current.processes.length === 0 ? (
        <FirstSampleNote />
      ) : (
        current.processes.map((process) => (
          <Stack key={process.id} gap="xs">
            <TelemetryProcessHeader process={process} />
            {process.sampledAt == null ? (
              <FirstSampleNote />
            ) : (
              <TelemetryValueTable
                label={t("telemetry.jvm.tableAria", { process: process.instance })}
                rows={[
                  { label: t("telemetry.jvm.cpu"), value: formatRatio(process.jvm.cpuRatio) },
                  { label: t("telemetry.jvm.threads"), value: formatCount(process.jvm.threads) },
                  { label: t("telemetry.jvm.gcCount"), value: formatCount(process.jvm.gcCount) },
                  { label: t("telemetry.jvm.gcTime"), value: formatDuration(process.jvm.gcTimeMs) },
                  { label: t("telemetry.jvm.poolAcquired"), value: usageOf(t, process.jvm.poolAcquired, poolMax) },
                  { label: t("telemetry.jvm.poolIdle"), value: formatCount(process.jvm.poolIdle) },
                  { label: t("telemetry.jvm.poolPending"), value: formatCount(process.jvm.poolPending) },
                  { label: t("telemetry.jvm.poolAllocated"), value: usageOf(t, process.jvm.poolAllocated, poolMax) },
                ]}
              />
            )}
          </Stack>
        ))
      )}
      <TelemetryTrend group="jvm" range={range} sets={(series, tr) => oneSet(jvmColumns(series, tr))} label={t("telemetry.section.jvm")} active={active} />
    </Stack>
  );
}
