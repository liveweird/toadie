import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import type { TFunction } from "i18next";
import { jsonResponse } from "../test/http";
import { QueryClient } from "@tanstack/react-query";
import { act, renderWithProviders, screen, within } from "../test/render";
import { GENERATED_AT, historyFixture, MB, seriesFixture } from "../test/telemetryFixtures";
import { formatDateTime } from "../utils/relativeTime";
import type { TelemetrySeries } from "../api/telemetry";
import { memoryColumns, oneSet, workspaceSets } from "../utils/telemetryTrend";
import TelemetryTrend from "./TelemetryTrend";

vi.mock("@mantine/charts", () => ({
  LineChart: (props: { series: { label: string }[]; data: unknown[] }) => (
    <div data-testid="line-chart" data-labels={props.series.map((s) => s.label).join("|")} data-rows={props.data.length} />
  ),
}));

function stubHistory(answer: (url: string) => Response) {
  const fetchMock = vi.fn((url: string) => Promise.resolve(answer(url)));
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

const memorySets = (series: readonly TelemetrySeries[], t: TFunction) => oneSet(memoryColumns(series, t, null));
const memory = [seriesFixture("mem.cgroup_working_set", [100 * MB, 200 * MB])];

describe("TelemetryTrend", () => {
  beforeEach(() => {
    localStorage.setItem("toadie.auth.token", "fake-token");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("asks for its group and range, draws the plotted lines and keeps every column's numbers in a table behind a disclosure", async () => {
    const fetchMock = stubHistory(() => jsonResponse(200, historyFixture("memory", "24h", memory)));
    const user = userEvent.setup();
    renderWithProviders(<TelemetryTrend group="memory" range="24h" sets={memorySets} label="Memory" />);
    expect((await screen.findByTestId("line-chart")).getAttribute("data-labels")).toBe("Working set (mean)");
    expect(screen.getByRole("group", { name: "Trend: Memory" })).toBeInTheDocument();
    expect(String(fetchMock.mock.calls[0]?.[0])).toBe("/api/v1/telemetry/history?group=memory&range=24h");
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    const toggle = screen.getByRole("button", { name: "Show the figures" });
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    await user.click(toggle);
    expect(screen.getByRole("button", { name: "Hide the figures" })).toHaveAttribute("aria-expanded", "true");
    const table = screen.getByRole("table", { name: "Trend figures: Memory" });
    expect(table).toHaveTextContent("100 MB");
    expect(table).toHaveTextContent("300 MB");
    expect(table).toHaveTextContent(formatDateTime(GENERATED_AT - 3_600_000, "en"));
    // The table is a keyboard-reachable, named scroll region.
    expect(screen.getByRole("group", { name: "Scrollable: Trend figures: Memory" })).toHaveAttribute("tabindex", "0");
    // A full-range history says nothing about where it starts.
    expect(screen.queryByText(/History starts/)).not.toBeInTheDocument();
  });

  test("a section with two sets draws a chart and a table for each, named after the set", async () => {
    stubHistory(() =>
      jsonResponse(
        200,
        historyFixture("workspace", "7d", [
          seriesFixture("db.size", [5 * MB, 6 * MB]),
          seriesFixture("ws.rows", [4, 5], { label: "entities", unit: "COUNT" }),
        ]),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<TelemetryTrend group="workspace" range="7d" sets={workspaceSets} label="Workspace" />);
    expect(await screen.findAllByTestId("line-chart")).toHaveLength(2);
    expect(screen.getByRole("heading", { name: "Size" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Rows" })).toBeInTheDocument();
    expect(screen.getByRole("group", { name: "Trend: Workspace · Rows" })).toBeInTheDocument();
    await user.click(screen.getAllByRole("button", { name: "Show the figures" })[1]!);
    expect(within(screen.getByRole("table", { name: "Trend figures: Workspace · Rows" })).getByText("Entities")).toBeInTheDocument();
  });

  test("a table-only set (nothing plotted) shows no chart but keeps its figures", async () => {
    stubHistory(() => jsonResponse(200, historyFixture("requests", "24h", [seriesFixture("http.count", [7], { label: "*", unit: "COUNT", kind: "DELTA" })])));
    renderWithProviders(
      <TelemetryTrend
        group="requests"
        range="24h"
        label="Requests"
        sets={(series) => oneSet([{ id: "c", label: "Requests", unit: "COUNT", plot: false, values: new Map(series[0]!.points.map((p) => [p.t, p.avg])) }])}
      />,
    );
    expect(await screen.findByRole("button", { name: "Show the figures" })).toBeInTheDocument();
    expect(screen.queryByTestId("line-chart")).not.toBeInTheDocument();
  });

  test("says when the history is shorter than the range, and when more lines matched than are listed", async () => {
    stubHistory(() =>
      jsonResponse(200, historyFixture("memory", "90d", memory, { historyFrom: GENERATED_AT - 3_600_000, from: GENERATED_AT - 90 * 86_400_000, truncated: true })),
    );
    renderWithProviders(<TelemetryTrend group="memory" range="90d" sets={memorySets} label="Memory" />);
    const date = new Intl.DateTimeFormat("en", { dateStyle: "medium" }).format(GENERATED_AT - 3_600_000);
    expect(await screen.findByText(`History starts ${date}.`)).toBeInTheDocument();
    expect(screen.getByText("More lines matched than are listed.")).toBeInTheDocument();
  });

  test("an inactive trend (its section collapsed) asks for nothing", async () => {
    const fetchMock = stubHistory(() => jsonResponse(200, historyFixture("memory", "24h", memory)));
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    renderWithProviders(<TelemetryTrend group="memory" range="24h" sets={memorySets} label="Memory" active={false} />, { queryClient });
    // A disabled query is registered but never fetches: its state stays idle with no data, and nothing is in flight.
    await act(async () => {});
    const query = queryClient.getQueryCache().find({ queryKey: ["telemetry", "history", "memory", "24h"] });
    expect(query?.state.fetchStatus).toBe("idle");
    expect(query?.state.data).toBeUndefined();
    expect(queryClient.isFetching()).toBe(0);
    expect(fetchMock).not.toHaveBeenCalled();
    expect(screen.getByRole("status")).toBeInTheDocument();
  });

  test("an answer without points is an empty state, not a blank chart", async () => {
    stubHistory(() => jsonResponse(200, historyFixture("memory", "24h", [])));
    renderWithProviders(<TelemetryTrend group="memory" range="24h" sets={memorySets} label="Memory" />);
    expect(await screen.findByText("No history for this range yet.")).toBeInTheDocument();
    expect(screen.queryByTestId("line-chart")).not.toBeInTheDocument();
  });

  test("a section whose sets are all empty says the same", async () => {
    stubHistory(() => jsonResponse(200, historyFixture("workspace", "24h", [])));
    renderWithProviders(<TelemetryTrend group="workspace" range="24h" sets={workspaceSets} label="Workspace" />);
    expect(await screen.findByText("No history for this range yet.")).toBeInTheDocument();
  });

  test("a failed load renders inline, with the status and not the raw message", async () => {
    stubHistory(() => jsonResponse(403, { title: "Forbidden" }));
    renderWithProviders(<TelemetryTrend group="memory" range="24h" sets={memorySets} label="Memory" />);
    expect(await screen.findByRole("alert")).toHaveTextContent("Load failed (403)");
  });

  test("never draws more lines than the palette has colours; the rest stay in the table", async () => {
    const many = Array.from({ length: 9 }, (_, i) => seriesFixture("ws.rows", [i + 1, i + 2], { label: `t${i}`, unit: "COUNT" }));
    stubHistory(() => jsonResponse(200, historyFixture("workspace", "24h", many)));
    renderWithProviders(<TelemetryTrend group="workspace" range="24h" sets={workspaceSets} label="Workspace" />);
    const chart = await screen.findByTestId("line-chart");
    expect(chart.getAttribute("data-labels")!.split("|")).toHaveLength(7);
  });
});
