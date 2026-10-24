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

    test("a graph over 300 nodes opens behind the panel: no layout, no canvas, no empty state", async () => {
      mockGraph(mockFetch, bigGraph(301));
      renderPage();

      expect(await panel()).toHaveTextContent("Nodes: 301, relations: 299");
      expect(screen.getByRole("button", { name: "Show top level" })).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Show everything" })).toBeInTheDocument();
      expect(screen.queryByTestId("flow")).not.toBeInTheDocument();
      expect(screen.queryByText(/nothing to render/i)).not.toBeInTheDocument();
      expect(vi.mocked(layoutPositions)).not.toHaveBeenCalled();
    });

    test("exactly 300 nodes lay out as usual", async () => {
      mockGraph(mockFetch, bigGraph(300));
      renderPage();

      await waitFor(() => expect(faces("service")).toBe(298));
      expect(screen.queryByRole("region", { name: "Large graph" })).not.toBeInTheDocument();
    });

    test("Show top level draws only the roots, and nothing it does is saved", async () => {
      mockGraph(mockFetch, bigGraph(301));
      const user = userEvent.setup();
      renderPage();

      await user.click(await screen.findByRole("button", { name: "Show top level" }));

      await waitFor(() => expect(faces("team")).toBe(2));
      expect(faces("service")).toBe(0);
      expect(screen.getByText("Top level")).toBeInTheDocument();

      // Expanding a root edits the overlay locally — no PUT of the layout document.
      await user.click(screen.getByRole("button", { name: "Expand alpha (150 hidden)" }));
      await waitFor(() => expect(faces("service")).toBe(150));
      expect(layoutPuts(mockFetch)).toEqual([]);
    });

    test("leaving the top-level view with its badge brings the panel back", async () => {
      mockGraph(mockFetch, bigGraph(301));
      const user = userEvent.setup();
      renderPage();

      await user.click(await screen.findByRole("button", { name: "Show top level" }));
      await waitFor(() => expect(faces("team")).toBe(2));
      await user.click(screen.getByRole("button", { name: "Leave the top-level view" }));

      expect(await panel()).toBeInTheDocument();
      expect(layoutPuts(mockFetch)).toEqual([]);
    });

    test("Expand all leaves the overlay too, and the panel returns", async () => {
      mockGraph(mockFetch, bigGraph(301));
      const user = userEvent.setup();
      renderPage();

      await user.click(await screen.findByRole("button", { name: "Show top level" }));
      await waitFor(() => expect(faces("team")).toBe(2));
      await user.click(screen.getByRole("button", { name: "Expand all" }));

      expect(await panel()).toBeInTheDocument();
      expect(screen.queryByText("Top level")).not.toBeInTheDocument();
      expect(layoutPuts(mockFetch)).toEqual([]);
    });

    test("Show everything draws all of it, with dagre's crossing heuristic switched off", async () => {
      mockGraph(mockFetch, bigGraph(301));
      const user = userEvent.setup();
      renderPage();

      await user.click(await screen.findByRole("button", { name: "Show everything" }));

      await waitFor(() => expect(faces("service")).toBe(299));
      expect(faces("team")).toBe(2);
      expect(screen.queryByRole("region", { name: "Large graph" })).not.toBeInTheDocument();
      const request = vi.mocked(layoutPositions).mock.calls.at(-1)?.[0];
      expect(request?.options).toEqual({ ranker: "tight-tree", disableOptimalOrderHeuristic: true });
    });

    test("a graph without a hierarchy offers only Show everything", async () => {
      const flat = bigGraph(301);
      mockGraph(mockFetch, { nodes: flat.nodes, edges: [] });
      renderPage();

      await panel();
      expect(screen.queryByRole("button", { name: "Show top level" })).not.toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Show everything" })).toBeInTheDocument();
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
