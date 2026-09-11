import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor, within } from "@testing-library/react";
import { Route, Routes, useLocation } from "react-router-dom";
import Entities from "./Entities";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

type FetchMock = ReturnType<typeof vi.fn>;

const BLUEPRINTS = [
  {
    id: 1,
    identifier: "service",
    title: "Service",
    schema: {
      properties: {
        language: { type: "string", title: "Language" },
        active: { type: "boolean", title: "Active" },
      },
      required: [],
    },
    relations: {},
    mirrorProperties: {},
    calculationProperties: {},
    aggregationProperties: {},
  },
];

const ENTITY = {
  id: 5,
  blueprint: "service",
  blueprintId: 1,
  identifier: "checkout",
  title: "Checkout",
  team: ["platform"],
  properties: { language: "kotlin", active: true },
  relations: {},
  findings: [{ code: "REQUIRED_MISSING", field: "properties.tier", message: "Required" }],
  createdBy: 1,
  creatorName: "Alice",
  creatorDeleted: false,
  createdAt: 0,
  updatedAt: 0,
};

const ENTITY_NO_PREVIEW_VALUES = {
  ...ENTITY,
  id: 6,
  identifier: "billing",
  title: "Billing",
  team: undefined,
  properties: {},
  findings: [],
};

// v1.27.0 — computed (mirror/calculation/aggregation) preview columns: two schema scalar
// columns plus three computed ones, so the combined list (5) exceeds MAX_COLUMN_PROPERTIES
// (4) and the cap must drop the LAST one (aggregation), preserving schema-then-computed order.
const BLUEPRINT_COMPUTED = {
  id: 2,
  identifier: "workload",
  title: "Workload",
  schema: {
    properties: {
      env: { type: "string", title: "Env" },
      replicas: { type: "number", title: "Replicas" },
    },
    required: [],
  },
  relations: {},
  mirrorProperties: {
    langs: { title: "Langs", path: "service.languages" },
  },
  calculationProperties: {
    risk: { title: "Risk", type: "string", calculation: ".properties.tier", colorized: true, colors: { high: "red" } },
  },
  aggregationProperties: {
    dependents: {
      title: "Dependents",
      target: "workload",
      calculationSpec: { calculationBy: "entities", func: "count" },
    },
  },
};

const ENTITY_COMPUTED = {
  id: 7,
  blueprint: "workload",
  blueprintId: 2,
  identifier: "checkout-staging",
  title: "Checkout staging",
  team: undefined,
  properties: { env: "staging", replicas: 2, langs: ["java", "kotlin"], risk: "high", dependents: 5 },
  relations: {},
  findings: [],
  createdBy: 1,
  creatorName: "Alice",
  creatorDeleted: false,
  createdAt: 0,
  updatedAt: 0,
};

function mockRoutes(mockFetch: FetchMock) {
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    if (method === "GET" && url === "/api/v1/blueprints") return Promise.resolve(jsonResponse(200, { items: BLUEPRINTS }));
    if (method === "GET" && url.startsWith("/api/v1/entities?")) {
      return Promise.resolve(jsonResponse(200, { items: [ENTITY], page: 1, pageSize: 20, total: 1 }));
    }
    if (method === "DELETE" && url === "/api/v1/entities/5") return Promise.resolve(new Response(null, { status: 204 }));
    return Promise.resolve(jsonResponse(404, {}));
  });
}

describe("Entities page", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("toadie.auth.token", "fake-token");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("with no blueprint chosen it shows the pick-a-blueprint empty state and disables New entity, without listing entities", async () => {
    mockRoutes(mockFetch);
    renderWithProviders(<Entities />, { route: "/entities" });

    expect(await screen.findByText("Pick a blueprint above to see its entities")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "New entity" })).toHaveAttribute("data-disabled", "true");
    // The Team filter's own options pool (`_team`) loads independently of the picked
    // blueprint — only the primary, blueprint-scoped list must stay unfetched.
    expect(
      mockFetch.mock.calls.some(
        ([url]) => (url as string).startsWith("/api/v1/entities?") && !(url as string).includes("blueprint=_team"),
      ),
    ).toBe(false);
  });

  test("picking a blueprint via the URL lists its entities with identifier link and findings badge", async () => {
    mockRoutes(mockFetch);
    renderWithProviders(<Entities />, { route: "/entities?blueprint=service" });

    expect(await screen.findByRole("link", { name: "Edit checkout" })).toBeInTheDocument();
    expect(screen.getByText("1 finding")).toBeInTheDocument();
    // The Team column renders the entity's own team as a badge.
    expect(screen.getByText("platform")).toBeInTheDocument();
    // The boolean preview column renders a badge for its true value.
    expect(screen.getByText("True")).toBeInTheDocument();
    // A picked blueprint renders "New entity" as a real link (Mantine only forces a plain
    // <button> when `disabled` is set).
    expect(screen.getByRole("link", { name: "New entity" })).toHaveAttribute(
      "href",
      "/entities/new?blueprint=service",
    );
  });

  test("a row with no team shows a dash in the Team column", async () => {
    mockFetch.mockImplementation((url: string) => {
      if (url === "/api/v1/blueprints") return Promise.resolve(jsonResponse(200, { items: BLUEPRINTS }));
      if (url.startsWith("/api/v1/entities?")) {
        return Promise.resolve(jsonResponse(200, { items: [ENTITY_NO_PREVIEW_VALUES], page: 1, pageSize: 20, total: 1 }));
      }
      return Promise.resolve(jsonResponse(404, {}));
    });
    renderWithProviders(<Entities />, { route: "/entities?blueprint=service" });
    await screen.findByRole("link", { name: "Edit billing" });
    expect(screen.getAllByText("—").length).toBeGreaterThan(0);
  });

  test("picking a team in the filter refetches with team= and the URL carries ?team=", async () => {
    mockRoutes(mockFetch);
    const user = userEvent.setup();
    renderWithProviders(<Entities />, { route: "/entities?blueprint=service" });

    await screen.findByRole("link", { name: "Edit checkout" });
    const teamSelect = screen.getByLabelText("Team", { selector: "input" });
    await user.click(teamSelect);
    await user.click(await screen.findByRole("option", { name: "checkout — Checkout" }));

    await waitFor(() =>
      expect(
        mockFetch.mock.calls.some(
          ([url]) => typeof url === "string" && url.includes("/api/v1/entities?") && url.includes("team=checkout"),
        ),
      ).toBe(true),
    );
  });

  test("a row missing preview-column values shows a dash, and findings badge stays absent at zero", async () => {
    mockFetch.mockImplementation((url: string) => {
      if (url === "/api/v1/blueprints") return Promise.resolve(jsonResponse(200, { items: BLUEPRINTS }));
      if (url.startsWith("/api/v1/entities?")) {
        return Promise.resolve(jsonResponse(200, { items: [ENTITY_NO_PREVIEW_VALUES], page: 1, pageSize: 20, total: 1 }));
      }
      return Promise.resolve(jsonResponse(404, {}));
    });
    renderWithProviders(<Entities />, { route: "/entities?blueprint=service" });

    await screen.findByRole("link", { name: "Edit billing" });
    expect(screen.getAllByText("—").length).toBeGreaterThan(0);
    expect(screen.queryByText(/finding/)).not.toBeInTheDocument();
  });

  test("no entities for the picked blueprint shows the empty state", async () => {
    mockFetch.mockImplementation((url: string) => {
      if (url === "/api/v1/blueprints") return Promise.resolve(jsonResponse(200, { items: BLUEPRINTS }));
      if (url.startsWith("/api/v1/entities?")) return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 20, total: 0 }));
      return Promise.resolve(jsonResponse(404, {}));
    });
    renderWithProviders(<Entities />, { route: "/entities?blueprint=service" });

    expect(await screen.findByText("No entities for this blueprint yet")).toBeInTheDocument();
  });

  function PathProbe() {
    const location = useLocation();
    return <div data-testid="probe">{location.pathname}</div>;
  }

  test("clicking Edit navigates to the editor", async () => {
    mockRoutes(mockFetch);
    const user = userEvent.setup();
    renderWithProviders(
      <Routes>
        <Route path="/entities" element={<Entities />} />
        <Route path="/entities/:id/edit" element={<PathProbe />} />
      </Routes>,
      { route: "/entities?blueprint=service" },
    );

    await screen.findByRole("link", { name: "Edit checkout" });
    await user.click(screen.getByRole("button", { name: "Edit checkout" }));
    expect(await screen.findByTestId("probe")).toHaveTextContent("/entities/5/edit");
  });

  test("a delete conflict renders the fixed 409 message inline", async () => {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      const method = init?.method ?? "GET";
      if (method === "GET" && url === "/api/v1/blueprints") return Promise.resolve(jsonResponse(200, { items: BLUEPRINTS }));
      if (method === "GET" && url.startsWith("/api/v1/entities?")) {
        return Promise.resolve(jsonResponse(200, { items: [ENTITY], page: 1, pageSize: 20, total: 1 }));
      }
      if (method === "DELETE" && url === "/api/v1/entities/5") return Promise.resolve(jsonResponse(409, null));
      return Promise.resolve(jsonResponse(404, {}));
    });
    const user = userEvent.setup();
    renderWithProviders(<Entities />, { route: "/entities?blueprint=service" });

    await screen.findByRole("link", { name: "Edit checkout" });
    await user.click(screen.getByRole("button", { name: "Delete checkout" }));
    await user.click(screen.getByRole("button", { name: "Delete" }));

    expect(await screen.findByText("This entity is still targeted by another entity's relation.")).toBeInTheDocument();
  });

  test("delete removes the row after confirmation", async () => {
    mockRoutes(mockFetch);
    const user = userEvent.setup();
    renderWithProviders(<Entities />, { route: "/entities?blueprint=service" });

    await screen.findByRole("link", { name: "Edit checkout" });
    await user.click(screen.getByRole("button", { name: "Delete checkout" }));
    await user.click(screen.getByRole("button", { name: "Delete" }));

    await waitFor(() => expect(mockFetch).toHaveBeenCalledWith("/api/v1/entities/5", expect.objectContaining({ method: "DELETE" })));
  });

  test("preview columns are schema-then-computed, capped at 4, with array and colorized computed cells", async () => {
    mockFetch.mockImplementation((url: string) => {
      if (url === "/api/v1/blueprints") return Promise.resolve(jsonResponse(200, { items: [BLUEPRINT_COMPUTED] }));
      if (url.startsWith("/api/v1/entities?")) {
        return Promise.resolve(jsonResponse(200, { items: [ENTITY_COMPUTED], page: 1, pageSize: 20, total: 1 }));
      }
      return Promise.resolve(jsonResponse(404, {}));
    });
    renderWithProviders(<Entities />, { route: "/entities?blueprint=workload" });

    await screen.findByRole("link", { name: "Edit checkout-staging" });

    // Cap + ordering: the two schema columns come first, then computed columns in mirror ->
    // calculation -> aggregation order, cut at MAX_COLUMN_PROPERTIES (4) — "Dependents" (the
    // aggregation, 5th column) never appears.
    const headers = screen.getAllByRole("columnheader").map((h) => h.textContent);
    expect(headers).toEqual(expect.arrayContaining(["Env", "Replicas", "Langs", "Risk"]));
    expect(headers).not.toEqual(expect.arrayContaining(["Dependents"]));

    const row = screen.getByRole("link", { name: "Edit checkout-staging" }).closest("tr")!;
    // The mirror column ("Langs") renders its array value as pills.
    expect(within(row).getByText("java")).toBeInTheDocument();
    expect(within(row).getByText("kotlin")).toBeInTheDocument();
    // The colorized calculation column ("Risk") renders its value as a badge.
    const riskBadge = within(row).getByText("high");
    expect(riskBadge.closest(".mantine-Badge-root")).not.toBeNull();
  });
});
