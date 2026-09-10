import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor } from "@testing-library/react";
import { Route, Routes, useLocation } from "react-router-dom";
import Blueprints from "./Blueprints";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const TOKEN_KEY = "toadie.auth.token";
const ROLES_KEY = "toadie.auth.roles";

type FetchMock = ReturnType<typeof vi.fn>;

const REGISTRY = {
  items: [
    {
      id: 1,
      identifier: "microservice",
      title: "Microservice",
      schema: { properties: { language: { type: "string" } }, required: [] },
      relations: { owningTeam: { title: "Owned by", target: "team", required: false, many: false } },
      mirrorProperties: {},
      calculationProperties: {},
      aggregationProperties: {},
      createdBy: 1,
      creatorName: "Alice",
      creatorDeleted: false,
      createdAt: 1,
      updatedAt: 1,
      system: false,
    },
    {
      id: 2,
      identifier: "team",
      title: "Team",
      schema: { properties: {}, required: [] },
      relations: {},
      mirrorProperties: {},
      calculationProperties: {},
      aggregationProperties: {},
      createdBy: 1,
      creatorName: "Alice",
      creatorDeleted: false,
      createdAt: 1,
      updatedAt: 1,
      system: false,
    },
    {
      id: 3,
      identifier: "_team",
      title: "Team",
      schema: { properties: {}, required: [] },
      relations: { parent: { title: "Parent team", target: "_team", required: false, many: false } },
      mirrorProperties: {},
      calculationProperties: {},
      aggregationProperties: {},
      createdBy: 1,
      creatorName: "Alice",
      creatorDeleted: false,
      createdAt: 1,
      updatedAt: 1,
      system: true,
    },
  ],
};

function serveBlueprints(mockFetch: FetchMock, mutations: Record<string, { status: number; body?: unknown }> = {}) {
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    if (method === "GET" && url === "/api/v1/blueprints") {
      return Promise.resolve(jsonResponse(200, REGISTRY));
    }
    const mutation = mutations[`${method} ${url}`];
    if (mutation) {
      return Promise.resolve(jsonResponse(mutation.status, mutation.body ?? { title: "x", status: mutation.status }));
    }
    return Promise.resolve(jsonResponse(404, { title: "x", status: 404 }));
  });
}

function findCall(mockFetch: FetchMock, method: string, url: string) {
  return mockFetch.mock.calls.find(
    ([u, init]) => ((init as RequestInit | undefined)?.method ?? "GET") === method && u === url,
  );
}

function PathProbe() {
  const location = useLocation();
  return <div data-testid="probe">{location.pathname}</div>;
}

function renderBlueprints() {
  return renderWithProviders(
    <Routes>
      <Route path="/blueprints" element={<Blueprints />} />
      <Route path="/blueprints/:id/edit" element={<PathProbe />} />
    </Routes>,
    { route: "/blueprints" },
  );
}

describe("Blueprints page", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem(TOKEN_KEY, "fake-token");
    localStorage.setItem(ROLES_KEY, JSON.stringify(["ADMIN"]));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("a regular user gets the read-only table without actions", async () => {
    localStorage.setItem(ROLES_KEY, "[]");
    serveBlueprints(mockFetch);
    renderBlueprints();

    expect(await screen.findByText("microservice")).toBeInTheDocument();
    expect(screen.getByText("Microservice")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /new blueprint/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^edit/i })).not.toBeInTheDocument();
  });

  test("an admin sees property/relation counts and the New blueprint link", async () => {
    serveBlueprints(mockFetch);
    renderBlueprints();

    await screen.findByText("microservice");
    expect(screen.getByRole("link", { name: /new blueprint/i })).toHaveAttribute("href", "/blueprints/new");
    const row = screen.getByText("microservice").closest("tr")!;
    expect(row).toHaveTextContent("1"); // one property
  });

  test("Edit navigates to the editor route for that blueprint", async () => {
    serveBlueprints(mockFetch);
    const user = userEvent.setup();
    renderBlueprints();

    await user.click(await screen.findByRole("button", { name: "Edit microservice" }));
    await waitFor(() => expect(screen.getByTestId("probe")).toHaveTextContent("/blueprints/1/edit"));
  });

  test("a failed load shows the inline error alert", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(500, {})));
    renderBlueprints();

    expect(await screen.findByText("Could not load the blueprints")).toBeInTheDocument();
  });

  test("an empty registry shows the empty state", async () => {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if ((init?.method ?? "GET") === "GET" && url === "/api/v1/blueprints") {
        return Promise.resolve(jsonResponse(200, { items: [] }));
      }
      return Promise.resolve(jsonResponse(404, {}));
    });
    renderBlueprints();

    expect(await screen.findByText(/no blueprints defined yet/i)).toBeInTheDocument();
  });

  test("a 409 on delete shows the targeted-blueprint message", async () => {
    serveBlueprints(mockFetch, {
      "DELETE /api/v1/blueprints/2": {
        status: 409,
        body: { title: "Conflict", status: 409, detail: "Blueprint 'team' is the target of relations in: microservice" },
      },
    });
    const user = userEvent.setup();
    renderBlueprints();

    await user.click(await screen.findByRole("button", { name: "Delete team" }));
    await user.click(screen.getByRole("button", { name: /^delete$/i }));

    expect(
      await screen.findByText(/still referenced by another blueprint's relation or aggregation property/i),
    ).toBeInTheDocument();
    expect(findCall(mockFetch, "DELETE", "/api/v1/blueprints/2")).toBeDefined();
  });

  test("a system row shows the System badge, disables Delete with a tooltip, and Edit still navigates", async () => {
    serveBlueprints(mockFetch);
    const user = userEvent.setup();
    renderBlueprints();

    await screen.findByText("_team");
    const row = screen.getByText("_team").closest("tr")!;
    expect(row).toHaveTextContent("System");

    const deleteButton = screen.getByRole("button", { name: "Delete _team" });
    expect(deleteButton).toBeDisabled();
    await user.hover(deleteButton);
    expect(await screen.findByText("System blueprints cannot be deleted")).toBeInTheDocument();
    expect(findCall(mockFetch, "DELETE", "/api/v1/blueprints/3")).toBeUndefined();

    await user.click(screen.getByRole("button", { name: "Edit _team" }));
    await waitFor(() => expect(screen.getByTestId("probe")).toHaveTextContent("/blueprints/3/edit"));
  });

  test("an admin deletes a blueprint after confirming", async () => {
    serveBlueprints(mockFetch, { "DELETE /api/v1/blueprints/2": { status: 204 } });
    const user = userEvent.setup();
    renderBlueprints();

    await user.click(await screen.findByRole("button", { name: "Delete team" }));
    await user.click(screen.getByRole("button", { name: /^delete$/i }));

    await waitFor(() => expect(findCall(mockFetch, "DELETE", "/api/v1/blueprints/2")).toBeDefined());
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });
});
