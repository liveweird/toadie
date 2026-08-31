import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { fireEvent, screen, waitFor } from "@testing-library/react";
import { Route, Routes } from "react-router-dom";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";
import Hierarchy from "./Hierarchy";

const TOKEN_KEY = "toadie.auth.token";

type FetchMock = ReturnType<typeof vi.fn>;

// A three-level containment chain (system ⊃ component ⊃ subcomponent) plus a MISSING
// placeholder parent with a child of its own.
const GRAPH = {
  nodes: [
    { id: "system:default/billing", kind: "system", namespace: "default", name: "billing", title: "Billing", fileId: 1, status: "STORED" },
    { id: "component:default/core", kind: "component", namespace: "default", name: "core", title: null, fileId: 2, status: "STORED" },
    { id: "component:default/worker", kind: "component", namespace: "default", name: "worker", title: null, fileId: 3, status: "STORED" },
    { id: "system:default/gone-sys", kind: "system", namespace: "default", name: "gone-sys", title: null, fileId: null, status: "MISSING" },
    { id: "component:default/orphan", kind: "component", namespace: "default", name: "orphan", title: null, fileId: 4, status: "STORED" },
  ],
  edges: [
    { sourceId: "component:default/core", targetId: "system:default/billing", field: "spec.system" },
    { sourceId: "component:default/worker", targetId: "system:default/billing", field: "spec.system" },
    { sourceId: "component:default/worker", targetId: "component:default/core", field: "spec.subcomponentOf" },
    { sourceId: "component:default/orphan", targetId: "system:default/gone-sys", field: "spec.system" },
  ],
};

const NAMESPACE_ENTRIES = { items: [{ id: 1, value: "default", isDefault: true }] };

function mockGraph(mockFetch: FetchMock, body: unknown = GRAPH, status = 200) {
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    if (url.startsWith("/api/v1/dictionaries/namespaces"))
      return Promise.resolve(jsonResponse(200, NAMESPACE_ENTRIES));
    if ((init?.method ?? "GET") === "DELETE" && url.startsWith("/api/v1/files/"))
      return Promise.resolve(new Response(null, { status: 204 }));
    return url.startsWith("/api/v1/files/graph")
      ? Promise.resolve(jsonResponse(status, body))
      : Promise.resolve(jsonResponse(404, {}));
  });
}

function renderPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/" element={<Hierarchy />} />
    </Routes>,
    { route: "/" },
  );
}

describe("Hierarchy page", () => {
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

  test("renders the containment tree with placeholder parents", async () => {
    mockGraph(mockFetch);
    renderPage();

    expect(await screen.findByText("billing")).toBeInTheDocument();
    expect(screen.getByText("core")).toBeInTheDocument();
    expect(screen.getByText("worker")).toBeInTheDocument();
    // The deleted system renders dimmed with its badge — and keeps its child nested.
    expect(screen.getByText("gone-sys")).toBeInTheDocument();
    expect(screen.getByText("Missing")).toBeInTheDocument();
    expect(screen.getByText("orphan")).toBeInTheDocument();
  });

  test("collapsing a branch hides its children; expand all restores them", async () => {
    mockGraph(mockFetch);
    const user = userEvent.setup();
    renderPage();
    await screen.findByText("billing");

    // Collapsing a branch removes its children from the document entirely.
    await user.click(screen.getByRole("button", { name: "Toggle children of billing" }));
    expect(screen.queryByText("core")).not.toBeInTheDocument();
    expect(screen.queryByText("worker")).not.toBeInTheDocument();
    // Siblings outside the branch stay.
    expect(screen.getByText("orphan")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Expand all" }));
    expect(screen.getByText("worker")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Collapse all" }));
    expect(screen.queryByText("core")).not.toBeInTheDocument();
    expect(screen.queryByText("orphan")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Toggle children of billing" })).toHaveAttribute(
      "aria-expanded",
      "false",
    );
  });

  test("stored rows carry the Operations menu, placeholders do not, delete flows through the confirm", async () => {
    mockGraph(mockFetch);
    const user = userEvent.setup();
    renderPage();
    await screen.findByText("billing");

    // Every STORED row has one; the MISSING placeholder has none.
    expect(screen.getByRole("button", { name: "Operations for billing" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Operations for gone-sys" })).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Operations for orphan" }));
    await user.click(await screen.findByRole("menuitem", { name: "Delete" }));
    // The menu has closed by now — the only "Delete" button left is the modal's confirm.
    await user.click(await screen.findByRole("button", { name: "Delete" }));

    await waitFor(() => {
      const deleted = mockFetch.mock.calls.find(
        ([url, init]) =>
          (init as RequestInit | undefined)?.method === "DELETE" && url === "/api/v1/files/4",
      );
      expect(deleted).toBeTruthy();
    });
  });

  test("a stored row's name opens its editor; a placeholder's name is not a link", async () => {
    mockGraph(mockFetch);
    renderPage();
    await screen.findByText("billing");

    expect(screen.getByRole("link", { name: "Edit billing" })).toHaveAttribute(
      "href",
      "/files/1/edit",
    );
    // The MISSING placeholder has no stored entity to open — same test the Operations menu
    // applies. Its name still renders, just not as a link.
    expect(screen.getByText("gone-sys")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Edit gone-sys" })).not.toBeInTheDocument();
  });

  test("the always-visible Kind pills drive the graph query as a visible set", async () => {
    mockGraph(mockFetch);
    renderPage();

    await screen.findByText("core");
    // The pills sit above the tree, outside the collapsed FilterPanel — hiding Component
    // sends the six kinds that stay visible.
    fireEvent.click(screen.getByRole("checkbox", { name: "Component" }));

    await waitFor(() => {
      const called = mockFetch.mock.calls.some(
        ([url]) =>
          typeof url === "string" &&
          url.includes("/api/v1/files/graph?kind=API") &&
          !url.includes("kind=Component"),
      );
      expect(called).toBe(true);
    });
  });

  test("a failed graph load shows the error alert", async () => {
    mockGraph(mockFetch, { title: "boom", status: 500 }, 500);
    renderPage();
    expect(await screen.findByText("Could not load the hierarchy")).toBeInTheDocument();
  });

  test("an empty workspace shows the empty state", async () => {
    mockGraph(mockFetch, { nodes: [], edges: [] });
    renderPage();
    expect(await screen.findByText(/nothing to show/i)).toBeInTheDocument();
  });
});
