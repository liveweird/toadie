import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { fireEvent, screen, waitFor } from "@testing-library/react";
import { Route, Routes, useLocation } from "react-router-dom";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";
import type { LaidOutNode } from "../utils/graphLayout";

// React Flow needs real DOM measurement (ResizeObserver, bounding boxes) that happy-dom can't
// give — the canvas is stubbed to a list of node buttons; the real rendering is e2e's job.
// The pure shaping (filterGraph/layoutGraph) is covered in graphLayout.test.ts. The stub
// ignores props it doesn't render — every prop the page relies on must be surfaced here
// explicitly (draggability as a data attribute, positions as spans, drags as buttons that
// replay React Flow's drag event sequence, click-after-drag included).
vi.mock("@xyflow/react", async () => {
  const { useState } = await import("react");

  function useNodesState<T>(initial: T[]) {
    const [nodes, setNodes] = useState(initial);
    return [nodes, setNodes, () => {}] as const;
  }

  function useEdgesState<T>(initial: T[]) {
    const [edges, setEdges] = useState(initial);
    return [edges, setEdges, () => {}] as const;
  }

  function applyNodeChanges<T extends { id: string; position?: { x: number; y: number } }>(
    changes: { type: string; id?: string; position?: { x: number; y: number } }[],
    nodes: T[],
  ): T[] {
    let next = nodes;
    for (const change of changes) {
      if (change.type !== "position" || !change.id || !change.position) continue;
      next = next.map((n) => (n.id === change.id ? { ...n, position: change.position! } : n));
    }
    return next;
  }

  return {
    applyNodeChanges,
    useNodesState,
    useEdgesState,
    ReactFlow: ({
      nodes,
      nodesDraggable,
      onNodesChange,
      onNodeDragStart,
      onNodeDragStop,
      onNodeClick,
      children,
    }: {
      nodes: LaidOutNode[];
      nodesDraggable?: boolean;
      onNodesChange?: (changes: unknown[]) => void;
      onNodeDragStart?: () => void;
      onNodeDragStop?: () => void;
      onNodeClick?: (event: unknown, node: LaidOutNode) => void;
      children?: React.ReactNode;
    }) => (
      <div data-testid="flow" data-draggable={String(nodesDraggable ?? true)}>
        {/* The canvas overlays (namespace frames, Background, Controls) are children. */}
        {children}
        {nodes.map((n) => (
          <div key={n.id}>
            <button type="button" onClick={(e) => onNodeClick?.(e, n)}>
              {n.data.apiNode.name} [{n.data.apiNode.status}]
            </button>
            <span data-testid={`pos:${n.id}`}>{`${n.position.x},${n.position.y}`}</span>
            <button
              type="button"
              data-testid={`drag:${n.id}`}
              onClick={(e) => {
                onNodeDragStart?.();
                onNodesChange?.([
                  { type: "position", id: n.id, position: { x: 111, y: 222 }, dragging: true },
                ]);
                onNodesChange?.([
                  { type: "position", id: n.id, position: { x: 111, y: 222 }, dragging: false },
                ]);
                onNodeDragStop?.();
                // React Flow fires the click after a drag gesture too — the page must swallow it.
                onNodeClick?.(e, n);
              }}
            >
              drag {n.id}
            </button>
          </div>
        ))}
        {/* A multi-select drag ends SEVERAL nodes in ONE changes batch — the page must
            accumulate them into a single persisted map, not last-write-wins. */}
        <button
          type="button"
          data-testid="drag-multi"
          onClick={() => {
            onNodeDragStart?.();
            onNodesChange?.([
              { type: "position", id: nodes[0]?.id, position: { x: 11, y: 12 }, dragging: false },
              { type: "position", id: nodes[1]?.id, position: { x: 21, y: 22 }, dragging: false },
            ]);
            onNodeDragStop?.();
          }}
        >
          drag multi
        </button>
      </div>
    ),
    Background: () => null,
    Controls: () => null,
    Handle: () => null,
    Position: { Left: "left", Right: "right" },
    // The namespace frames render through the viewport portal; here it is just a passthrough
    // so the frames show up as ordinary DOM and can be asserted on.
    ViewportPortal: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  };
});

import RenderGraph from "./RenderGraph";

const TOKEN_KEY = "toadie.auth.token";

type FetchMock = ReturnType<typeof vi.fn>;

const GRAPH = {
  nodes: [
    { id: "component:default/a", kind: "component", namespace: "default", name: "svc-a", title: null, fileId: 7, status: "STORED" },
    { id: "component:default/ghost", kind: "component", namespace: "default", name: "ghost", title: null, fileId: null, status: "MISSING" },
    { id: "group:default/team-x", kind: "group", namespace: "default", name: "team-x", title: null, fileId: null, status: "MISSING" },
  ],
  edges: [
    { sourceId: "component:default/a", targetId: "component:default/ghost", field: "spec.dependsOn" },
    { sourceId: "component:default/a", targetId: "group:default/team-x", field: "spec.owner" },
  ],
};

// The namespace filter combo loads its options from the namespaces dictionary.
const NAMESPACE_ENTRIES = {
  items: [
    { id: 1, value: "default", isDefault: true },
    { id: 2, value: "team-a", isDefault: false },
  ],
};

function mockGraph(
  mockFetch: FetchMock,
  body: unknown = GRAPH,
  status = 200,
  layout: unknown = { mode: "auto", positions: {} },
) {
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    if (url.startsWith("/api/v1/dictionaries/namespaces"))
      return Promise.resolve(jsonResponse(200, NAMESPACE_ENTRIES));
    if (url === "/api/v1/users/9/graph-layout") {
      return init?.method === "PUT"
        ? Promise.resolve(new Response(null, { status: 204 }))
        : Promise.resolve(jsonResponse(200, layout));
    }
    return url.startsWith("/api/v1/files/graph")
      ? Promise.resolve(jsonResponse(status, body))
      : Promise.resolve(jsonResponse(404, {}));
  });
}

/** Every layout PUT's parsed body, in call order. */
function layoutPuts(mockFetch: FetchMock): unknown[] {
  return mockFetch.mock.calls
    .filter(
      ([url, init]) =>
        url === "/api/v1/users/9/graph-layout" && (init as RequestInit | undefined)?.method === "PUT",
    )
    .map(([, init]) => JSON.parse((init as RequestInit).body as string));
}

function PathProbe() {
  const location = useLocation();
  return <div data-testid="probe">{location.pathname}</div>;
}

function renderPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/graph" element={<RenderGraph />} />
      <Route path="/files/:id/edit" element={<PathProbe />} />
    </Routes>,
    { route: "/graph" },
  );
}

describe("RenderGraph page", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem(TOKEN_KEY, "fake-token");
    // The layout document is loaded and saved per user — the mock serves user 9.
    localStorage.setItem("toadie.auth.userId", "9");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("renders the graph's nodes with their statuses", async () => {
    mockGraph(mockFetch);
    renderPage();

    expect(await screen.findByText(/svc-a \[STORED\]/)).toBeInTheDocument();
    expect(screen.getByText(/ghost \[MISSING\]/)).toBeInTheDocument();
    expect(screen.getByText(/team-x \[MISSING\]/)).toBeInTheDocument();
  });

  test("the namespace filter refetches with namespace=", async () => {
    mockGraph(mockFetch);
    renderPage();

    await screen.findByText(/svc-a/);
    // The filter set lives behind the collapsed FilterPanel now (the Files list's surface).
    fireEvent.click(screen.getByRole("button", { name: /Filters/ }));
    fireEvent.click(screen.getByLabelText("Namespace", { selector: "input" }));
    fireEvent.click(await screen.findByRole("option", { name: "team-a" }));

    await waitFor(() => {
      const called = mockFetch.mock.calls.some(
        ([url]) => typeof url === "string" && url.includes("graph?namespace=team-a"),
      );
      expect(called).toBe(true);
    });
  });

  test("toggling a relation family off prunes its virtual nodes", async () => {
    mockGraph(mockFetch);
    const user = userEvent.setup();
    renderPage();

    await screen.findByText(/team-x \[MISSING\]/);
    await user.click(screen.getByRole("checkbox", { name: "Owned by" }));

    expect(screen.queryByText(/team-x/)).not.toBeInTheDocument();
    // The other family's virtual node and the stored node stay.
    expect(screen.getByText(/ghost \[MISSING\]/)).toBeInTheDocument();
    expect(screen.getByText(/svc-a \[STORED\]/)).toBeInTheDocument();
  });

  test("clicking a stored node opens its editor; virtual nodes don't navigate", async () => {
    mockGraph(mockFetch);
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByText(/ghost \[MISSING\]/));
    expect(screen.queryByTestId("probe")).not.toBeInTheDocument();

    await user.click(screen.getByText(/svc-a \[STORED\]/));
    await waitFor(() =>
      expect(screen.getByTestId("probe")).toHaveTextContent("/files/7/edit"),
    );
  });

  test("an empty workspace shows the empty state instead of the canvas", async () => {
    mockGraph(mockFetch, { nodes: [], edges: [] });
    renderPage();

    expect(await screen.findByText(/nothing to render/i)).toBeInTheDocument();
    expect(screen.queryByTestId("flow")).not.toBeInTheDocument();
  });

  test("shows an alert when the graph fails to load", async () => {
    mockGraph(mockFetch, { title: "boom", status: 500 }, 500);
    renderPage();

    expect(await screen.findByText("Failed to load the graph")).toBeInTheDocument();
  });

  test("switching to Manual persists the mode, enables dragging, and reveals Reset", async () => {
    mockGraph(mockFetch);
    const user = userEvent.setup();
    renderPage();

    await screen.findByText(/svc-a/);
    expect(screen.getByTestId("flow")).toHaveAttribute("data-draggable", "false");
    expect(screen.queryByRole("button", { name: "Reset layout" })).not.toBeInTheDocument();

    await user.click(screen.getByRole("radio", { name: "Manual" }));

    expect(screen.getByTestId("flow")).toHaveAttribute("data-draggable", "true");
    expect(screen.getByRole("button", { name: "Reset layout" })).toBeInTheDocument();
    await waitFor(() => expect(layoutPuts(mockFetch)).toEqual([{ mode: "manual", positions: {} }]));
  });

  test("a drag moves the node, persists the FULL merged map, and never navigates", async () => {
    // The stored map carries an id NOT in the current graph — a save must keep it (merge,
    // never prune to the visible nodes).
    mockGraph(mockFetch, GRAPH, 200, {
      mode: "manual",
      positions: { "component:default/zzz": { x: 1, y: 2 } },
    });
    const user = userEvent.setup();
    renderPage();

    await screen.findByText(/svc-a/);
    expect(screen.getByTestId("flow")).toHaveAttribute("data-draggable", "true");

    await user.click(screen.getByTestId("drag:component:default/a"));

    expect(screen.getByTestId("pos:component:default/a").textContent).toBe("111,222");
    // The click that rode the drag gesture was swallowed — no navigation.
    expect(screen.queryByTestId("probe")).not.toBeInTheDocument();
    await waitFor(
      () =>
        expect(layoutPuts(mockFetch).at(-1)).toEqual({
          mode: "manual",
          positions: {
            "component:default/zzz": { x: 1, y: 2 },
            "component:default/a": { x: 111, y: 222 },
          },
        }),
      { timeout: 2000 },
    );
  });

  test("a multi-select drag persists BOTH nodes' positions in one save", async () => {
    mockGraph(mockFetch, GRAPH, 200, { mode: "manual", positions: {} });
    const user = userEvent.setup();
    renderPage();

    await screen.findByText(/svc-a/);
    await user.click(screen.getByTestId("drag-multi"));

    expect(screen.getByTestId("pos:component:default/a").textContent).toBe("11,12");
    expect(screen.getByTestId("pos:component:default/ghost").textContent).toBe("21,22");
    await waitFor(
      () =>
        expect(layoutPuts(mockFetch)).toEqual([
          {
            mode: "manual",
            positions: {
              "component:default/a": { x: 11, y: 12 },
              "component:default/ghost": { x: 21, y: 22 },
            },
          },
        ]),
      { timeout: 2000 },
    );
  });

  test("stored positions apply in manual mode and are ignored back in auto", async () => {
    mockGraph(mockFetch, GRAPH, 200, {
      mode: "manual",
      positions: { "component:default/a": { x: 5, y: 6 } },
    });
    const user = userEvent.setup();
    renderPage();

    await screen.findByText(/svc-a/);
    expect(screen.getByTestId("pos:component:default/a").textContent).toBe("5,6");

    await user.click(screen.getByRole("radio", { name: "Auto" }));

    expect(screen.getByTestId("pos:component:default/a").textContent).not.toBe("5,6");
    expect(screen.getByTestId("flow")).toHaveAttribute("data-draggable", "false");
  });

  test("Reset layout clears the stored positions and persists the empty map", async () => {
    mockGraph(mockFetch, GRAPH, 200, {
      mode: "manual",
      positions: { "component:default/a": { x: 5, y: 6 } },
    });
    const user = userEvent.setup();
    renderPage();

    await screen.findByText(/svc-a/);
    expect(screen.getByTestId("pos:component:default/a").textContent).toBe("5,6");

    await user.click(screen.getByRole("button", { name: "Reset layout" }));

    expect(screen.getByTestId("pos:component:default/a").textContent).not.toBe("5,6");
    await waitFor(() => expect(layoutPuts(mockFetch)).toEqual([{ mode: "manual", positions: {} }]));
  });

  test("a graph spanning two namespaces draws a frame per namespace", async () => {
    mockGraph(mockFetch, {
      nodes: [
        ...GRAPH.nodes,
        { id: "system:external/acq", kind: "system", namespace: "external", name: "acq", title: null, fileId: 9, status: "STORED" },
      ],
      edges: [...GRAPH.edges, { sourceId: "component:default/a", targetId: "system:external/acq", field: "spec.system" }],
    });
    renderPage();

    await screen.findByText(/svc-a/);
    expect(screen.getByText("default")).toBeInTheDocument();
    expect(screen.getByText("external")).toBeInTheDocument();
  });

  test("a single-namespace graph draws no frame at all", async () => {
    mockGraph(mockFetch, GRAPH);
    renderPage();

    await screen.findByText(/svc-a/);
    // A lone box around the whole canvas says nothing, so it is not drawn — and `default`
    // must therefore appear nowhere on the canvas.
    expect(screen.queryByText("default")).not.toBeInTheDocument();
  });
});
