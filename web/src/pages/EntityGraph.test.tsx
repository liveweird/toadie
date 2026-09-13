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

// The entity query bar's real CodeMirror editor is covered by its own QueryEditor.test.tsx
// (a real mount) and EntityQueryBar.test.tsx — page tests drive a plain textarea stand-in so
// typing/Mod-Enter assertions stay simple `userEvent`/`fireEvent` calls.
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
  layout: unknown = { mode: "auto", positions: {} },
  hierarchies: unknown = HIERARCHIES,
  checkDiagnostics: unknown[] = [],
) {
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    if (url.startsWith("/api/v1/blueprints")) return Promise.resolve(jsonResponse(200, { items: [] }));
    if (url.startsWith("/api/v1/dictionaries/hierarchies")) return Promise.resolve(jsonResponse(200, { items: hierarchies }));
    if (url === "/api/v1/entities/query/check")
      return Promise.resolve(jsonResponse(200, { diagnostics: checkDiagnostics }));
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
        { sourceId: "service|checkout", targetId: "team|platform", relation: "$team", hierarchies: [], ownership: true },
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

  test("the hierarchy picker renders the dictionary values in order and defaults to the first", async () => {
    mockGraph(mockFetch);
    renderPage();

    await screen.findByText(/platform \[team\]/);
    const picker = screen.getByLabelText("Hierarchy", { selector: "input" }) as HTMLInputElement;
    expect(picker.value).toBe("composition");
  });

  test("switching the hierarchy restyles containment and persists the choice", async () => {
    mockGraph(mockFetch);
    const user = userEvent.setup();
    renderPage();

    await screen.findByText(/checkout \[service\]/);
    // Under "composition" (the default), the team edge nests checkout under platform — platform
    // gets a fold toggle.
    expect(screen.getByRole("button", { name: "Collapse platform" })).toBeInTheDocument();

    await user.click(screen.getByLabelText("Hierarchy", { selector: "input" }));
    await user.click(await screen.findByRole("option", { name: "cost-center" }));

    // No edge carries "cost-center" — nothing nests, so platform loses its fold toggle.
    await waitFor(() =>
      expect(screen.queryByRole("button", { name: "Collapse platform" })).not.toBeInTheDocument(),
    );
    expect(localStorage.getItem("toadie.viewSettings.entityGraph.hierarchy")).toBe('"cost-center"');
  });

  test("a stale stored hierarchy id falls back to the first dictionary value", async () => {
    mockGraph(mockFetch);
    localStorage.setItem("toadie.viewSettings.entityGraph.hierarchy", '"gone"');
    renderPage();

    await screen.findByText(/platform \[team\]/);
    const picker = screen.getByLabelText("Hierarchy", { selector: "input" }) as HTMLInputElement;
    expect(picker.value).toBe("composition");
  });

  test("an empty hierarchies dictionary disables the picker with a hint", async () => {
    mockGraph(mockFetch, GRAPH, 200, { mode: "auto", positions: {} }, []);
    renderPage();

    await screen.findByText(/platform \[team\]/);
    expect(screen.getByLabelText("Hierarchy", { selector: "input" })).toBeDisabled();
    expect(screen.getByText("No hierarchies defined — add them on the Hierarchies page")).toBeInTheDocument();
  });

  describe("entity query bar (phase 7, v2.0.0)", () => {
    test("typing debounces a live check request against /api/v1/entities/query/check", async () => {
      mockGraph(mockFetch);
      const user = userEvent.setup();
      renderPage();

      await screen.findByText(/platform \[team\]/);
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

      await screen.findByText(/platform \[team\]/);
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
        if (url === "/api/v1/users/9/entity-graph-layout")
          return Promise.resolve(jsonResponse(200, { mode: "auto", positions: {} }));
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
        if (url.startsWith("/api/v1/entities?"))
          return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 100, total: 0 }));
        return Promise.resolve(jsonResponse(404, {}));
      });
      const user = userEvent.setup();
      renderPage();

      await screen.findByText(/platform \[team\]/);
      await user.type(screen.getByRole("textbox", { name: "Entity query" }), "MATCH (a:srv)");
      await user.click(screen.getByRole("button", { name: "Run" }));

      expect(await screen.findByText("line 1, column 8")).toBeInTheDocument();
      expect(screen.getByText("Did you mean `service`?")).toBeInTheDocument();
      expect(screen.queryByText("Failed to load the entity graph")).not.toBeInTheDocument();

      // Editing the draft past the failed text hands the bar back to the live check: the stale
      // run diagnostics would otherwise land on the wrong characters of the new text.
      await user.type(screen.getByRole("textbox", { name: "Entity query" }), " RETURN a");
      await waitFor(() => expect(screen.queryByText("Did you mean `service`?")).not.toBeInTheDocument());
      expect(screen.queryByText("line 1, column 8")).not.toBeInTheDocument();
      // …and the generic alert must not reappear in their place: the failure is still the query.
      expect(screen.queryByText("Failed to load the entity graph")).not.toBeInTheDocument();
    });

    test("Clear empties the query so the next graph request carries no query param", async () => {
      mockGraph(mockFetch);
      const user = userEvent.setup();
      renderPage();

      await screen.findByText(/platform \[team\]/);
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

      await screen.findByText(/platform \[team\]/);
      await user.type(screen.getByRole("textbox", { name: "Entity query" }), "MATCH (a)");

      await waitFor(() => expect(localStorage.getItem("toadie.viewSettings.entityQuery.text")).toBe('"MATCH (a)"'));
    });
  });
});
