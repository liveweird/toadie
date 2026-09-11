import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { fireEvent, screen, waitFor } from "@testing-library/react";
import { Route, Routes, useLocation } from "react-router-dom";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

// React Flow needs real DOM measurement happy-dom can't give — shared stub (v1.25.0,
// extracted from RenderGraph.test.tsx). The pure shaping (entityGraph.ts/graphLayout.ts) is
// covered by its own unit tests.
vi.mock("@xyflow/react", () => import("../test/reactFlowStub"));

import EntityGraph from "./EntityGraph";

const TOKEN_KEY = "toadie.auth.token";

type FetchMock = ReturnType<typeof vi.fn>;

const GRAPH = {
  nodes: [
    { id: "team|platform", entityId: 1, blueprint: "team", blueprintTitle: "Team", identifier: "platform", title: "Platform", findings: 0 },
    { id: "service|checkout", entityId: 2, blueprint: "service", blueprintTitle: "Service", identifier: "checkout", title: "Checkout", findings: 0 },
    { id: "service|solo", entityId: 3, blueprint: "service", blueprintTitle: "Service", identifier: "solo", title: "Solo", findings: 0 },
  ],
  edges: [
    { sourceId: "service|checkout", targetId: "team|platform", relation: "team", hierarchy: true },
    { sourceId: "service|checkout", targetId: "service|solo", relation: "peer", hierarchy: false },
  ],
};

function mockGraph(
  mockFetch: FetchMock,
  body: unknown = GRAPH,
  status = 200,
  layout: unknown = { mode: "auto", positions: {} },
) {
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    if (url.startsWith("/api/v1/blueprints")) return Promise.resolve(jsonResponse(200, { items: [] }));
    if (url === "/api/v1/users/9/entity-graph-layout") {
      return init?.method === "PUT"
        ? Promise.resolve(new Response(null, { status: 204 }))
        : Promise.resolve(jsonResponse(200, layout));
    }
    if (url.startsWith("/api/v1/entities/graph"))
      return Promise.resolve(jsonResponse(status, body));
    // The Team filter's own options pool (`useEntityOptions(TEAM_BLUEPRINT)`).
    if (url.startsWith("/api/v1/entities?"))
      return Promise.resolve(
        jsonResponse(200, {
          items: [{ id: 1, identifier: "platform", title: "Platform", findings: [] }],
          page: 1,
          pageSize: 100,
          total: 1,
        }),
      );
    return Promise.resolve(jsonResponse(404, {}));
  });
}

function layoutPuts(mockFetch: FetchMock): unknown[] {
  return mockFetch.mock.calls
    .filter(
      ([url, init]) =>
        url === "/api/v1/users/9/entity-graph-layout" && (init as RequestInit | undefined)?.method === "PUT",
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
      <Route path="/entity-graph" element={<EntityGraph />} />
      <Route path="/entities/:id/edit" element={<PathProbe />} />
    </Routes>,
    { route: "/entity-graph" },
  );
}

describe("EntityGraph page", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem(TOKEN_KEY, "fake-token");
    localStorage.setItem("toadie.auth.userId", "9");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("renders every entity node", async () => {
    mockGraph(mockFetch);
    renderPage();

    // The shared React Flow stub faces a node as `identifier [blueprint]` (no `name`/`status`
    // fields on an entity node — see src/test/reactFlowStub.tsx).
    expect(await screen.findByText(/platform \[team\]/)).toBeInTheDocument();
    expect(screen.getByText(/checkout \[service\]/)).toBeInTheDocument();
    expect(screen.getByText(/solo \[service\]/)).toBeInTheDocument();
  });

  test("two blueprints draw a frame each, labelled by their title", async () => {
    mockGraph(mockFetch);
    renderPage();

    await screen.findByText(/platform \[team\]/);
    expect(screen.getByText("Team")).toBeInTheDocument();
    expect(screen.getByText("Service")).toBeInTheDocument();
  });

  test("toggling the peer relation chip off hides its edge but keeps both nodes", async () => {
    mockGraph(mockFetch);
    const user = userEvent.setup();
    renderPage();

    await screen.findByText(/checkout \[service\]/);
    expect(screen.getByTestId("edge:service|checkout->service|solo:peer")).toBeInTheDocument();
    await user.click(screen.getByRole("checkbox", { name: "peer" }));

    expect(screen.queryByTestId("edge:service|checkout->service|solo:peer")).not.toBeInTheDocument();
    expect(screen.getByText(/solo \[service\]/)).toBeInTheDocument();
  });

  test("clicking a node navigates to its editor", async () => {
    mockGraph(mockFetch);
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByText(/checkout \[service\]/));
    await waitFor(() => expect(screen.getByTestId("probe")).toHaveTextContent("/entities/2/edit"));
  });

  test("an empty workspace shows the empty state", async () => {
    mockGraph(mockFetch, { nodes: [], edges: [] });
    renderPage();

    expect(await screen.findByText(/nothing to render/i)).toBeInTheDocument();
    expect(screen.queryByTestId("flow")).not.toBeInTheDocument();
  });

  test("shows an alert when the graph fails to load", async () => {
    mockGraph(mockFetch, { title: "boom", status: 500 }, 500);
    renderPage();

    expect(await screen.findByText("Failed to load the entity graph")).toBeInTheDocument();
  });

  test("collapsing the hierarchy parent hides its child and persists at once", async () => {
    mockGraph(mockFetch);
    const user = userEvent.setup();
    renderPage();

    await screen.findByText(/checkout \[service\]/);
    await user.click(screen.getByRole("button", { name: "Collapse platform" }));

    expect(screen.queryByText(/checkout \[service\]/)).not.toBeInTheDocument();
    // solo has no hierarchy parent (only the non-hierarchy "peer" relation) — it stays.
    expect(screen.getByText(/solo \[service\]/)).toBeInTheDocument();
    await waitFor(() =>
      expect(layoutPuts(mockFetch)).toEqual([{ mode: "auto", positions: {}, collapsed: ["team|platform"] }]),
    );
  });

  test("switching to Manual persists the mode and enables dragging", async () => {
    mockGraph(mockFetch);
    const user = userEvent.setup();
    renderPage();

    await screen.findByText(/platform \[team\]/);
    expect(screen.getByTestId("flow")).toHaveAttribute("data-draggable", "false");
    await user.click(screen.getByRole("radio", { name: "Manual" }));

    expect(screen.getByTestId("flow")).toHaveAttribute("data-draggable", "true");
    await waitFor(() =>
      expect(layoutPuts(mockFetch)).toEqual([{ mode: "manual", positions: {}, collapsed: [] }]),
    );
  });

  test("a drag persists the node's position, merged with the stored map", async () => {
    mockGraph(mockFetch, GRAPH, 200, {
      mode: "manual",
      positions: { "team|zzz": { x: 1, y: 2 } },
    });
    const user = userEvent.setup();
    renderPage();

    await screen.findByText(/platform \[team\]/);
    await user.click(screen.getByTestId("drag:team|platform"));

    await waitFor(() =>
      expect(layoutPuts(mockFetch).at(-1)).toEqual({
        mode: "manual",
        positions: { "team|zzz": { x: 1, y: 2 }, "team|platform": { x: 111, y: 222 } },
        collapsed: [],
      }),
      { timeout: 2000 },
    );
  });

  test("the legend opens from the toolbar button", async () => {
    mockGraph(mockFetch);
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("button", { name: "Legend" }));
    expect(await screen.findByText("Entity")).toBeInTheDocument();
    expect(screen.getByText("Hierarchy edge")).toBeInTheDocument();
  });

  test("the blueprint filter refetches with a repeated blueprint= param", async () => {
    mockGraph(mockFetch);
    renderPage();

    await screen.findByText(/platform \[team\]/);
    fireEvent.click(screen.getByRole("button", { name: /Filters/ }));
    await screen.findByLabelText("Blueprints", { selector: "input" });

    await waitFor(() => {
      const called = mockFetch.mock.calls.some(
        ([url]) => typeof url === "string" && url.startsWith("/api/v1/entities/graph"),
      );
      expect(called).toBe(true);
    });
  });

  test("a $team ownership edge draws dashed gray and shows in the legend", async () => {
    const graph = {
      nodes: [
        { id: "team|platform", entityId: 1, blueprint: "team", blueprintTitle: "Team", identifier: "platform", title: "Platform", findings: 0 },
        { id: "service|checkout", entityId: 2, blueprint: "service", blueprintTitle: "Service", identifier: "checkout", title: "Checkout", findings: 0 },
      ],
      edges: [
        { sourceId: "service|checkout", targetId: "team|platform", relation: "$team", hierarchy: false, ownership: true },
      ],
    };
    mockGraph(mockFetch, graph);
    const user = userEvent.setup();
    renderPage();

    await screen.findByText(/checkout \[service\]/);
    expect(screen.getByTestId("edge:service|checkout->team|platform:$team")).toHaveAttribute(
      "data-dash",
      "2 3",
    );

    await user.click(screen.getByRole("button", { name: "Legend" }));
    expect(await screen.findByText("Ownership edge")).toBeInTheDocument();
  });

  test("picking the Team filter refetches the graph with a team= param", async () => {
    mockGraph(mockFetch);
    const user = userEvent.setup();
    renderPage();

    await screen.findByText(/platform \[team\]/);
    fireEvent.click(screen.getByRole("button", { name: /Filters/ }));
    await user.click(await screen.findByLabelText("Team", { selector: "input" }));
    await user.click(await screen.findByRole("option", { name: /platform/ }));

    await waitFor(() => {
      const called = mockFetch.mock.calls.some(
        ([url]) =>
          typeof url === "string" && url.startsWith("/api/v1/entities/graph") && url.includes("team=platform"),
      );
      expect(called).toBe(true);
    });
  });
});
