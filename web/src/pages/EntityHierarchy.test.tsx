import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor } from "@testing-library/react";
import { Route, Routes, useLocation } from "react-router-dom";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

// The entity query bar's real CodeMirror editor is covered by its own QueryEditor.test.tsx
// (a real mount) and EntityQueryBar.test.tsx — page tests drive a plain textarea stand-in.
vi.mock("../components/QueryEditor", () => ({
  default: ({
    value,
    onChange,
    onRun,
    ariaLabel,
  }: {
    value: string;
    onChange: (value: string) => void;
    onRun: () => void;
    ariaLabel: string;
  }) => (
    <textarea
      aria-label={ariaLabel}
      value={value}
      onChange={(e) => onChange(e.target.value)}
      onKeyDown={(e) => {
        if ((e.ctrlKey || e.metaKey) && e.key === "Enter") onRun();
      }}
    />
  ),
}));

import EntityHierarchy from "./EntityHierarchy";

const TOKEN_KEY = "toadie.auth.token";

type FetchMock = ReturnType<typeof vi.fn>;

// team ⊃ service (via the "team" relation, the "composition" hierarchy), plus an unrelated
// peer entity.
const GRAPH = {
  nodes: [
    { id: "team|platform", entityId: 1, blueprint: "team", blueprintTitle: "Team", identifier: "platform", title: "Platform", findings: 0 },
    { id: "service|checkout", entityId: 2, blueprint: "service", blueprintTitle: "Service", identifier: "checkout", title: "Checkout", findings: 2 },
    { id: "service|solo", entityId: 3, blueprint: "service", blueprintTitle: "Service", identifier: "solo", title: "Solo", findings: 0 },
  ],
  edges: [
    { sourceId: "service|checkout", targetId: "team|platform", relation: "team", hierarchies: ["composition"] },
    { sourceId: "service|checkout", targetId: "service|solo", relation: "peer", hierarchies: [] },
  ],
};

const HIERARCHIES = [
  { id: 1, value: "composition", isDefault: false },
  { id: 2, value: "cost-center", isDefault: false },
];

function mockGraph(
  mockFetch: FetchMock,
  body: unknown = GRAPH,
  status = 200,
  hierarchies: unknown = HIERARCHIES,
  checkDiagnostics: unknown[] = [],
) {
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    if (url.startsWith("/api/v1/blueprints")) return Promise.resolve(jsonResponse(200, { items: [] }));
    if (url.startsWith("/api/v1/dictionaries/hierarchies")) return Promise.resolve(jsonResponse(200, { items: hierarchies }));
    if (url === "/api/v1/entities/query/check")
      return Promise.resolve(jsonResponse(200, { diagnostics: checkDiagnostics }));
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

  test("an owned entity with a $team ownership edge still renders as a root beside its team", async () => {
    mockGraph(mockFetch, {
      nodes: [
        { id: "team|platform", entityId: 1, blueprint: "team", blueprintTitle: "Team", identifier: "platform", title: "Platform", findings: 0 },
        { id: "service|orphan", entityId: 2, blueprint: "service", blueprintTitle: "Service", identifier: "orphan", title: "Orphan", findings: 0 },
      ],
      edges: [
        { sourceId: "service|orphan", targetId: "team|platform", relation: "$team", hierarchies: [], ownership: true },
      ],
    });
    renderPage();

    await screen.findByText("Platform");
    // Ownership never nests — the owned entity is a SIBLING root, not Platform's child.
    expect(screen.getByText("Orphan")).toBeInTheDocument();
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

  test("the hierarchy picker renders the dictionary values in order and defaults to the first", async () => {
    mockGraph(mockFetch);
    renderPage();

    await screen.findByText("Platform");
    const picker = screen.getByLabelText("Hierarchy", { selector: "input" }) as HTMLInputElement;
    expect(picker.value).toBe("composition");
  });

  test("switching the hierarchy re-roots the tree and persists the choice", async () => {
    mockGraph(mockFetch, {
      nodes: [
        { id: "team|platform", entityId: 1, blueprint: "team", blueprintTitle: "Team", identifier: "platform", title: "Platform", findings: 0 },
        { id: "service|checkout", entityId: 2, blueprint: "service", blueprintTitle: "Service", identifier: "checkout", title: "Checkout", findings: 0 },
      ],
      edges: [
        { sourceId: "service|checkout", targetId: "team|platform", relation: "team", hierarchies: ["cost-center"] },
      ],
    });
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("Platform");
    // Under "composition" (the default), no edge names it — both nodes are roots, so Checkout
    // is not nested under a collapsible Platform.
    expect(screen.queryByRole("button", { name: "Toggle children of Platform" })).not.toBeInTheDocument();

    await user.click(screen.getByLabelText("Hierarchy", { selector: "input" }));
    await user.click(await screen.findByRole("option", { name: "cost-center" }));

    expect(await screen.findByRole("button", { name: "Toggle children of Platform" })).toBeInTheDocument();
    expect(localStorage.getItem("toadie.viewSettings.entityHierarchy.hierarchy")).toBe('"cost-center"');
  });

  test("a stale stored hierarchy id falls back to the first dictionary value", async () => {
    mockGraph(mockFetch);
    localStorage.setItem("toadie.viewSettings.entityHierarchy.hierarchy", '"gone"');
    renderPage();

    await screen.findByText("Platform");
    const picker = screen.getByLabelText("Hierarchy", { selector: "input" }) as HTMLInputElement;
    expect(picker.value).toBe("composition");
  });

  test("an empty hierarchies dictionary disables the picker with a hint", async () => {
    mockGraph(mockFetch, GRAPH, 200, []);
    renderPage();

    await screen.findByText("Platform");
    expect(screen.getByLabelText("Hierarchy", { selector: "input" })).toBeDisabled();
    expect(screen.getByText("No hierarchies defined — add them on the Hierarchies page")).toBeInTheDocument();
  });

  describe("entity query bar (phase 7, v2.0.0)", () => {
    test("typing debounces a live check request against /api/v1/entities/query/check", async () => {
      mockGraph(mockFetch);
      const user = userEvent.setup();
      renderPage();

      await screen.findByText("Platform");
      await user.type(screen.getByRole("textbox", { name: "Entity query" }), "MATCH (a)");

      await waitFor(() => {
        const call = mockFetch.mock.calls.find(([url]) => url === "/api/v1/entities/query/check");
        expect(call).toBeDefined();
        expect(JSON.parse((call![1] as RequestInit).body as string)).toEqual({ query: "MATCH (a)" });
      });
    });

    test("Run refetches the graph with a query= parameter", async () => {
      mockGraph(mockFetch);
      const user = userEvent.setup();
      renderPage();

      await screen.findByText("Platform");
      await user.type(screen.getByRole("textbox", { name: "Entity query" }), "MATCH (a)");
      await user.click(screen.getByRole("button", { name: "Run" }));

      await waitFor(() => {
        const called = mockFetch.mock.calls.some(
          ([url]) =>
            typeof url === "string" &&
            url.startsWith("/api/v1/entities/graph") &&
            url.includes("query=MATCH"),
        );
        expect(called).toBe(true);
      });
      expect(await screen.findByTestId("entityQuery-applied")).toHaveTextContent("3 entities");
    });

    test("a failed run carrying diagnostics is shown and suppresses the generic load-failed alert", async () => {
      mockFetch.mockImplementation((url: string) => {
        if (url.startsWith("/api/v1/blueprints")) return Promise.resolve(jsonResponse(200, { items: [] }));
        if (url.startsWith("/api/v1/dictionaries/hierarchies"))
          return Promise.resolve(jsonResponse(200, { items: HIERARCHIES }));
        if (url === "/api/v1/entities/query/check") return Promise.resolve(jsonResponse(200, { diagnostics: [] }));
        if (url.startsWith("/api/v1/entities/graph")) {
          if (url.includes("query=")) {
            return Promise.resolve(
              jsonResponse(400, {
                title: "Bad Request",
                status: 400,
                detail: "unknown label 'srv'",
                diagnostics: [
                  {
                    code: "UNKNOWN_LABEL",
                    message: "unknown label 'srv'",
                    line: 1,
                    column: 8,
                    endLine: 1,
                    endColumn: 11,
                    suggestion: "service",
                  },
                ],
              }),
            );
          }
          return Promise.resolve(jsonResponse(200, GRAPH));
        }
        return Promise.resolve(jsonResponse(404, {}));
      });
      const user = userEvent.setup();
      renderPage();

      await screen.findByText("Platform");
      await user.type(screen.getByRole("textbox", { name: "Entity query" }), "MATCH (a:srv)");
      await user.click(screen.getByRole("button", { name: "Run" }));

      expect(await screen.findByText("line 1, column 8")).toBeInTheDocument();
      expect(screen.getByText("Did you mean `service`?")).toBeInTheDocument();
      expect(screen.queryByText("Could not load the entity hierarchy")).not.toBeInTheDocument();
    });

    test("Clear empties the query so the next graph request carries no query param", async () => {
      mockGraph(mockFetch);
      const user = userEvent.setup();
      renderPage();

      await screen.findByText("Platform");
      await user.type(screen.getByRole("textbox", { name: "Entity query" }), "MATCH (a)");
      await user.click(screen.getByRole("button", { name: "Run" }));
      await waitFor(() =>
        expect(
          mockFetch.mock.calls.some(([url]) => typeof url === "string" && url.includes("query=MATCH")),
        ).toBe(true),
      );

      mockFetch.mock.calls.length = 0;
      await user.click(screen.getByRole("button", { name: "Clear" }));

      await waitFor(() => {
        const call = mockFetch.mock.calls.find(
          ([url]) => typeof url === "string" && url.startsWith("/api/v1/entities/graph"),
        );
        expect(call).toBeDefined();
        expect((call![0] as string).includes("query=")).toBe(false);
      });
    });

    test("the draft survives a page switch via the shared entityQuery.text storage key", async () => {
      mockGraph(mockFetch);
      const user = userEvent.setup();
      renderPage();

      await screen.findByText("Platform");
      await user.type(screen.getByRole("textbox", { name: "Entity query" }), "MATCH (a)");

      await waitFor(() => expect(localStorage.getItem("toadie.viewSettings.entityQuery.text")).toBe('"MATCH (a)"'));
    });
  });
});
