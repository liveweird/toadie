import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { act, screen, waitFor } from "@testing-library/react";
import { jsonResponse } from "../test/http";
import { blueprintResponse } from "../test/fixtures";
import { fitViewCalls } from "../test/reactFlowStub";
import { FakeWorker } from "../test/fakeWorker";

// React Flow needs real DOM measurement happy-dom can't give — the shared stub (see
// EntityGraph.test.tsx). The pure shaping is covered by its own unit tests.
vi.mock("@xyflow/react", () => import("../test/reactFlowStub"));

// The query bar's CodeMirror editor is covered by its own tests; a plain textarea stands in.
vi.mock("../components/QueryEditor", () => ({
  default: ({ value, onChange, ariaLabel }: { value: string; onChange: (value: string) => void; ariaLabel: string }) => (
    <textarea aria-label={ariaLabel} value={value} onChange={(e) => onChange(e.target.value)} />
  ),
}));

// The dagre half of the layout is spied so the tests can pin WHAT re-runs it: only structural
// inputs (graph, chips, fold, hierarchy, Focus) — never the layout document, the blueprint
// registry's tiers, or other decoration.
vi.mock("../utils/graphLayout", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../utils/graphLayout")>();
  return { ...actual, layoutPositions: vi.fn(actual.layoutPositions) };
});

import { layoutPositions } from "../utils/graphLayout";
import { GRAPH, layoutPuts, mockGraph, renderPage, TOKEN_KEY, type FetchMock } from "../test/entityGraphPage";

// The Entity graph's layout pipeline: what re-runs dagre, the worker round trip, and the
// large-graph gate (EntityGraph.test.tsx holds the page's other behaviour).
describe("EntityGraph layout", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem(TOKEN_KEY, "fake-token");
    localStorage.setItem("toadie.auth.userId", "9");
    fitViewCalls.length = 0;
    vi.mocked(layoutPositions).mockClear();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  describe("the layout re-runs only on structural inputs", () => {
    const layoutRuns = () => vi.mocked(layoutPositions).mock.calls.length;
    const flush = () => act(async () => {});

    /** Holds the GETs of the URLs [matching] until the returned release is called. */
    function holdGets(matching: (url: string) => boolean): () => void {
      let release!: () => void;
      const gate = new Promise<void>((resolve) => {
        release = resolve;
      });
      const base = mockFetch.getMockImplementation() as unknown as (url: string, init?: RequestInit) => Promise<Response>;
      mockFetch.mockImplementation((url: string, init?: RequestInit) =>
        matching(url) && init?.method !== "PUT" ? gate.then(() => base(url, init)) : base(url, init),
      );
      return release;
    }

    test("the layout document arriving after the graph does not re-run it", async () => {
      mockGraph(mockFetch);
      const release = holdGets((url) => url === "/api/v1/users/9/entity-graph-layout");
      renderPage();

      await screen.findByText(/checkout \[service\]/);
      await flush();
      const before = layoutRuns();
      expect(before).toBeGreaterThan(0);
      expect(screen.getByRole("radio", { name: "Manual" })).toBeDisabled();

      release();
      await waitFor(() => expect(screen.getByRole("radio", { name: "Manual" })).toBeEnabled());
      await flush();
      expect(layoutRuns()).toBe(before);
    });

    test("a late blueprint registry (tier dots only) does not re-run it", async () => {
      mockGraph(mockFetch, GRAPH, 200, undefined, undefined, [], [
        blueprintResponse({ id: 1, identifier: "team", title: "Team", tiers: { blueprint: 1 } }),
        blueprintResponse({ id: 2, identifier: "service", title: "Service", tiers: { blueprint: 2 } }),
      ]);
      const release = holdGets((url) => url.startsWith("/api/v1/blueprints"));
      renderPage();

      await screen.findByText(/checkout \[service\]/);
      await flush();
      const before = layoutRuns();

      release();
      await waitFor(() =>
        expect(mockFetch.mock.calls.some(([url]) => String(url).startsWith("/api/v1/blueprints"))).toBe(true),
      );
      await flush();
      await flush();
      expect(layoutRuns()).toBe(before);
    });

    test("a relation chip toggle re-runs it", async () => {
      mockGraph(mockFetch);
      const user = userEvent.setup();
      renderPage();

      await screen.findByText(/checkout \[service\]/);
      await flush();
      const before = layoutRuns();
      await user.click(screen.getByRole("button", { name: /^Visibility/ }));
      await user.click(screen.getByRole("checkbox", { name: "peer" }));
      expect(layoutRuns()).toBeGreaterThan(before);
    });

    test("a fold toggle re-runs it", async () => {
      mockGraph(mockFetch);
      const user = userEvent.setup();
      renderPage();

      await screen.findByText(/checkout \[service\]/);
      await flush();
      const before = layoutRuns();
      await user.click(screen.getByRole("button", { name: "Collapse platform" }));
      await waitFor(() => expect(layoutRuns()).toBeGreaterThan(before));
    });

    test("a hierarchy pick re-runs it", async () => {
      mockGraph(mockFetch);
      const user = userEvent.setup();
      renderPage();

      await screen.findByText(/checkout \[service\]/);
      await flush();
      const before = layoutRuns();
      await user.click(screen.getByLabelText("Hierarchy", { selector: "input" }));
      await user.click(await screen.findByRole("option", { name: "cost-center" }));
      await waitFor(() => expect(layoutRuns()).toBeGreaterThan(before));
    });

    test("a Focus change re-runs it", async () => {
      localStorage.setItem("toadie.viewSettings.entityGraph.filter.focusTier", "1");
      mockGraph(mockFetch, GRAPH, 200, undefined, undefined, [], [
        blueprintResponse({ id: 1, identifier: "team", title: "Team", tiers: { blueprint: 1 } }),
        blueprintResponse({
          id: 2,
          identifier: "service",
          title: "Service",
          tiers: { blueprint: 1, relations: { peer: 1, team: 2 } },
        }),
      ]);
      const user = userEvent.setup();
      renderPage();

      await screen.findByText(/checkout \[service\]/);
      await flush();
      const before = layoutRuns();
      await user.click(screen.getByRole("button", { name: /^Filters/ }));
      await user.click(screen.getByRole("combobox", { name: "Focus" }));
      await user.click(await screen.findByRole("option", { name: "Tiers 1–2" }));
      await waitFor(() => expect(layoutRuns()).toBeGreaterThan(before));
    });
  });

  describe("the layout worker", () => {
    beforeEach(() => {
      FakeWorker.reset();
      vi.stubGlobal("Worker", FakeWorker);
    });

    /** Answers whatever the page has posted so far, until the canvas is up. */
    async function answerUntilDrawn() {
      await waitFor(() => {
        act(() => FakeWorker.instances.forEach((w) => w.answerPending()));
        expect(screen.getByTestId("flow")).toBeInTheDocument();
      });
    }

    test("shows a named 'Laying out' status instead of the canvas until the worker answers", async () => {
      mockGraph(mockFetch);
      renderPage();

      const status = await screen.findByText("Laying out the graph…");
      expect(status).toHaveAttribute("role", "status");
      expect(screen.queryByTestId("flow")).not.toBeInTheDocument();
      expect(screen.queryByText(/nothing to render/i)).not.toBeInTheDocument();

      await answerUntilDrawn();
      expect(await screen.findByText(/checkout \[service\]/)).toBeInTheDocument();
      expect(screen.queryByText("Laying out the graph…")).not.toBeInTheDocument();
    });

    test("keeps the previous canvas while a newer layout computes, then swaps it in and re-fits", async () => {
      mockGraph(mockFetch);
      const user = userEvent.setup();
      renderPage();
      await answerUntilDrawn();
      await screen.findByText(/checkout \[service\]/);

      await user.click(screen.getByRole("button", { name: "Collapse platform" }));

      // The new layout is pending (unanswered): the old nodes are still on screen, with no
      // spinner in their place.
      expect(screen.getByText(/checkout \[service\]/)).toBeInTheDocument();
      expect(screen.queryByText("Laying out the graph…")).not.toBeInTheDocument();

      fitViewCalls.length = 0;
      act(() => FakeWorker.instances.forEach((w) => w.answerPending()));
      await waitFor(() => expect(screen.queryByText(/checkout \[service\]/)).not.toBeInTheDocument());
      // The fit follows the new node set reaching the canvas, not the request being posted.
      await waitFor(() => expect(fitViewCalls.at(-1)).toEqual(["service|solo", "team|platform"]));
    });

    test("a failed layout shows the narrowing hint with a retry that resubmits", async () => {
      mockGraph(mockFetch);
      const user = userEvent.setup();
      renderPage();
      await screen.findByText("Laying out the graph…");
      await waitFor(() => expect(FakeWorker.instances.at(-1)?.posted.length).toBeGreaterThan(0));
      const worker = FakeWorker.instances.at(-1)!;
      act(() => worker.reply({ id: worker.posted[0].id, error: "boom" }));

      expect(await screen.findByText("The graph layout did not finish")).toBeInTheDocument();
      expect(screen.getByText(/Narrow the filters or try again/)).toBeInTheDocument();
      expect(screen.queryByTestId("flow")).not.toBeInTheDocument();

      await user.click(screen.getByRole("button", { name: "Try again" }));
      await answerUntilDrawn();
      expect(await screen.findByText(/checkout \[service\]/)).toBeInTheDocument();
    });
  });

  describe("the large-graph gate", () => {
    /** Two roots (`alpha`, `beta`) and `total - 2` services split between them. */
    function bigGraph(total: number) {
      const roots = ["alpha", "beta"];
      const leaves = total - roots.length;
      return {
        nodes: [
          ...roots.map((id, i) => ({
            id: `team|${id}`, entityId: i + 1, blueprint: "team", blueprintTitle: "Team", identifier: id, title: id, findings: 0,
          })),
          ...Array.from({ length: leaves }, (_, i) => ({
            id: `service|s${i}`, entityId: 100 + i, blueprint: "service", blueprintTitle: "Service", identifier: `s${i}`, title: `S${i}`, findings: 0,
          })),
        ],
        edges: Array.from({ length: leaves }, (_, i) => ({
          sourceId: `service|s${i}`, targetId: `team|${roots[i % 2]}`, relation: "team", hierarchies: ["composition"],
        })),
      };
    }
    const faces = (blueprint: string) => screen.queryAllByText(new RegExp(String.raw`\[${blueprint}\]`)).length;
    const panel = () => screen.findByRole("region", { name: "Large graph" });

    test("a graph over 300 nodes opens on the top-level overlay: only the roots are laid out, nothing is saved", async () => {
      mockGraph(mockFetch, bigGraph(301));
      renderPage();

      await waitFor(() => expect(faces("team")).toBe(2));
      expect(faces("service")).toBe(0);
      expect(screen.queryByRole("region", { name: "Large graph" })).not.toBeInTheDocument();
      expect(screen.getByText("Top level")).toBeInTheDocument();
      expect(screen.getByTestId("flow")).toHaveAttribute("data-drawn-nodes", "2");
      // dagre never saw the 301-node graph, and the overlay is not written to the layout document.
      expect(vi.mocked(layoutPositions).mock.calls.every(([request]) => request.nodes.length === 2)).toBe(true);
      expect(layoutPuts(mockFetch)).toEqual([]);
    });

    test("a graph whose top level is still over 300 nodes keeps the panel: no layout, no canvas, no empty state", async () => {
      // 301 teams with one service each: 602 nodes, and the top level still draws 301.
      const teams = Array.from({ length: 301 }, (_, i) => `t${i}`);
      mockGraph(mockFetch, {
        nodes: [
          ...teams.map((id, i) => ({
            id: `team|${id}`, entityId: i + 1, blueprint: "team", blueprintTitle: "Team", identifier: id, title: id, findings: 0,
          })),
          ...teams.map((id, i) => ({
            id: `service|s-${id}`, entityId: 1000 + i, blueprint: "service", blueprintTitle: "Service", identifier: `s-${id}`, title: `S-${id}`, findings: 0,
          })),
        ],
        edges: teams.map((id) => ({
          sourceId: `service|s-${id}`, targetId: `team|${id}`, relation: "team", hierarchies: ["composition"],
        })),
      });
      renderPage();

      expect(await panel()).toHaveTextContent("Nodes: 602, relations: 301");
      expect(screen.getByRole("button", { name: "Show top level" })).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Show everything" })).toBeInTheDocument();
      expect(screen.queryByTestId("flow")).not.toBeInTheDocument();
      expect(screen.queryByText(/nothing to render/i)).not.toBeInTheDocument();
      expect(vi.mocked(layoutPositions)).not.toHaveBeenCalled();
    });

    test("exactly 300 nodes lay out as usual, with the 0.05 zoom floor", async () => {
      mockGraph(mockFetch, bigGraph(300));
      renderPage();

      await waitFor(() => expect(faces("service")).toBe(298));
      expect(screen.queryByRole("region", { name: "Large graph" })).not.toBeInTheDocument();
      expect(screen.queryByText("Top level")).not.toBeInTheDocument();
      // At the limit (not over it) every node is mounted.
      expect(screen.getByTestId("flow")).toHaveAttribute("data-virtualized", "false");
      expect(screen.getByTestId("flow")).toHaveAttribute("data-drawn-nodes", "300");
      expect(screen.getByTestId("flow")).toHaveAttribute("data-min-zoom", "0.05");
    });

    test("expanding a root edits the overlay locally: no PUT of the layout document", async () => {
      mockGraph(mockFetch, bigGraph(301));
      const user = userEvent.setup();
      renderPage();

      await waitFor(() => expect(faces("team")).toBe(2));
      await user.click(screen.getByRole("button", { name: "Expand alpha (150 hidden)" }));
      await waitFor(() => expect(faces("service")).toBe(150));
      expect(layoutPuts(mockFetch)).toEqual([]);
    });

    test("leaving the top-level view with its badge brings the panel, and Show top level returns", async () => {
      mockGraph(mockFetch, bigGraph(301));
      const user = userEvent.setup();
      renderPage();

      await waitFor(() => expect(faces("team")).toBe(2));
      await user.click(screen.getByRole("button", { name: "Leave the top-level view" }));

      expect(await panel()).toBeInTheDocument();
      expect(layoutPuts(mockFetch)).toEqual([]);

      await user.click(screen.getByRole("button", { name: "Show top level" }));
      await waitFor(() => expect(faces("team")).toBe(2));
      expect(screen.queryByRole("region", { name: "Large graph" })).not.toBeInTheDocument();
    });

    test("Expand all leaves the overlay too, and the panel returns", async () => {
      mockGraph(mockFetch, bigGraph(301));
      const user = userEvent.setup();
      renderPage();

      await waitFor(() => expect(faces("team")).toBe(2));
      await user.click(screen.getByRole("button", { name: "Expand all" }));

      expect(await panel()).toBeInTheDocument();
      expect(screen.queryByText("Top level")).not.toBeInTheDocument();
      expect(layoutPuts(mockFetch)).toEqual([]);
    });

    test("Expand all on the default overlay only leaves it: the saved fold is neither cleared nor PUT", async () => {
      // 700 nodes; the saved fold hides beta's 349 services (351 left, still over 300), so the
      // top level (two teams) opens by default.
      mockGraph(mockFetch, bigGraph(700), 200, { mode: "auto", positions: {}, collapsed: ["team|beta"] });
      const user = userEvent.setup();
      renderPage();

      await waitFor(() => expect(faces("team")).toBe(2));
      expect(screen.getByText("Top level")).toBeInTheDocument();
      await user.click(screen.getByRole("button", { name: "Expand all" }));

      expect(await panel()).toHaveTextContent("Nodes: 351");
      expect(screen.queryByText("Top level")).not.toBeInTheDocument();
      expect(layoutPuts(mockFetch)).toEqual([]);
    });

    test("a saved fold arriving late never flashes the default overlay or lays it out", async () => {
      // The layout GET is held: the fold it carries brings 301 nodes down to 151.
      let release: (value: Response) => void = () => {};
      mockGraph(mockFetch, bigGraph(301));
      const base = mockFetch.getMockImplementation() as unknown as (url: string, init?: RequestInit) => Promise<Response>;
      mockFetch.mockImplementation((url: string, init?: RequestInit) =>
        url === "/api/v1/users/9/entity-graph-layout" && init?.method !== "PUT"
          ? new Promise<Response>((resolve) => {
              release = resolve;
            })
          : base(url, init),
      );
      renderPage();

      expect(await panel()).toHaveTextContent("Nodes: 301");
      expect(screen.queryByText("Top level")).not.toBeInTheDocument();
      expect(vi.mocked(layoutPositions)).not.toHaveBeenCalled();

      act(() => release(jsonResponse(200, { mode: "auto", positions: {}, collapsed: ["team|alpha"] })));
      await waitFor(() => expect(faces("team") + faces("service")).toBe(151));
      expect(screen.queryByText("Top level")).not.toBeInTheDocument();
      expect(vi.mocked(layoutPositions).mock.calls.every(([request]) => request.nodes.length === 151)).toBe(true);
    });

    test("an expand over the limit offers Show top level again, and it returns to the roots", async () => {
      mockGraph(mockFetch, bigGraph(301));
      const user = userEvent.setup();
      renderPage();

      await waitFor(() => expect(faces("team")).toBe(2));
      await user.click(screen.getByRole("button", { name: "Expand alpha (150 hidden)" }));
      await waitFor(() => expect(faces("service")).toBe(150));
      await user.click(screen.getByRole("button", { name: "Expand beta (149 hidden)" }));
      expect(await panel()).toHaveTextContent("Nodes: 301");
      await user.click(screen.getByRole("button", { name: "Show top level" }));
      await waitFor(() => expect(faces("team")).toBe(2));
      expect(faces("service")).toBe(0);
      expect(layoutPuts(mockFetch)).toEqual([]);
    });

    test("Show everything, reached through the panel, draws all of it with dagre's crossing heuristic switched off", async () => {
      mockGraph(mockFetch, bigGraph(301));
      const user = userEvent.setup();
      renderPage();

      await waitFor(() => expect(faces("team")).toBe(2));
      await user.click(screen.getByRole("button", { name: "Leave the top-level view" }));
      await user.click(await screen.findByRole("button", { name: "Show everything" }));

      await waitFor(() => expect(faces("service")).toBe(299));
      expect(faces("team")).toBe(2);
      expect(screen.queryByRole("region", { name: "Large graph" })).not.toBeInTheDocument();
      expect(screen.queryByText("Top level")).not.toBeInTheDocument();
      const request = vi.mocked(layoutPositions).mock.calls.at(-1)?.[0];
      expect(request?.options).toEqual({ ranker: "tight-tree", disableOptimalOrderHeuristic: true });
      // Only a canvas that needed the gate mounts just what is in view.
      expect(screen.getByTestId("flow")).toHaveAttribute("data-virtualized", "true");
      expect(screen.getByTestId("flow")).toHaveAttribute("data-drawn-nodes", "301");
    });

    test("a graph without a hierarchy keeps the panel and offers only Show everything", async () => {
      const flat = bigGraph(301);
      mockGraph(mockFetch, { nodes: flat.nodes, edges: [] });
      renderPage();

      await panel();
      expect(screen.queryByRole("button", { name: "Show top level" })).not.toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Show everything" })).toBeInTheDocument();
      expect(vi.mocked(layoutPositions)).not.toHaveBeenCalled();
    });
  });

  test("a 400 on saving the layout names the cap and keeps Retry", async () => {
    mockGraph(mockFetch);
    const base = mockFetch.getMockImplementation() as unknown as (url: string, init?: RequestInit) => Promise<Response>;
    mockFetch.mockImplementation((url: string, init?: RequestInit) =>
      url === "/api/v1/users/9/entity-graph-layout" && init?.method === "PUT"
        ? Promise.resolve(jsonResponse(400, { title: "Bad Request", status: 400 }))
        : base(url, init),
    );
    const user = userEvent.setup();
    renderPage();

    await screen.findByText(/checkout \[service\]/);
    await user.click(screen.getByRole("radio", { name: "Manual" }));

    expect(await screen.findByText("Failed to save the layout")).toBeInTheDocument();
    expect(screen.getByText(/Too many saved positions or collapsed nodes/)).toBeInTheDocument();
    expect(screen.queryByText("Save failed (400)")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Retry saving layout" })).toBeInTheDocument();
  });
});
