import "@mantine/charts/styles.layer.css";
import { useMemo } from "react";
import { useTranslation } from "react-i18next";
import { ChartTooltip, LineChart, type ChartSeries } from "@mantine/charts";
import { Box } from "@mantine/core";
import type { TelemetryRange } from "../api/telemetry";
import { lineStyle } from "../utils/chartColors";
import { formatDateTime } from "../utils/relativeTime";
import { formatTrendValue, type TrendUnit } from "../utils/telemetryTrend";

export interface TrendLine {
  label: string;
}

/** A value recharts hands a formatter: a finite number (also as a numeric string), or nothing usable. */
function finiteNumber(value: unknown): number | null {
  const number = typeof value === "number" ? value : typeof value === "string" && value.trim() !== "" ? Number(value) : NaN;
  return Number.isFinite(number) ? number : null;
}

/**
 * The tooltip's content. recharts calls the `content` render function EVERY render, also while the tooltip is inactive and with a
 * `label` of `undefined` (and a category it cannot place can be NaN), so it draws nothing unless the tooltip is active over a bucket
 * whose start is a finite number - a `formatDateTime` of anything else throws a `RangeError` that would take the whole panel down.
 */
export function TrendTooltip({
  active,
  label,
  payload,
  series,
  valueFormatter,
  locale,
}: {
  active?: boolean;
  label?: unknown;
  payload?: readonly unknown[];
  series: ChartSeries[];
  valueFormatter: (value: number) => string;
  locale: string;
}) {
  const at = finiteNumber(label);
  if (!active || at === null) return null;
  return <ChartTooltip label={formatDateTime(at, locale)} payload={[...(payload ?? [])] as never} series={series} valueFormatter={valueFormatter} />;
}

/**
 * One small line chart of a panel section's trend. Every line shares ONE unit (one axis); a legend appears from two lines on (a
 * single line is named by the section title and the group's label). A line is a metric, a table, a percentile: it has no meaning
 * of its own, so colour (`chartColors.ts`, in its fixed validated order) is never the only carrier - each line past the first also
 * wears its own dash pattern, and the legend, the tooltip and the numbers table behind `FiguresDisclosure` name every line. A
 * lazy chunk: recharts and the charts stylesheet never enter the main bundle. `rows` are `chartRows` (the bucket start as `t`,
 * `s<i>` per line); ticks and the tooltip stamp are in the viewer's language and local time.
 */
export default function TelemetryTrendChart({
  rows,
  lines,
  unit,
  range,
  ariaLabel,
}: {
  rows: Record<string, number | undefined>[];
  lines: TrendLine[];
  unit: TrendUnit;
  range: TelemetryRange;
  ariaLabel: string;
}) {
  const { i18n } = useTranslation();
  const locale = i18n.language;
  const series = lines.map((line, index) => ({ name: `s${index}`, label: line.label, ...lineStyle(index) }));
  const first = rows[0]?.t;
  const last = rows[rows.length - 1]?.t;
  // A day reads as clock times (with the date once the window crosses midnight), a week or three months as dates.
  const tick = useMemo(() => {
    const crossesMidnight = first != null && last != null && new Date(first).toDateString() !== new Date(last).toDateString();
    const options: Intl.DateTimeFormatOptions =
      range === "24h"
        ? crossesMidnight
          ? { month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit" }
          : { hour: "2-digit", minute: "2-digit" }
        : { month: "numeric", day: "numeric" };
    const formatter = new Intl.DateTimeFormat(locale, options);
    // A tick that is not a finite number (recharts can ask for one at the axis ends) reads as nothing rather than throwing.
    return (value: unknown) => {
      const at = finiteNumber(value);
      return at === null ? "" : formatter.format(at);
    };
  }, [first, last, range, locale]);
  // Axis ticks and tooltip rows: a missing value (a gap) reads as the dash, never throws.
  const format = (value: number) => {
    const number = finiteNumber(value);
    return number === null ? "—" : formatTrendValue(unit, number);
  };
  return (
    <Box role="group" aria-label={ariaLabel}>
      <LineChart
        h={180}
        data={rows}
        dataKey="t"
        series={series}
        curveType="linear"
        withDots={false}
        connectNulls={false}
        withLegend={series.length >= 2}
        legendProps={{ verticalAlign: "bottom", height: 24 }}
        tickLine="y"
        xAxisProps={{ minTickGap: 40, tickFormatter: tick }}
        yAxisProps={{ width: 64 }}
        valueFormatter={format}
        tooltipProps={{
          content: ({ active, label, payload }) => (
            <TrendTooltip active={active} label={label} payload={payload} series={series} valueFormatter={format} locale={locale} />
          ),
        }}
      />
    </Box>
  );
}
