/* eslint-disable react-refresh/only-export-components */
import { vi } from "vitest";
import { Route, Routes, useLocation } from "react-router-dom";
import { jsonResponse } from "./http";
import { renderWithProviders } from "./render";
import { blueprintResponse } from "./fixtures";
import EntityGraph from "../pages/EntityGraph";

// -- test scaffolding shared by the Entity graph page tests (EntityGraph.test.tsx and
// EntityGraph.layout.test.tsx): the mocked REST surface and the page render, in one file with
// its route probe (the test/render.tsx idiom; fast-refresh is irrelevant under vitest). Each
// test file still installs its own `vi.mock` of `@xyflow/react` (src/test/reactFlowStub.tsx).

export const TOKEN_KEY = "toadie.auth.token";

export type FetchMock = ReturnType<typeof vi.fn>;

export const GRAPH = {
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

export const HIERARCHIES = [
  { id: 1, value: "composition", isDefault: false },
  { id: 2, value: "cost-center", isDefault: false },
];

const BLUEPRINTS = [
  blueprintResponse({ id: 1, identifier: "team", title: "Team" }),
  blueprintResponse({ id: 2, identifier: "service", title: "Service" }),
];

export function mockGraph(
  mockFetch: FetchMock,
  body: unknown = GRAPH,
  status = 200,
  layout: unknown = { mode: "auto", positions: {} },
  hierarchies: unknown = HIERARCHIES,
  checkDiagnostics: unknown[] = [],
  blueprints: unknown = BLUEPRINTS,
) {
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    if (url.startsWith("/api/v1/blueprints")) return Promise.resolve(jsonResponse(200, { items: blueprints }));
    if (url.startsWith("/api/v1/dictionaries/hierarchies")) return Promise.resolve(jsonResponse(200, { items: hierarchies }));
    if (url.startsWith("/api/v1/entity-queries")) return Promise.resolve(jsonResponse(200, { items: [] }));
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

export function layoutPuts(mockFetch: FetchMock): unknown[] {
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

export function renderPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/entity-graph" element={<EntityGraph />} />
      <Route path="/entities/:id/edit" element={<PathProbe />} />
    </Routes>,
    { route: "/entity-graph" },
  );
}
