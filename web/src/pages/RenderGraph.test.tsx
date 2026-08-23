import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor } from "@testing-library/react";
import { Route, Routes, useLocation } from "react-router-dom";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";
import type { LaidOutNode } from "../utils/graphLayout";

// React Flow needs real DOM measurement (ResizeObserver, bounding boxes) that happy-dom can't
// give — the canvas is stubbed to a list of node buttons; the real rendering is e2e's job.
// The pure shaping (filterGraph/layoutGraph) is covered in graphLayout.test.ts.
vi.mock("@xyflow/react", () => ({
  ReactFlow: ({
    nodes,
    onNodeClick,
  }: {
    nodes: LaidOutNode[];
    onNodeClick?: (event: unknown, node: LaidOutNode) => void;
  }) => (
    <div data-testid="flow">
      {nodes.map((n) => (
        <button key={n.id} type="button" onClick={(e) => onNodeClick?.(e, n)}>
          {n.data.apiNode.name} [{n.data.apiNode.status}]
        </button>
      ))}
    </div>
  ),
  Background: () => null,
  Controls: () => null,
  Handle: () => null,
  Position: { Left: "left", Right: "right" },
}));

import RenderGraph from "./RenderGraph";

const TOKEN_KEY = "toadie.auth.token";

type FetchMock = ReturnType<typeof vi.fn>;

const GRAPH = {
  nodes: [
    { id: "component:default/a", kind: "component", namespace: "default", name: "svc-a", title: null, fileId: 7, status: "STORED" },
    { id: "component:default/ghost", kind: "component", namespace: "default", name: "ghost", title: null, fileId: null, status: "MISSING" },
    { id: "group:default/team-x", kind: "group", namespace: "default", name: "team-x", title: null, fileId: null, status: "EXTERNAL" },
  ],
  edges: [
    { sourceId: "component:default/a", targetId: "component:default/ghost", field: "spec.dependsOn" },
    { sourceId: "component:default/a", targetId: "group:default/team-x", field: "spec.owner" },
  ],
};

function mockGraph(mockFetch: FetchMock, body: unknown = GRAPH, status = 200) {
  mockFetch.mockImplementation((url: string) =>
    url.startsWith("/api/v1/catalog-files/graph")
      ? Promise.resolve(jsonResponse(status, body))
      : Promise.resolve(jsonResponse(404, {})),
  );
}

function PathProbe() {
  const location = useLocation();
  return <div data-testid="probe">{location.pathname}</div>;
}

function renderPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/render" element={<RenderGraph />} />
      <Route path="/catalog-files/:id/edit" element={<PathProbe />} />
    </Routes>,
    { route: "/render" },
  );
}

describe("RenderGraph page", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem(TOKEN_KEY, "fake-token");
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
    expect(screen.getByText(/team-x \[EXTERNAL\]/)).toBeInTheDocument();
  });

  test("the namespace filter refetches with namespace=", async () => {
    mockGraph(mockFetch);
    const user = userEvent.setup();
    renderPage();

    await screen.findByText(/svc-a/);
    await user.type(screen.getByLabelText("Namespace"), "team-a");

    await waitFor(
      () => {
        const called = mockFetch.mock.calls.some(
          ([url]) => typeof url === "string" && url.includes("graph?namespace=team-a"),
        );
        expect(called).toBe(true);
      },
      { timeout: 1500 },
    );
  });

  test("toggling a relation family off prunes its virtual nodes", async () => {
    mockGraph(mockFetch);
    const user = userEvent.setup();
    renderPage();

    await screen.findByText(/team-x \[EXTERNAL\]/);
    await user.click(screen.getByRole("checkbox", { name: "Owner" }));

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
      expect(screen.getByTestId("probe")).toHaveTextContent("/catalog-files/7/edit"),
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
});
