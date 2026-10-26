import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { QueryClient } from "@tanstack/react-query";
import { AppShell } from "@mantine/core";
import type { TelemetryCurrent } from "../api/telemetry";
import { jsonResponse } from "../test/http";
import { renderWithProviders, screen, within } from "../test/render";
import { currentFixture, historyFixture, MB, processFixture, SAMPLED_AT, seriesFixture } from "../test/telemetryFixtures";
import { formatDateTime } from "../utils/relativeTime";
import TelemetryPanel from "./TelemetryPanel";

vi.mock("@mantine/charts", () => ({
  LineChart: (props: { series: { label: string }[] }) => (
    <div data-testid="line-chart" data-labels={props.series.map((s) => s.label).join("|")} />
  ),
}));


function stubApi(current: TelemetryCurrent = currentFixture()) {
  const fetchMock = vi.fn((url: string) => {
    if (url.startsWith("/api/v1/telemetry/current")) return Promise.resolve(jsonResponse(200, current));
    const query = new URL(url, "http://x").searchParams;
    const group = query.get("group") as "memory";
    const range = query.get("range") as "24h";
    return Promise.resolve(jsonResponse(200, historyFixture(group, range, [seriesFixture("mem.cgroup_working_set", [100 * MB, 200 * MB])])));
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

const calls = (fetchMock: ReturnType<typeof stubApi>, path: string) =>
  fetchMock.mock.calls.map((call) => String(call[0])).filter((url) => url.startsWith(path));

function renderPanel(onClose = () => {}, queryClient?: QueryClient) {
  return renderWithProviders(
    <AppShell aside={{ width: 420, breakpoint: "sm" }}>
      <AppShell.Aside>
        <TelemetryPanel onClose={onClose} />
      </AppShell.Aside>
    </AppShell>,
    { queryClient },
  );
}

const section = (name: string) => screen.getByRole("button", { name });
const SAMPLED = formatDateTime(SAMPLED_AT, "en");

describe("TelemetryPanel", () => {
  beforeEach(() => {
    localStorage.setItem("toadie.auth.token", "fake-token");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.useRealTimers();
    localStorage.clear();
  });

  test("shows the title, when the newest sample was taken, a close button and the five sections with Memory open", async () => {
    stubApi();
    const onClose = vi.fn();
    const user = userEvent.setup();
    renderPanel(onClose);
    expect(await screen.findByRole("heading", { level: 2, name: "Telemetry" })).toBeInTheDocument();
    // The header (the newest sample of any process) and the process's own heading.
    expect(await screen.findAllByText(`Sampled at ${SAMPLED}`)).toHaveLength(2);
    for (const name of ["Memory", "Workspace", "Pressure", "Requests", "JVM"]) {
      expect(section(name)).toBeInTheDocument();
    }
    expect(section("Memory")).toHaveAttribute("aria-expanded", "true");
    expect(section("Workspace")).toHaveAttribute("aria-expanded", "false");
    await user.click(screen.getByRole("button", { name: "Close" }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  test("Memory prints the process's figures with the share of its limit, and TEXT badges from 80 % (High) and 90 % (Critical)", async () => {
    const base = processFixture();
    stubApi(currentFixture({ processes: [processFixture(), processFixture({ id: 2, instance: "toadie-0", stale: true, memory: { ...base.memory, percentOfLimit: 92.1 } })] }));
    renderPanel();
    const table = await screen.findByRole("table", { name: "Memory of toadie-1" });
    expect(within(table).getByText("512 MB")).toBeInTheDocument();
    expect(within(table).getByText("410 MB")).toBeInTheDocument();
    expect(within(table).getByText("80.1%")).toBeInTheDocument();
    expect(within(table).getByText("High")).toBeInTheDocument();
    expect(within(table).queryByText("Critical")).not.toBeInTheDocument();
    const stale = screen.getByRole("table", { name: "Memory of toadie-0" });
    expect(within(stale).getByText("92.1%")).toBeInTheDocument();
    expect(within(stale).getByText("Critical")).toBeInTheDocument();
  });

  test("a process below 80 % has no badge; a stale one is flagged by text, in its heading and as an alert", async () => {
    stubApi(currentFixture({ processes: [processFixture({ memory: { ...processFixture().memory, percentOfLimit: 79.9 } }), processFixture({ id: 2, instance: "toadie-0", stale: true })] }));
    renderPanel();
    const table = await screen.findByRole("table", { name: "Memory of toadie-1" });
    expect(within(table).queryByText("High")).not.toBeInTheDocument();
    expect(within(table).queryByText("Critical")).not.toBeInTheDocument();
    expect(screen.getByText("Stale")).toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent("A process has stopped reporting");
    expect(screen.getByRole("alert")).toHaveTextContent("toadie-0 has sent no sample for more than three sample intervals");
  });

  test("outside a container there is no limit and the panel says so", async () => {
    const base = processFixture();
    stubApi(currentFixture({ processes: [processFixture({ memory: { ...base.memory, containerLimitBytes: null, percentOfLimit: null, workingSetBytes: null } })] }));
    renderPanel();
    const table = await screen.findByRole("table", { name: "Memory of toadie-1" });
    expect(within(table).getByText("No limit")).toBeInTheDocument();
    expect(screen.getByText("Not running in a container: no limit, so no share of it either.")).toBeInTheDocument();
  });

  test("before the first sample the process sections say when it arrives", async () => {
    stubApi(currentFixture({ processes: [processFixture({ sampledAt: null })] }));
    renderPanel();
    expect(await screen.findByText("No sample yet.")).toBeInTheDocument();
    expect(screen.getByText("No sample yet")).toBeInTheDocument();
    expect(screen.queryByText(/^Sampled at/)).not.toBeInTheDocument();
  });

  test("with no process at all, Memory, Pressure and JVM show the same note", async () => {
    stubApi(currentFixture({ processes: [] }));
    const user = userEvent.setup();
    renderPanel();
    expect(await screen.findAllByText(/^No sample yet\.$/)).toHaveLength(1);
    await user.click(section("Pressure"));
    await user.click(section("JVM"));
    expect(screen.getAllByText(/^No sample yet\.$/)).toHaveLength(3);
  });

  test("Workspace lists the database, the table groups and the row counts; names it does not know read as themselves; before the first measurement it says so", async () => {
    stubApi();
    const user = userEvent.setup();
    const { unmount } = renderPanel();
    await user.click(await screen.findByRole("button", { name: "Workspace" }));
    const sizes = screen.getByRole("table", { name: "Database size per table group" });
    expect(within(sizes).getByText("1.5 GB")).toBeInTheDocument();
    expect(within(sizes).getByText("Catalog")).toBeInTheDocument();
    expect(within(sizes).getByText("900 MB")).toBeInTheDocument();
    expect(within(sizes).getByText("mystery")).toBeInTheDocument();
    const rows = screen.getByRole("table", { name: "Rows per table" });
    expect(within(rows).getByText("Catalog files")).toBeInTheDocument();
    expect(within(rows).getByText("64 000")).toBeInTheDocument();
    expect(within(rows).getByText("new_table")).toBeInTheDocument();
    expect(screen.getByText(`Measured at ${formatDateTime(Date.UTC(2026, 9, 24, 11, 0, 0), "en")}`)).toBeInTheDocument();
    unmount();

    stubApi(currentFixture({ workspace: null }));
    renderPanel();
    expect(await screen.findByText("Not measured yet.")).toBeInTheDocument();
  });

  test("Pressure reads 'n of N' per bounded thing, flags refusals and quarantine, and says the integration API is off", async () => {
    stubApi();
    const user = userEvent.setup();
    renderPanel();
    await user.click(await screen.findByRole("button", { name: "Pressure" }));
    const table = screen.getByRole("table", { name: "Limits of toadie-1" });
    const row = (label: string) => within(table).getByText(label).closest("tr")!;
    expect(within(row("Read memory budget in use")).getByText("8 MB of 64 MB")).toBeInTheDocument();
    expect(within(row("Read memory budget peak (last interval)")).getByText("16 MB of 64 MB")).toBeInTheDocument();
    expect(within(row("Entity queries running")).getByText("1 of 4")).toBeInTheDocument();
    expect(within(row("jq workers busy")).getByText("0 of 4")).toBeInTheDocument();
    expect(within(row("jq calculations waiting")).getByText("0 of 64")).toBeInTheDocument();
    expect(within(row("Outbound fetches waiting")).getByText("0 of 16")).toBeInTheDocument();
    // A refusal count above zero is a badge; at zero it is the bare number. Quarantine is a badge too.
    expect(row("Entity queries refused (last interval)").querySelector(".mantine-Badge-root")).toHaveTextContent("2");
    expect(row("Reads refused for want of budget (last interval)").querySelector(".mantine-Badge-root")).toBeNull();
    expect(row("jq expressions quarantined").querySelector(".mantine-Badge-root")).toHaveTextContent("1");
    // No integration rows while that API is off.
    expect(within(table).queryByText("Integration requests running")).not.toBeInTheDocument();
    expect(screen.getByText("Integration API off: it has no figures.")).toBeInTheDocument();
  });

  test("with the integration API on, its rows are 'n of N' and a refusal is a badge", async () => {
    const base = currentFixture();
    stubApi(
      currentFixture({
        limits: { ...base.limits, integrationEnabled: true, integrationPermits: 4, integrationRetainedBytes: 32 * MB },
        processes: [processFixture({ pressure: { ...processFixture().pressure, integrationInUse: 1, integrationRetainedBytes: 2 * MB, integrationRefusals: 5 } })],
      }),
    );
    const user = userEvent.setup();
    renderPanel();
    await user.click(await screen.findByRole("button", { name: "Pressure" }));
    const table = screen.getByRole("table", { name: "Limits of toadie-1" });
    expect(within(within(table).getByText("Integration requests running").closest("tr")!).getByText("1 of 4")).toBeInTheDocument();
    expect(within(table).getByText("2 MB of 32 MB")).toBeInTheDocument();
    expect(within(table).getByText("Integration requests refused (last interval)").closest("tr")!.querySelector(".mantine-Badge-root")).toHaveTextContent("5");
    expect(screen.queryByText("Integration API off: it has no figures.")).not.toBeInTheDocument();
  });

  test("Pressure before the first sample says when it arrives", async () => {
    stubApi(currentFixture({ processes: [processFixture({ sampledAt: null })] }));
    const user = userEvent.setup();
    renderPanel();
    await user.click(await screen.findByRole("button", { name: "Pressure" }));
    expect(screen.getAllByText(/^No sample yet\.$/)).toHaveLength(2);
  });

  test("Requests shows the total and the busiest routes with 5xx and 429 badges in a keyboard-reachable region; without a sample it says so", async () => {
    stubApi();
    const user = userEvent.setup();
    const { unmount } = renderPanel();
    await user.click(await screen.findByRole("button", { name: "Requests" }));
    const table = screen.getByRole("table", { name: "Requests per route" });
    const total = within(table).getByText("All routes").closest("tr")!;
    expect(within(total).getByText("1 500")).toBeInTheDocument();
    expect(within(total).getByText("480 ms")).toBeInTheDocument();
    expect(within(total).getByText("2")).toBeInTheDocument();
    const graph = within(table).getByText("/api/v1/entities/graph").closest("tr")!;
    expect(within(graph).getByText("900")).toBeInTheDocument();
    expect(within(graph).getByText("3")).toBeInTheDocument();
    expect(screen.getByText(`Counts: the last 5 min. Percentiles: the last 5 min. Sampled at ${SAMPLED}. Telemetry's own requests are left out; routes are listed only for the heavy reads and imports.`)).toBeInTheDocument();
    expect(within(table).getByRole("columnheader", { name: "p95 (5 min)" })).toBeInTheDocument();
    expect(screen.getByRole("group", { name: "Scrollable: Requests per route" })).toHaveAttribute("tabindex", "0");
    unmount();

    stubApi(currentFixture({ requests: { windowSeconds: 300, percentileWindowSeconds: 300, measuredAt: null, total: currentFixture().requests.total, routes: [] } }));
    localStorage.setItem("toadie.viewSettings.telemetry.sections", '["requests"]');
    renderPanel();
    expect(await screen.findByText(/No process has a recent sample/)).toBeInTheDocument();
  });

  test("the percentiles are labelled with their own window, which may differ from the counts'", async () => {
    const base = currentFixture();
    stubApi({ ...base, requests: { ...base.requests, windowSeconds: 300, percentileWindowSeconds: 900 } });
    localStorage.setItem("toadie.viewSettings.telemetry.sections", '["requests"]');
    renderPanel();
    const table = await screen.findByRole("table", { name: "Requests per route" });
    expect(within(table).getByRole("columnheader", { name: "p50 (15 min)" })).toBeInTheDocument();
    expect(screen.getByText(/Counts: the last 5 min\. Percentiles: the last 15 min\./)).toBeInTheDocument();
  });

  test("JVM shows CPU, threads, GC and the connection pool against its maximum", async () => {
    stubApi();
    const user = userEvent.setup();
    renderPanel();
    await user.click(await screen.findByRole("button", { name: "JVM" }));
    const table = screen.getByRole("table", { name: "JVM figures of toadie-1" });
    expect(within(table).getByText("12.3%")).toBeInTheDocument();
    expect(within(table).getByText("41")).toBeInTheDocument();
    expect(within(table).getByText("25 ms")).toBeInTheDocument();
    expect(within(table).getByText("Waiting for a database connection")).toBeInTheDocument();
    expect(within(table).getByText("2 of 20")).toBeInTheDocument();
    expect(within(table).getByText("7 of 20")).toBeInTheDocument();
  });

  test("JVM before the first sample says when it arrives", async () => {
    stubApi(currentFixture({ processes: [processFixture({ sampledAt: null })] }));
    const user = userEvent.setup();
    renderPanel();
    await user.click(await screen.findByRole("button", { name: "JVM" }));
    expect(screen.getAllByText(/^No sample yet\.$/)).toHaveLength(2);
  });

  test("history is asked only for expanded sections, and a range switch asks again for the new range", async () => {
    const fetchMock = stubApi();
    const user = userEvent.setup();
    renderPanel();
    expect(await screen.findByTestId("line-chart")).toBeInTheDocument();
    // (A set: Mantine's Collapse may mount a panel's body twice in the first render, which the query cache answers once.)
    const histories = () => [...new Set(calls(fetchMock, "/api/v1/telemetry/history"))];
    expect(histories()).toEqual(["/api/v1/telemetry/history?group=memory&range=24h"]);

    await user.click(screen.getByRole("radio", { name: "7 days" }));
    await vi.waitFor(() => expect(histories()).toContain("/api/v1/telemetry/history?group=memory&range=7d"));
    expect(screen.getByRole("radio", { name: "7 days" })).toBeChecked();

    await user.click(section("JVM"));
    await vi.waitFor(() => expect(histories()).toContain("/api/v1/telemetry/history?group=jvm&range=7d"));
    expect(histories().some((url) => url.includes("group=workspace"))).toBe(false);
  });

  test("the range and the expanded sections are remembered on this device", async () => {
    stubApi();
    const user = userEvent.setup();
    const { unmount } = renderPanel();
    await user.click(await screen.findByRole("button", { name: "Memory" }));
    await user.click(screen.getByRole("radio", { name: "90 days" }));
    await user.click(section("Workspace"));
    expect(localStorage.getItem("toadie.viewSettings.telemetry.range")).toBe('"90d"');
    expect(localStorage.getItem("toadie.viewSettings.telemetry.sections")).toBe('["workspace"]');
    unmount();

    stubApi();
    renderPanel();
    expect(await screen.findByRole("button", { name: "Workspace" })).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByRole("radio", { name: "90 days" })).toBeChecked();
    expect(section("Memory")).toHaveAttribute("aria-expanded", "false");
  });

  test("corrupt stored state falls back to 24 h with Memory open", async () => {
    localStorage.setItem("toadie.viewSettings.telemetry.range", '"1y"');
    localStorage.setItem("toadie.viewSettings.telemetry.sections", '["nope"]');
    stubApi();
    renderPanel();
    expect(await screen.findByRole("button", { name: "Memory" })).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByRole("radio", { name: "24 h" })).toBeChecked();
  });

  test("current is refetched every minute while the panel is open and not at all once it is closed", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const fetchMock = stubApi();
    const { unmount } = renderPanel();
    await screen.findByRole("heading", { level: 2, name: "Telemetry" });
    expect(calls(fetchMock, "/api/v1/telemetry/current")).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(calls(fetchMock, "/api/v1/telemetry/current")).toHaveLength(2);
    unmount();
    await vi.advanceTimersByTimeAsync(180_000);
    expect(calls(fetchMock, "/api/v1/telemetry/current")).toHaveLength(2);
  });

  test("the refresh is configured to stand still in a background tab", async () => {
    stubApi();
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    renderPanel(() => {}, queryClient);
    await screen.findByRole("heading", { level: 2, name: "Telemetry" });
    const observer = queryClient.getQueryCache().find({ queryKey: ["telemetry", "current"] })?.observers[0];
    expect(observer?.options.refetchInterval).toBe(60_000);
    expect(observer?.options.refetchIntervalInBackground).toBe(false);
  });

  test("a failed load renders inline with no sections", async () => {
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(jsonResponse(500, { title: "boom" }))));
    renderPanel();
    expect(await screen.findByRole("alert")).toHaveTextContent("Load failed (500)");
    expect(screen.queryByRole("button", { name: "Memory" })).not.toBeInTheDocument();
  });

  test("the headings form one outline: h2 title, an h3 per section, an h4 per process", async () => {
    stubApi();
    renderPanel();
    await screen.findByRole("table", { name: "Memory of toadie-1" });
    const outline = screen.getAllByRole("heading").map((heading): [number, string] => [Number(heading.tagName.slice(1)), heading.textContent ?? ""]);
    expect(outline).toEqual([
      [2, "Telemetry"],
      [3, "Memory"],
      [4, "toadie-1"],
      [3, "Workspace"],
      [3, "Pressure"],
      [3, "Requests"],
      [3, "JVM"],
    ]);
  });

  test("a failed background refresh keeps the old figures but says they may be out of date", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    let fail = false;
    vi.stubGlobal(
      "fetch",
      vi.fn((url: string) => {
        if (url.startsWith("/api/v1/telemetry/current")) return Promise.resolve(fail ? jsonResponse(429, { title: "slow down" }) : jsonResponse(200, currentFixture()));
        return Promise.resolve(jsonResponse(200, historyFixture("memory", "24h", [])));
      }),
    );
    renderPanel();
    await screen.findByRole("table", { name: "Memory of toadie-1" });
    expect(screen.queryByText("The figures could not be refreshed and may be out of date")).not.toBeInTheDocument();
    fail = true;
    await vi.advanceTimersByTimeAsync(60_000);
    expect(await screen.findByText("The figures could not be refreshed and may be out of date")).toBeInTheDocument();
    expect(screen.getByText("Load failed (429)")).toBeInTheDocument();
    expect(screen.getByRole("table", { name: "Memory of toadie-1" })).toBeInTheDocument();
  });

  test("a 429 on the first load is an inline error and no sections", async () => {
    const fetchMock = vi.fn((_url: string) => Promise.resolve(jsonResponse(429, { title: "slow down" })));
    vi.stubGlobal("fetch", fetchMock);
    renderPanel();
    expect(await screen.findByText("Load failed (429)")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Memory" })).not.toBeInTheDocument();
    // Only `current` was asked for: no section's history is requested behind a failed load.
    expect(fetchMock.mock.calls.every(([url]) => String(url).startsWith("/api/v1/telemetry/current"))).toBe(true);
  });
});
