import { describe, expect, test, vi } from "vitest";
import type { ReactElement } from "react";
import { renderWithProviders, screen } from "../test/render";
import { formatDateTime } from "../utils/relativeTime";
import TelemetryTrendChart, { TrendTooltip } from "./TelemetryTrendChart";

interface Props {
  data: unknown;
  series: unknown;
  withLegend: boolean;
  withDots: boolean;
  connectNulls: boolean;
  valueFormatter: (value: number) => string;
  xAxisProps: { tickFormatter: (value: unknown) => string };
  tooltipProps: { content: (props: { active?: boolean; label?: unknown; payload?: unknown[] }) => ReactElement };
}

const T1 = Date.UTC(2026, 9, 24, 11, 55);
const T2 = Date.UTC(2026, 9, 25, 11, 55);

vi.mock("@mantine/charts", () => ({
  ChartTooltip: (props: { label: string }) => <p data-testid="tooltip">{props.label}</p>,
  LineChart: (props: Props) => (
    <div
      data-testid="line-chart"
      data-series={JSON.stringify(props.series)}
      data-legend={String(props.withLegend)}
      data-dots={String(props.withDots)}
      data-nulls={String(props.connectNulls)}
      data-formatted={props.valueFormatter(1536)}
      data-tick={props.xAxisProps.tickFormatter(1_792_000_000_000)}
      data-ticks-unusable={[undefined, Number.NaN, "x", null].map((v) => props.xAxisProps.tickFormatter(v)).join("|")}
      data-values-unusable={[undefined, Number.NaN, null].map((v) => props.valueFormatter(v as never)).join("|")}
    >
      {props.tooltipProps.content({ active: true, label: 1_792_000_000_000, payload: [] })}
    </div>
  ),
}));

const rows = [{ t: T1, s0: 1, s1: 2 }];

describe("TelemetryTrendChart", () => {
  test("two lines wear the validated palette AND their own dash, get a legend, one axis unit and gaps for missing buckets", () => {
    renderWithProviders(<TelemetryTrendChart rows={rows} lines={[{ label: "p50" }, { label: "p95" }]} unit="BYTES" range="24h" ariaLabel="Trend: Requests" />);
    expect(screen.getByRole("group", { name: "Trend: Requests" })).toBeInTheDocument();
    const chart = screen.getByTestId("line-chart");
    expect(JSON.parse(chart.getAttribute("data-series")!)).toEqual([
      { name: "s0", label: "p50", color: "blue.7" },
      { name: "s1", label: "p95", color: "lime.9", strokeDasharray: "6 3" },
    ]);
    expect(chart.getAttribute("data-legend")).toBe("true");
    expect(chart.getAttribute("data-dots")).toBe("false");
    expect(chart.getAttribute("data-nulls")).toBe("false");
    expect(chart.getAttribute("data-formatted")).toBe("1.5 KB");
    expect(screen.getByTestId("tooltip")).toHaveTextContent(formatDateTime(1_792_000_000_000, "en"));
  });

  test("a tick or a value recharts hands over that is not a finite number reads as nothing (ticks) or the dash (values), and never throws", () => {
    renderWithProviders(<TelemetryTrendChart rows={rows} lines={[{ label: "CPU" }]} unit="PERCENT" range="24h" ariaLabel="x" />);
    const chart = screen.getByTestId("line-chart");
    expect(chart.getAttribute("data-ticks-unusable")).toBe("|||");
    expect(chart.getAttribute("data-values-unusable")).toBe("—|—|—");
  });

  test("a single line has no legend; ticks read as clock times for a day (with the date once it crosses midnight) and as dates for longer ranges", () => {
    const day = new Intl.DateTimeFormat("en", { hour: "2-digit", minute: "2-digit" });
    const dayAndDate = new Intl.DateTimeFormat("en", { month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit" });
    const date = new Intl.DateTimeFormat("en", { month: "numeric", day: "numeric" });
    const { rerender } = renderWithProviders(<TelemetryTrendChart rows={[{ t: T1, s0: 1 }]} lines={[{ label: "CPU" }]} unit="PERCENT" range="24h" ariaLabel="x" />);
    expect(screen.getByTestId("line-chart").getAttribute("data-legend")).toBe("false");
    expect(screen.getByTestId("line-chart").getAttribute("data-tick")).toBe(day.format(1_792_000_000_000));
    rerender(<TelemetryTrendChart rows={[{ t: T1, s0: 1 }, { t: T2, s0: 2 }]} lines={[{ label: "CPU" }]} unit="PERCENT" range="24h" ariaLabel="x" />);
    expect(screen.getByTestId("line-chart").getAttribute("data-tick")).toBe(dayAndDate.format(1_792_000_000_000));
    rerender(<TelemetryTrendChart rows={rows} lines={[{ label: "CPU" }]} unit="PERCENT" range="90d" ariaLabel="x" />);
    expect(screen.getByTestId("line-chart").getAttribute("data-tick")).toBe(date.format(1_792_000_000_000));
  });
});

describe("TrendTooltip", () => {
  const props = { series: [], valueFormatter: (value: number) => String(value), locale: "en" };

  test("draws the bucket's stamp while active over a bucket", () => {
    renderWithProviders(<TrendTooltip {...props} active label={T1} payload={[]} />);
    expect(screen.getByTestId("tooltip")).toHaveTextContent(formatDateTime(T1, "en"));
  });

  test.each([
    ["inactive", { active: false, label: T1 }],
    ["active with no label (recharts renders it inactive with undefined)", { active: true, label: undefined }],
    ["no props at all", {}],
    ["a NaN label", { active: true, label: Number.NaN }],
    ["an infinite label", { active: true, label: Number.POSITIVE_INFINITY }],
    ["a non-numeric label", { active: true, label: "soon" }],
    ["a blank label", { active: true, label: " " }],
  ])("%s renders nothing and does not throw", (_name, extra) => {
    renderWithProviders(<TrendTooltip {...props} {...extra} />);
    expect(screen.queryByTestId("tooltip")).not.toBeInTheDocument();
  });
});
