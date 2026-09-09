import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor } from "@testing-library/react";
import { Route, Routes, useLocation } from "react-router-dom";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";
import EntityHierarchy from "./EntityHierarchy";

const TOKEN_KEY = "toadie.auth.token";

type FetchMock = ReturnType<typeof vi.fn>;

// team ⊃ service (via the "team" hierarchy relation), plus an unrelated peer entity.
const GRAPH = {
  nodes: [
    { id: "team|platform", entityId: 1, blueprint: "team", blueprintTitle: "Team", identifier: "platform", title: "Platform", findings: 0 },
    { id: "service|checkout", entityId: 2, blueprint: "service", blueprintTitle: "Service", identifier: "checkout", title: "Checkout", findings: 2 },
    { id: "service|solo", entityId: 3, blueprint: "service", blueprintTitle: "Service", identifier: "solo", title: "Solo", findings: 0 },
  ],
  edges: [
    { sourceId: "service|checkout", targetId: "team|platform", relation: "team", hierarchy: true },
    { sourceId: "service|checkout", targetId: "service|solo", relation: "peer", hierarchy: false },
  ],
};

function mockGraph(mockFetch: FetchMock, body: unknown = GRAPH, status = 200) {
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    if (url.startsWith("/api/v1/blueprints")) return Promise.resolve(jsonResponse(200, { items: [] }));
    if ((init?.method ?? "GET") === "DELETE" && url.startsWith("/api/v1/entities/"))
      return Promise.resolve(new Response(null, { status: 204 }));
    return url.startsWith("/api/v1/entities/graph")
      ? Promise.resolve(jsonResponse(status, body))
      : Promise.resolve(jsonResponse(404, {}));
  });
}

function PathProbe() {
  const location = useLocation();
  return <div data-testid="probe">{location.pathname}</div>;
}

function renderPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/entity-hierarchy" element={<EntityHierarchy />} />
      <Route path="/entities/:id/edit" element={<PathProbe />} />
    </Routes>,
    { route: "/entity-hierarchy" },
  );
}

describe("EntityHierarchy page", () => {
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

  test("nests an entity under its hierarchy-relation parent", async () => {
    mockGraph(mockFetch);
    renderPage();

    expect(await screen.findByText("Platform")).toBeInTheDocument();
    expect(screen.getByText("Checkout")).toBeInTheDocument();
    expect(screen.getByText("checkout")).toBeInTheDocument();
    // The non-hierarchy "peer" relation never nests solo under checkout — it stays a root.
    expect(screen.getByText("Solo")).toBeInTheDocument();
  });

  test("the findings badge shows the count for an entity with findings, and nothing at zero", async () => {
    mockGraph(mockFetch);
    renderPage();

    await screen.findByText("Checkout");
    expect(screen.getByText("2 findings")).toBeInTheDocument();
  });

  test("an empty workspace shows the empty state", async () => {
    mockGraph(mockFetch, { nodes: [], edges: [] });
    renderPage();

    expect(await screen.findByText(/no entities match/i)).toBeInTheDocument();
  });

  test("shows an alert when the graph fails to load", async () => {
    mockGraph(mockFetch, { title: "boom", status: 500 }, 500);
    renderPage();

    expect(await screen.findByText("Could not load the entity hierarchy")).toBeInTheDocument();
  });

  test("collapsing the parent hides its child; expand all restores it", async () => {
    mockGraph(mockFetch);
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("Platform");
    await user.click(screen.getByRole("button", { name: "Toggle children of Platform" }));
    expect(screen.queryByText("Checkout")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Expand all" }));
    expect(screen.getByText("Checkout")).toBeInTheDocument();
  });

  test("collapse all hides every branch", async () => {
    mockGraph(mockFetch);
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("Checkout");
    await user.click(screen.getByRole("button", { name: "Collapse all" }));
    expect(screen.queryByText("Checkout")).not.toBeInTheDocument();
  });

  test("pin narrows the tree to one entity and clears via the badge", async () => {
    mockGraph(mockFetch);
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("Solo");
    await user.click(screen.getByRole("button", { name: "Operations for Checkout" }));
    await user.click(await screen.findByRole("menuitem", { name: "Pin" }));

    expect(screen.queryByText("Solo")).not.toBeInTheDocument();
    expect(screen.getByText("Pinned: Checkout")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Unpin Checkout" }));
    await waitFor(() => expect(screen.getByText("Solo")).toBeInTheDocument());
  });

  test("a pin whose entity left the loaded graph drops itself", async () => {
    mockGraph(mockFetch);
    localStorage.setItem("toadie.viewSettings.entityHierarchy.pinnedNodeId", '"service|gone"');
    renderPage();

    await screen.findByText("Platform");
    expect(screen.queryByText(/^Pinned:/)).not.toBeInTheDocument();
    expect(localStorage.getItem("toadie.viewSettings.entityHierarchy.pinnedNodeId")).toBe('""');
    // Auto-unpinned, so the full forest renders.
    expect(screen.getByText("Checkout")).toBeInTheDocument();
    expect(screen.getByText("Solo")).toBeInTheDocument();
  });

  test("Edit navigates to the entity editor; Delete removes the row", async () => {
    mockGraph(mockFetch);
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("Solo");
    await user.click(screen.getByRole("button", { name: "Operations for Solo" }));
    await user.click(await screen.findByRole("menuitem", { name: "Edit" }));
    await waitFor(() => expect(screen.getByTestId("probe")).toHaveTextContent("/entities/3/edit"));
  });

  test("delete confirms and removes the entity", async () => {
    mockGraph(mockFetch);
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("Solo");
    await user.click(screen.getByRole("button", { name: "Operations for Solo" }));
    await user.click(await screen.findByRole("menuitem", { name: "Delete" }));
    await user.click(await screen.findByRole("button", { name: "Delete" }));

    await waitFor(() =>
      expect(mockFetch.mock.calls.some(([url, init]) => url === "/api/v1/entities/3" && init?.method === "DELETE")).toBe(true),
    );
  });
});
