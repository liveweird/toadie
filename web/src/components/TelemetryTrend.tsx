import { lazy, Suspense } from "react";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import type { TFunction } from "i18next";
import { Stack, Table, Text, Title } from "@mantine/core";
import { getTelemetryHistory, type TelemetryGroup, type TelemetryRange, type TelemetrySeries } from "../api/telemetry";
import { CATEGORY_PALETTE } from "../utils/chartColors";
import { formatDateTime } from "../utils/relativeTime";
import { chartRows, formatTrendValue, trendTimes, type TrendSet } from "../utils/telemetryTrend";
import DataTable from "./DataTable";
import FiguresDisclosure from "./FiguresDisclosure";
import LoadingBlock from "./LoadingBlock";
import TelemetryLoadError from "./TelemetryLoadError";
import TelemetryScrollRegion from "./TelemetryScrollRegion";

const TelemetryTrendChart = lazy(() => import("./TelemetryTrendChart"));

const HISTORY_STALE_MS = 5 * 60_000;

function TrendSetView({ set, label, range }: { set: TrendSet; label: string; range: TelemetryRange }) {
  const { t, i18n } = useTranslation();
  const all = set.columns;
  // A chart never draws more lines than the palette has colours; the rest stay in the table.
  const plotted = all.filter((column) => column.plot).slice(0, CATEGORY_PALETTE.length);
  const times = trendTimes(all);
  const name = set.title === null ? label : `${label} · ${set.title}`;
  const unit = plotted[0]?.unit ?? "COUNT";
  return (
    <Stack gap="xs">
      {set.title !== null && (
        <Title order={4} size="h5">
          {set.title}
        </Title>
      )}
      {times.length === 0 ? (
        <Text size="sm" c="dimmed">
          {t("telemetry.trend.empty")}
        </Text>
      ) : (
        <>
          {plotted.length > 0 && (
            <Suspense fallback={<LoadingBlock py="md" />}>
              <TelemetryTrendChart
                rows={chartRows(plotted, times)}
                lines={plotted.map((column) => ({ label: column.label }))}
                unit={unit}
                range={range}
                ariaLabel={t("telemetry.trend.chartAria", { section: name })}
              />
            </Suspense>
          )}
          <FiguresDisclosure showLabel={t("telemetry.trend.showTable")} hideLabel={t("telemetry.trend.hideTable")}>
            <TelemetryScrollRegion label={t("telemetry.trend.tableAria", { section: name })} minWidth={190 + 110 * all.length} maxHeight={320}>
              <DataTable label={t("telemetry.trend.tableAria", { section: name })} verticalSpacing="xs" stickyHeader>
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th>{t("telemetry.trend.time")}</Table.Th>
                    {all.map((column) => (
                      <Table.Th key={column.id} ta="right">
                        {column.label}
                      </Table.Th>
                    ))}
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {times.map((time) => (
                    <Table.Tr key={time}>
                      <Table.Td style={{ whiteSpace: "nowrap" }}>{formatDateTime(time, i18n.language)}</Table.Td>
                      {all.map((column) => {
                        const value = column.values.get(time);
                        return (
                          <Table.Td key={column.id} ta="right" style={{ whiteSpace: "nowrap", fontVariantNumeric: "tabular-nums" }}>
                            {value === undefined ? "—" : formatTrendValue(column.unit, value)}
                          </Table.Td>
                        );
                      })}
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </DataTable>
            </TelemetryScrollRegion>
          </FiguresDisclosure>
        </>
      )}
    </Stack>
  );
}

/**
 * One section's trend: the history of its group over the panel's range (per group and range, kept five minutes), the sets the
 * section derives from the series (one chart and one numbers table each - one UNIT per chart), every column's numbers in a table
 * behind a disclosure (a 420 px panel has no room beside the chart). The notes say when the history is shorter than the range
 * (`historyFrom`) or cut at the server's series cap (`truncated`). The query is the section's own: it only runs while the
 * section is expanded (`active`).
 */
export default function TelemetryTrend({
  group,
  range,
  sets,
  label,
  active = true,
}: {
  group: TelemetryGroup;
  range: TelemetryRange;
  /** Derives the chart sets from the answer's series (a pure function of the series and the translator). */
  sets: (series: readonly TelemetrySeries[], t: TFunction) => TrendSet[];
  /** The section's name for the charts and the tables. */
  label: string;
  /** False while the section is collapsed (its body may stay mounted through the close transition): no request, cached figures stay. */
  active?: boolean;
}) {
  const { t, i18n } = useTranslation();
  const history = useQuery({
    queryKey: ["telemetry", "history", group, range],
    queryFn: ({ signal }) => getTelemetryHistory(group, range, signal),
    staleTime: HISTORY_STALE_MS,
    enabled: active,
    placeholderData: keepPreviousData,
  });
  if (history.isError && history.data === undefined) return <TelemetryLoadError error={history.error} />;
  if (history.data === undefined) return <LoadingBlock py="md" />;

  const data = history.data;
  const built = sets(data.series, t);
  // Kept data of the previous range (a switch in flight) is dimmed, never mislabelled: its own range is on the answer.
  const stale = history.isPlaceholderData;
  return (
    <Stack gap="xs" aria-busy={stale || undefined} opacity={stale ? 0.5 : 1}>
      {built.length === 0 ? (
        <Text size="sm" c="dimmed">
          {t("telemetry.trend.empty")}
        </Text>
      ) : (
        built.map((set) => <TrendSetView key={set.id} set={set} label={label} range={data.range} />)
      )}
      {data.historyFrom > data.from && (
        <Text size="xs" c="dimmed">
          {t("telemetry.trend.historyFrom", { date: new Intl.DateTimeFormat(i18n.language, { dateStyle: "medium" }).format(data.historyFrom) })}
        </Text>
      )}
      {data.truncated && (
        <Text size="xs" c="dimmed">
          {t("telemetry.trend.truncated")}
        </Text>
      )}
    </Stack>
  );
}
