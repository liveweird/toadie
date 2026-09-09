import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor } from "@testing-library/react";
import { Route, Routes } from "react-router-dom";
import EditEntity from "./EditEntity";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

type FetchMock = ReturnType<typeof vi.fn>;

const BLUEPRINTS = [
  {
    id: 1,
    identifier: "service",
    title: "Service",
    schema: { properties: { language: { type: "string", title: "Language" } }, required: [] },
    relations: { owner: { title: "Owner", target: "team", required: false, many: false } },
    mirrorProperties: {},
    calculationProperties: {},
    aggregationProperties: {},
  },
];

function renderEdit(route = "/entities/5/edit") {
  return renderWithProviders(
    <Routes>
      <Route path="/entities/:id/edit" element={<EditEntity />} />
    </Routes>,
    { route },
  );
}

describe("EditEntity page", () => {
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

  test("a stale entity (non-empty stored findings) shows the orange findings alert", async () => {
    mockFetch.mockImplementation((url: string) => {
      if (url === "/api/v1/blueprints") return Promise.resolve(jsonResponse(200, { items: BLUEPRINTS }));
      if (url === "/api/v1/entities/5") {
        return Promise.resolve(
          jsonResponse(200, {
            id: 5,
            blueprint: "service",
            blueprintId: 1,
            identifier: "checkout",
            title: "Checkout",
            properties: {},
            relations: {},
            findings: [{ code: "REQUIRED_MISSING", field: "properties.language", message: "Required" }],
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

    renderEdit();

    expect(await screen.findByText("This entity is out of date with its blueprint")).toBeInTheDocument();
    expect(screen.getByText("properties.language: Required")).toBeInTheDocument();
  });

  test("an entity whose blueprint no longer exists shows the blueprint-missing message", async () => {
    mockFetch.mockImplementation((url: string) => {
      if (url === "/api/v1/blueprints") return Promise.resolve(jsonResponse(200, { items: [] }));
      if (url === "/api/v1/entities/5") {
        return Promise.resolve(
          jsonResponse(200, {
            id: 5,
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

    renderEdit();

    expect(await screen.findByText("The blueprint of this entity no longer exists.")).toBeInTheDocument();
  });

  test("a missing entity shows the not-found message", async () => {
    mockFetch.mockImplementation((url: string) => {
      if (url === "/api/v1/blueprints") return Promise.resolve(jsonResponse(200, { items: BLUEPRINTS }));
      if (url === "/api/v1/entities/5") return Promise.resolve(jsonResponse(404, { detail: "not found" }));
      return Promise.resolve(jsonResponse(404, {}));
    });

    renderEdit();

    expect(await screen.findByText("Entity not found.")).toBeInTheDocument();
  });

  test("saving PUTs the edited document and navigates back to the blueprint-scoped list", async () => {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      const method = init?.method ?? "GET";
      if (method === "GET" && url === "/api/v1/blueprints") return Promise.resolve(jsonResponse(200, { items: BLUEPRINTS }));
      if (method === "GET" && url === "/api/v1/entities/5") {
        return Promise.resolve(
          jsonResponse(200, {
            id: 5,
            blueprint: "service",
            blueprintId: 1,
            identifier: "checkout",
            title: "Checkout",
            properties: { language: "kotlin" },
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
      if (method === "GET" && url.startsWith("/api/v1/entities?")) {
        return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 100, total: 0 }));
      }
      if (method === "PUT" && url === "/api/v1/entities/5") return Promise.resolve(new Response(null, { status: 204 }));
      return Promise.resolve(jsonResponse(404, {}));
    });

    const user = userEvent.setup();
    renderEdit();

    await screen.findByRole("combobox", { name: "Owner" });
    await user.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() =>
      expect(mockFetch).toHaveBeenCalledWith(
        "/api/v1/entities/5",
        expect.objectContaining({ method: "PUT" }),
      ),
    );
  });
});
