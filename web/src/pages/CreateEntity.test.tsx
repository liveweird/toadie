import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor } from "@testing-library/react";
import { Route, Routes, useLocation } from "react-router-dom";
import CreateEntity from "./CreateEntity";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

type FetchMock = ReturnType<typeof vi.fn>;

const BLUEPRINTS = [
  {
    id: 1,
    identifier: "service",
    title: "Service",
    schema: { properties: {}, required: [] },
    relations: {},
    mirrorProperties: {},
    calculationProperties: {},
    aggregationProperties: {},
  },
];

function PathProbe() {
  const location = useLocation();
  return <div data-testid="probe">{location.pathname + location.search}</div>;
}

function renderCreate(route: string) {
  return renderWithProviders(
    <Routes>
      <Route path="/entities/new" element={<CreateEntity />} />
      <Route path="/entities" element={<PathProbe />} />
    </Routes>,
    { route },
  );
}

describe("CreateEntity page", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    mockFetch.mockImplementation((url: string) =>
      url === "/api/v1/blueprints" ? Promise.resolve(jsonResponse(200, { items: BLUEPRINTS })) : Promise.resolve(jsonResponse(404, {})),
    );
    localStorage.setItem("toadie.auth.token", "fake-token");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("an unknown ?blueprint= bounces to the entities list", async () => {
    renderCreate("/entities/new?blueprint=nope");
    await waitFor(() => expect(screen.getByTestId("probe")).toHaveTextContent("/entities"));
  });

  test("a known blueprint renders the editor", async () => {
    renderCreate("/entities/new?blueprint=service");
    expect(await screen.findByRole("heading", { name: "New entity" })).toBeInTheDocument();
    expect(screen.getByLabelText(/^Identifier/)).toBeInTheDocument();
  });

  test("submitting a valid form POSTs the entity and navigates to the blueprint-scoped list", async () => {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      const method = init?.method ?? "GET";
      if (method === "GET" && url === "/api/v1/blueprints") return Promise.resolve(jsonResponse(200, { items: BLUEPRINTS }));
      if (method === "POST" && url === "/api/v1/entities") {
        return Promise.resolve(
          jsonResponse(201, {
            id: 9,
            blueprint: "service",
            blueprintId: 1,
            identifier: "checkout",
            title: "Checkout",
            properties: {},
            relations: {},
            findings: [],
            createdBy: 1,
            creatorName: "Alice",
            creatorDeleted: false,
            createdAt: 0,
            updatedAt: 0,
          }),
        );
      }
      return Promise.resolve(jsonResponse(404, {}));
    });
    const user = userEvent.setup();
    renderCreate("/entities/new?blueprint=service");

    await user.type(await screen.findByLabelText(/^Identifier/), "checkout");
    await user.type(screen.getByLabelText(/^Title/), "Checkout");
    await user.click(screen.getByRole("button", { name: "Create" }));

    await waitFor(() =>
      expect(mockFetch).toHaveBeenCalledWith("/api/v1/entities", expect.objectContaining({ method: "POST" })),
    );
    await waitFor(() => expect(screen.getByTestId("probe")).toHaveTextContent("/entities?blueprint=service"));
  });

  test("a 400 rejection renders the fixed invalid message inline and stays on the page", async () => {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      const method = init?.method ?? "GET";
      if (method === "GET" && url === "/api/v1/blueprints") return Promise.resolve(jsonResponse(200, { items: BLUEPRINTS }));
      if (method === "POST" && url === "/api/v1/entities") return Promise.resolve(jsonResponse(400, null));
      return Promise.resolve(jsonResponse(404, {}));
    });
    const user = userEvent.setup();
    renderCreate("/entities/new?blueprint=service");

    await user.type(await screen.findByLabelText(/^Identifier/), "checkout");
    await user.type(screen.getByLabelText(/^Title/), "Checkout");
    await user.click(screen.getByRole("button", { name: "Create" }));

    expect(await screen.findByText("This entity is not valid. Check the highlighted fields.")).toBeInTheDocument();
  });
});
