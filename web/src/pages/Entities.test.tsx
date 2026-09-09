import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor } from "@testing-library/react";
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
  properties: {},
  findings: [],
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
    expect(mockFetch.mock.calls.some(([url]) => (url as string).startsWith("/api/v1/entities?"))).toBe(false);
  });

  test("picking a blueprint via the URL lists its entities with identifier link and findings badge", async () => {
    mockRoutes(mockFetch);
    renderWithProviders(<Entities />, { route: "/entities?blueprint=service" });

    expect(await screen.findByRole("link", { name: "Edit checkout" })).toBeInTheDocument();
    expect(screen.getByText("1 finding")).toBeInTheDocument();
    // The boolean preview column renders a badge for its true value.
    expect(screen.getByText("True")).toBeInTheDocument();
    // A picked blueprint renders "New entity" as a real link (Mantine only forces a plain
    // <button> when `disabled` is set).
    expect(screen.getByRole("link", { name: "New entity" })).toHaveAttribute(
      "href",
      "/entities/new?blueprint=service",
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
});
