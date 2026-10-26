import { useTranslation } from "react-i18next";
import { Badge, Stack, Text } from "@mantine/core";
import type { TelemetryCurrent, TelemetryProcess, TelemetryRange } from "../api/telemetry";
import { formatBytes, formatCount } from "../utils/telemetryFormat";
import { usageOf } from "../utils/telemetryNames";
import { oneSet, pressureColumns } from "../utils/telemetryTrend";
import { FirstSampleNote } from "./TelemetryMemorySection";
import TelemetryProcessHeader from "./TelemetryProcessHeader";
import TelemetryTrend from "./TelemetryTrend";
import TelemetryValueTable from "./TelemetryValueTable";

/** A refusal count: the bare number at zero, a red badge above it (the number is the text; the row's label says what was refused). */
function Refusals({ count }: { count: number | null }) {
  if (count == null || count === 0) return <>{formatCount(count)}</>;
  return (
    <Badge color="red" variant="light">
      {formatCount(count)}
    </Badge>
  );
}

/** Jq expressions quarantined after missing their deadline: orange above zero. */
function Quarantined({ count }: { count: number | null }) {
  if (count == null || count === 0) return <>{formatCount(count)}</>;
  return (
    <Badge color="orange" variant="light">
      {formatCount(count)}
    </Badge>
  );
}

function ProcessPressure({ process, current }: { process: TelemetryProcess; current: TelemetryCurrent }) {
  const { t } = useTranslation();
  const p = process.pressure;
  const { limits } = current;
  const integration = limits.integrationEnabled && limits.integrationPermits != null && limits.integrationRetainedBytes != null;
  return (
    <Stack gap="xs">
      <TelemetryProcessHeader process={process} />
      {process.sampledAt == null ? (
        <FirstSampleNote />
      ) : (
        <>
          <TelemetryValueTable
            label={t("telemetry.pressure.tableAria", { process: process.instance })}
            rows={[
              { label: t("telemetry.pressure.ledgerInFlight"), value: usageOf(t, p.ledgerInFlightBytes, limits.readLedgerBytes, formatBytes) },
              { label: t("telemetry.pressure.ledgerPeak"), value: usageOf(t, p.ledgerPeakBytes, limits.readLedgerBytes, formatBytes) },
              { label: t("telemetry.pressure.ledgerRefusals"), value: <Refusals count={p.ledgerRefusals} /> },
              { label: t("telemetry.pressure.queryInUse"), value: usageOf(t, p.queryInUse, limits.entityQueryPermits) },
              { label: t("telemetry.pressure.queryRefusals"), value: <Refusals count={p.queryRefusals} /> },
              { label: t("telemetry.pressure.jqQuarantined"), value: <Quarantined count={p.jqQuarantined} /> },
              { label: t("telemetry.pressure.jqActive"), value: usageOf(t, p.jqActive, limits.jqWorkers) },
              { label: t("telemetry.pressure.jqQueued"), value: usageOf(t, p.jqQueued, limits.jqQueueCapacity) },
              { label: t("telemetry.pressure.fetchActive"), value: usageOf(t, p.fetchActive, limits.fetchWorkers) },
              { label: t("telemetry.pressure.fetchQueued"), value: usageOf(t, p.fetchQueued, limits.fetchQueueCapacity) },
              ...(integration
                ? [
                    { label: t("telemetry.pressure.integrationInUse"), value: usageOf(t, p.integrationInUse, limits.integrationPermits ?? 0) },
                    { label: t("telemetry.pressure.integrationRetained"), value: usageOf(t, p.integrationRetainedBytes, limits.integrationRetainedBytes ?? 0, formatBytes) },
                    { label: t("telemetry.pressure.integrationRefusals"), value: <Refusals count={p.integrationRefusals} /> },
                  ]
                : []),
            ]}
          />
          {!integration && (
            <Text size="xs" c="dimmed">
              {t("telemetry.pressure.integrationOff")}
            </Text>
          )}
        </>
      )}
    </Stack>
  );
}

/** Pressure: how close each bounded thing is to its limit - "n of N" rows per process, refusal counts of the latest interval - and the refusals' trend. */
export default function TelemetryPressureSection({ current, range, active }: { current: TelemetryCurrent; range: TelemetryRange; active: boolean }) {
  const { t } = useTranslation();
  return (
    <Stack gap="md">
      {current.processes.length === 0 ? (
        <FirstSampleNote />
      ) : (
        current.processes.map((process) => <ProcessPressure key={process.id} process={process} current={current} />)
      )}
      <TelemetryTrend group="pressure" range={range} sets={(series, tr) => oneSet(pressureColumns(series, tr))} label={t("telemetry.section.pressure")} active={active} />
    </Stack>
  );
}
