import { StrictMode } from "react";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { notifyAuthChange, useSessionUserId } from "../auth";
import type { GraphLayoutDocument } from "../api/users";
import { useGraphLayout, type LayoutView } from "./useGraphLayout";

const { getGraphLayout, setGraphLayout, getEntityGraphLayout, setEntityGraphLayout } = vi.hoisted(() => ({
  getGraphLayout: vi.fn(),
  setGraphLayout: vi.fn(),
  getEntityGraphLayout: vi.fn(),
  setEntityGraphLayout: vi.fn(),
}));

vi.mock("../api/users", async (importOriginal) => {
  const original = await importOriginal<typeof import("../api/users")>();
  return { ...original, getGraphLayout, setGraphLayout, getEntityGraphLayout, setEntityGraphLayout };
});

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

function Harness({ userId, view = "graph" }: { userId: number; view?: LayoutView }) {
  const layout = useGraphLayout(userId, view);
  return (
    <div>
      <span data-testid="phase">{layout.phase}</span>
      <span data-testid="document">{JSON.stringify(layout.document)}</span>
      <span data-testid="state">{`${layout.pending}/${layout.saving}/${layout.saveError != null}`}</span>
      <button
        type="button"
        onClick={() => layout.update((current) => ({ ...current, mode: "manual" }))}
      >
        manual
      </button>
      <button
        type="button"
        onClick={() => layout.update((current) => ({
          ...current,
          collapsed: [...current.collapsed, "system:default/shop"],
        }))}
      >
        collapse
      </button>
      <button
        type="button"
        onClick={() => layout.update((current) => ({
          ...current,
          positions: { ...current.positions, dragged: { x: 7, y: 8 } },
        }), true)}
      >
        drag
      </button>
      <button type="button" onClick={layout.retryLoad}>retry load</button>
      <button type="button" onClick={layout.retrySave}>retry save</button>
      <button
        type="button"
        onClick={() => layout.update((current) => ({ ...current, positions: {} }))}
      >
        reset
      </button>
      <button
        type="button"
        onClick={() => {
          layout.update((current) => ({ ...current, mode: "manual" }));
          layout.update((current) => ({
            ...current,
            collapsed: [...current.collapsed, "system:default/shop"],
          }));
        }}
      >
        rapid edits
      </button>
    </div>
  );
}

function SessionHarness() {
  return <Harness userId={useSessionUserId()!} />;
}

function testClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } });
}

function renderHarness(client: QueryClient, userId = 9, strict = false) {
  const child = <Harness userId={userId} />;
  return render(
    <QueryClientProvider client={client}>{strict ? <StrictMode>{child}</StrictMode> : child}</QueryClientProvider>,
  );
}

function renderBothViews(client: QueryClient, userId = 9) {
  return render(
    <QueryClientProvider client={client}>
      <div>
        <div data-testid="graph-view">
          <Harness userId={userId} view="graph" />
        </div>
        <div data-testid="entity-view">
          <Harness userId={userId} view="entityGraph" />
        </div>
      </div>
    </QueryClientProvider>,
  );
}

const BASELINE: GraphLayoutDocument = {
  mode: "auto",
  positions: { unseen: { x: 1, y: 2 } },
  collapsed: ["system:default/unseen"],
};

function tokenForSession(sid: string, userId?: number) {
  return `header.${btoa(JSON.stringify({ sid, userId }))}.signature`;
}

describe("useGraphLayout", () => {
  beforeEach(() => {
    localStorage.setItem("toadie.auth.userId", "9");
    getGraphLayout.mockReset();
    setGraphLayout.mockReset();
    getEntityGraphLayout.mockReset();
    setEntityGraphLayout.mockReset();
    // Every case gets a resolving PUT by default, overridable per case. Without this, a case
    // that configures only the GET mocks can still trigger a debounced/queued save (the hook's
    // pump() calls this.config.set(...).then(...)) after the assertions finish, and an
    // unconfigured vi.fn() returns undefined — `.then` of undefined is an unhandled rejection
    // whose timing (before or after teardown) decided whether the run flaked.
    setGraphLayout.mockResolvedValue(undefined);
    setEntityGraphLayout.mockResolvedValue(undefined);
  });

  afterEach(() => {
    vi.useRealTimers();
    localStorage.clear();
  });

  test("holds initialization through StrictMode and retries a failed GET only explicitly", async () => {
    const first = deferred<GraphLayoutDocument>();
    getGraphLayout.mockReturnValueOnce(first.promise).mockResolvedValueOnce(BASELINE);
    const client = testClient();
    const view = renderHarness(client, 9, true);

    expect(screen.getByTestId("phase")).toHaveTextContent("loading");
    expect(getGraphLayout).toHaveBeenCalledTimes(1);
    first.reject(new Error("offline"));
    await waitFor(() => expect(screen.getByTestId("phase")).toHaveTextContent("loadError"));

    view.unmount();
    renderHarness(client, 9, true);
    expect(screen.getByTestId("phase")).toHaveTextContent("loadError");
    expect(getGraphLayout).toHaveBeenCalledTimes(1);

    fireEvent.click(screen.getByRole("button", { name: "retry load" }));
    await waitFor(() => expect(screen.getByTestId("phase")).toHaveTextContent("ready"));
    expect(getGraphLayout).toHaveBeenCalledTimes(2);
  });

  test("serializes immediate saves and coalesces rapid functional edits into the newest full document", async () => {
    getGraphLayout.mockResolvedValue(BASELINE);
    const firstSave = deferred<void>();
    setGraphLayout.mockReturnValueOnce(firstSave.promise).mockResolvedValueOnce(undefined);
    renderHarness(testClient());
    await screen.findByText("ready");

    fireEvent.click(screen.getByRole("button", { name: "rapid edits" }));
    expect(setGraphLayout).toHaveBeenCalledTimes(1);
    expect(setGraphLayout.mock.calls[0]?.[1]).toEqual({ ...BASELINE, mode: "manual" });

    firstSave.resolve();
    await waitFor(() => expect(setGraphLayout).toHaveBeenCalledTimes(2));
    expect(setGraphLayout.mock.calls[1]?.[1]).toEqual({
      ...BASELINE,
      mode: "manual",
      collapsed: [...BASELINE.collapsed!, "system:default/shop"],
    });
    await waitFor(() => expect(screen.getByTestId("state")).toHaveTextContent("false/false/false"));
  });

  test("debounces drag saves for 600ms and keeps the controller alive across navigation", async () => {
    vi.useFakeTimers();
    getGraphLayout.mockResolvedValue(BASELINE);
    const save = deferred<void>();
    setGraphLayout.mockReturnValue(save.promise);
    const client = testClient();
    const view = renderHarness(client);
    await act(async () => Promise.resolve());

    fireEvent.click(screen.getByRole("button", { name: "drag" }));
    expect(screen.getByTestId("state")).toHaveTextContent("true/false/false");
    view.unmount();
    act(() => vi.advanceTimersByTime(599));
    expect(setGraphLayout).not.toHaveBeenCalled();
    act(() => vi.advanceTimersByTime(1));
    expect(setGraphLayout).toHaveBeenCalledTimes(1);

    renderHarness(client);
    expect(screen.getByTestId("document")).toHaveTextContent('"dragged":{"x":7,"y":8}');
    expect(screen.getByTestId("state")).toHaveTextContent("false/true/false");
    save.resolve();
    await act(async () => Promise.resolve());
  });

  test("a failed save retains later edits and Retry sends only the latest document", async () => {
    getGraphLayout.mockResolvedValue(BASELINE);
    const firstSave = deferred<void>();
    setGraphLayout.mockReturnValueOnce(firstSave.promise).mockResolvedValueOnce(undefined);
    renderHarness(testClient());
    await screen.findByText("ready");

    fireEvent.click(screen.getByRole("button", { name: "manual" }));
    fireEvent.click(screen.getByRole("button", { name: "collapse" }));
    firstSave.reject(new Error("offline"));
    await waitFor(() => expect(screen.getByTestId("state")).toHaveTextContent("true/false/true"));

    fireEvent.click(screen.getByRole("button", { name: "drag" }));
    expect(setGraphLayout).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole("button", { name: "retry save" }));
    await waitFor(() => expect(setGraphLayout).toHaveBeenCalledTimes(2));
    expect(setGraphLayout.mock.calls[1]?.[1]).toEqual({
      mode: "manual",
      positions: { ...BASELINE.positions, dragged: { x: 7, y: 8 } },
      collapsed: [...BASELINE.collapsed!, "system:default/shop"],
    });
  });

  test("isolates users and discards retained state at an explicit auth boundary", async () => {
    getGraphLayout.mockImplementation((id: number) => Promise.resolve({
      mode: id === 9 ? "manual" : "auto",
      positions: { [`user-${id}`]: { x: id, y: id } },
      collapsed: [],
    }));
    setGraphLayout.mockRejectedValue(new Error("offline"));
    const client = testClient();
    const first = renderHarness(client, 9);
    await screen.findByText("ready");
    fireEvent.click(screen.getByRole("button", { name: "manual" }));
    await waitFor(() => expect(screen.getByTestId("state")).toHaveTextContent("true/false/true"));
    first.unmount();

    localStorage.setItem("toadie.auth.userId", "10");
    const second = renderHarness(client, 10);
    await waitFor(() => expect(screen.getByTestId("document")).toHaveTextContent("user-10"));
    expect(screen.getByTestId("document")).not.toHaveTextContent("user-9");

    second.unmount();
    localStorage.setItem("toadie.auth.userId", "9");
    act(() => notifyAuthChange());
    renderHarness(client, 9);
    await waitFor(() => expect(getGraphLayout).toHaveBeenCalledTimes(3));
  });

  test("ignores a stale GET completion after the session boundary", async () => {
    const stale = deferred<GraphLayoutDocument>();
    getGraphLayout
      .mockReturnValueOnce(stale.promise)
      .mockResolvedValueOnce({ mode: "auto", positions: { fresh: { x: 3, y: 4 } }, collapsed: [] });
    const client = testClient();
    const first = renderHarness(client);
    expect(getGraphLayout).toHaveBeenCalledTimes(1);
    first.unmount();

    act(() => notifyAuthChange());
    renderHarness(client);
    await waitFor(() => expect(screen.getByTestId("document")).toHaveTextContent("fresh"));
    stale.resolve({ mode: "manual", positions: { stale: { x: 99, y: 99 } }, collapsed: [] });
    await act(async () => Promise.resolve());

    expect(screen.getByTestId("document")).toHaveTextContent("fresh");
    expect(screen.getByTestId("document")).not.toHaveTextContent("stale");
  });

  test("does not reuse a failed controller after another tab changes the login family", async () => {
    localStorage.setItem("toadie.auth.token", tokenForSession("family-a"));
    getGraphLayout.mockResolvedValue(BASELINE);
    setGraphLayout.mockRejectedValue(new Error("offline"));
    const client = testClient();
    const first = renderHarness(client);
    await screen.findByText("ready");
    fireEvent.click(screen.getByRole("button", { name: "manual" }));
    await waitFor(() => expect(screen.getByTestId("state")).toHaveTextContent("true/false/true"));
    first.unmount();

    localStorage.setItem("toadie.auth.token", tokenForSession("family-b"));
    window.dispatchEvent(new StorageEvent("storage", { key: "toadie.auth.token" }));
    renderHarness(client);

    await waitFor(() => expect(getGraphLayout).toHaveBeenCalledTimes(2));
    expect(screen.getByTestId("state")).toHaveTextContent("false/false/false");
  });

  test("checks the login family before a delayed drag dispatch, even before its storage event", async () => {
    vi.useFakeTimers();
    localStorage.setItem("toadie.auth.token", tokenForSession("family-a"));
    getGraphLayout.mockResolvedValue(BASELINE);
    setGraphLayout.mockResolvedValue(undefined);
    renderHarness(testClient());
    await act(async () => Promise.resolve());

    fireEvent.click(screen.getByRole("button", { name: "drag" }));
    localStorage.setItem("toadie.auth.token", tokenForSession("family-b"));
    act(() => vi.advanceTimersByTime(600));

    expect(setGraphLayout).not.toHaveBeenCalled();
  });

  test("a mounted hook follows a cross-tab account switch without an unrelated rerender", async () => {
    localStorage.setItem("toadie.auth.token", tokenForSession("family-a"));
    getGraphLayout.mockImplementation((id: number) => Promise.resolve({
      mode: "auto",
      positions: { [`user-${id}`]: { x: id, y: id } },
      collapsed: [],
    }));
    const client = testClient();
    render(<QueryClientProvider client={client}><SessionHarness /></QueryClientProvider>);
    await waitFor(() => expect(screen.getByTestId("document")).toHaveTextContent("user-9"));

    localStorage.setItem("toadie.auth.userId", "10");
    localStorage.setItem("toadie.auth.token", tokenForSession("family-b"));
    act(() => window.dispatchEvent(new StorageEvent("storage", { key: "toadie.auth.token" })));

    await waitFor(() => expect(screen.getByTestId("document")).toHaveTextContent("user-10"));
    expect(getGraphLayout.mock.calls.map(([id]) => id)).toEqual([9, 10]);
  });

  test("reacts when a cross-tab user-id storage event arrives after its token event", async () => {
    localStorage.setItem("toadie.auth.token", tokenForSession("family-a"));
    getGraphLayout.mockImplementation((id: number) => Promise.resolve({
      mode: "auto",
      positions: { [`user-${id}`]: { x: id, y: id } },
      collapsed: [],
    }));
    const client = testClient();
    render(<QueryClientProvider client={client}><SessionHarness /></QueryClientProvider>);
    await waitFor(() => expect(screen.getByTestId("document")).toHaveTextContent("user-9"));

    localStorage.setItem("toadie.auth.userId", "10");
    act(() => window.dispatchEvent(new StorageEvent("storage", { key: "toadie.auth.userId" })));

    await waitFor(() => expect(screen.getByTestId("document")).toHaveTextContent("user-10"));
    expect(getGraphLayout.mock.calls.map(([id]) => id)).toEqual([9, 10]);
  });

  test("takes the graph owner atomically from a token event before the stored user id catches up", async () => {
    localStorage.setItem("toadie.auth.token", tokenForSession("family-a", 9));
    getGraphLayout.mockImplementation((id: number) => Promise.resolve({
      mode: "auto",
      positions: { [`user-${id}`]: { x: id, y: id } },
      collapsed: [],
    }));
    const client = testClient();
    render(<QueryClientProvider client={client}><SessionHarness /></QueryClientProvider>);
    await waitFor(() => expect(screen.getByTestId("document")).toHaveTextContent("user-9"));

    // persistSession writes the atomic token before its compatibility userId key.
    localStorage.setItem("toadie.auth.token", tokenForSession("family-b", 10));
    act(() => window.dispatchEvent(new StorageEvent("storage", { key: "toadie.auth.token" })));
    await waitFor(() => expect(screen.getByTestId("document")).toHaveTextContent("user-10"));
    expect(localStorage.getItem("toadie.auth.userId")).toBe("9");
    expect(getGraphLayout.mock.calls.map(([id]) => id)).toEqual([9, 10]);

    localStorage.setItem("toadie.auth.userId", "10");
    act(() => window.dispatchEvent(new StorageEvent("storage", { key: "toadie.auth.userId" })));
    expect(getGraphLayout.mock.calls.map(([id]) => id)).toEqual([9, 10]);
  });

  test("retains its cache anchor beyond the query cache default GC while mounted", async () => {
    vi.useFakeTimers();
    getGraphLayout.mockResolvedValue(BASELINE);
    setGraphLayout.mockResolvedValue(undefined);
    renderHarness(testClient());
    await act(async () => Promise.resolve());

    act(() => vi.advanceTimersByTime(5 * 60_000 + 1));
    fireEvent.click(screen.getByRole("button", { name: "manual" }));
    expect(getGraphLayout).toHaveBeenCalledTimes(1);
    expect(setGraphLayout).toHaveBeenCalledTimes(1);
  });

  test("a clean remount refreshes the baseline after the StrictMode cleanup grace", async () => {
    vi.useFakeTimers();
    getGraphLayout
      .mockResolvedValueOnce(BASELINE)
      .mockResolvedValueOnce({ mode: "manual", positions: { refreshed: { x: 5, y: 6 } }, collapsed: [] });
    const client = testClient();
    const first = renderHarness(client);
    await act(async () => Promise.resolve());
    first.unmount();
    act(() => vi.advanceTimersByTime(0));

    renderHarness(client);
    await act(async () => Promise.resolve());
    expect(getGraphLayout).toHaveBeenCalledTimes(2);
    expect(screen.getByTestId("document")).toHaveTextContent("refreshed");
  });

  test("an immediate reset supersedes a pending debounced drag", async () => {
    vi.useFakeTimers();
    getGraphLayout.mockResolvedValue(BASELINE);
    setGraphLayout.mockResolvedValue(undefined);
    renderHarness(testClient());
    await act(async () => Promise.resolve());

    fireEvent.click(screen.getByRole("button", { name: "drag" }));
    fireEvent.click(screen.getByRole("button", { name: "reset" }));
    expect(setGraphLayout).toHaveBeenCalledTimes(1);
    expect(setGraphLayout.mock.calls[0]?.[1]).toEqual({ ...BASELINE, positions: {} });
    act(() => vi.advanceTimersByTime(600));
    expect(setGraphLayout).toHaveBeenCalledTimes(1);
  });

  test("retains the newest failed document across an SPA unmount and remount", async () => {
    getGraphLayout.mockResolvedValue(BASELINE);
    setGraphLayout.mockRejectedValue(new Error("offline"));
    const client = testClient();
    const first = renderHarness(client);
    await screen.findByText("ready");
    fireEvent.click(screen.getByRole("button", { name: "rapid edits" }));
    await waitFor(() => expect(screen.getByTestId("state")).toHaveTextContent("true/false/true"));
    first.unmount();

    renderHarness(client);
    expect(screen.getByTestId("state")).toHaveTextContent("true/false/true");
    expect(screen.getByTestId("document")).toHaveTextContent("system:default/shop");
    expect(getGraphLayout).toHaveBeenCalledTimes(1);
  });

  test("a mounted hook reacquires and reloads after an explicit query cache clear", async () => {
    getGraphLayout.mockResolvedValue(BASELINE);
    const client = testClient();
    renderHarness(client);
    await screen.findByText("ready");

    act(() => client.clear());
    await waitFor(() => expect(getGraphLayout).toHaveBeenCalledTimes(2));
    expect(screen.getByTestId("phase")).toHaveTextContent("ready");
  });

  test("the graph and entityGraph views run independent controllers over the same user, hitting their own endpoints", async () => {
    getGraphLayout.mockResolvedValue(BASELINE);
    const entityBaseline: GraphLayoutDocument = { mode: "auto", positions: { one: { x: 1, y: 1 } }, collapsed: [] };
    getEntityGraphLayout.mockResolvedValue(entityBaseline);
    const client = testClient();
    renderBothViews(client);

    const graphView = within(screen.getByTestId("graph-view"));
    const entityView = within(screen.getByTestId("entity-view"));
    await waitFor(() => expect(graphView.getByTestId("phase")).toHaveTextContent("ready"));
    await waitFor(() => expect(entityView.getByTestId("phase")).toHaveTextContent("ready"));
    expect(getGraphLayout).toHaveBeenCalledTimes(1);
    expect(getEntityGraphLayout).toHaveBeenCalledTimes(1);
    expect(graphView.getByTestId("document")).toHaveTextContent("unseen");
    expect(entityView.getByTestId("document")).not.toHaveTextContent("unseen");

    fireEvent.click(graphView.getByRole("button", { name: "manual" }));
    await waitFor(() => expect(setGraphLayout).toHaveBeenCalledTimes(1));
    expect(setEntityGraphLayout).not.toHaveBeenCalled();

    fireEvent.click(entityView.getByRole("button", { name: "collapse" }));
    await waitFor(() => expect(setEntityGraphLayout).toHaveBeenCalledTimes(1));
    expect(setGraphLayout).toHaveBeenCalledTimes(1);
    // The entity view's document is unaffected by the graph view's edit, and vice versa.
    expect(entityView.getByTestId("document")).not.toHaveTextContent("manual");
    expect(graphView.getByTestId("document")).not.toHaveTextContent("system:default/shop");
  });

  test("the entityGraph view's cache anchor is its own controllerKey — clearing one view's query does not touch the other", async () => {
    getGraphLayout.mockResolvedValue(BASELINE);
    getEntityGraphLayout.mockResolvedValue({ mode: "auto", positions: {}, collapsed: [] });
    const client = testClient();
    renderBothViews(client);
    await waitFor(() => expect(getGraphLayout).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(getEntityGraphLayout).toHaveBeenCalledTimes(1));

    client.removeQueries({ queryKey: ["entityGraphLayoutController", 9], exact: true });
    await waitFor(() => expect(getEntityGraphLayout).toHaveBeenCalledTimes(2));
    expect(getGraphLayout).toHaveBeenCalledTimes(1);
  });
});
