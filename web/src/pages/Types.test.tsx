import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor, within } from "@testing-library/react";
import Types from "./Types";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const TOKEN_KEY = "toadie.auth.token";
const ROLES_KEY = "toadie.auth.roles";

type FetchMock = ReturnType<typeof vi.fn>;

const REGISTRY = {
  items: [
    { id: 1, kind: "Component", types: ["service", "website"] },
    { id: 2, kind: "Group", types: ["team"] },
  ],
};

/** GET serves the dictionaries; mutations answer per [mutations]. */
function serveDictionaries(mockFetch: FetchMock, mutations: Record<string, { status: number; body?: unknown }> = {}) {
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    if (method === "GET" && url === "/api/v1/entity-types") {
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

describe("Types page", () => {
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
    serveDictionaries(mockFetch);
    renderWithProviders(<Types />);

    expect(await screen.findByText("Component")).toBeInTheDocument();
    expect(screen.getByText("service")).toBeInTheDocument();
    expect(screen.getByText("team")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /new dictionary/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /edit/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /delete/i })).not.toBeInTheDocument();
  });

  test("an admin creates a dictionary through the modal", async () => {
    serveDictionaries(mockFetch, {
      "POST /api/v1/entity-types": {
        status: 201,
        body: { id: 9, kind: "API", types: ["openapi"] },
      },
    });
    const user = userEvent.setup();
    renderWithProviders(<Types />);

    await user.click(await screen.findByRole("button", { name: /new dictionary/i }));
    const modal = screen.getByRole("dialog");
    await user.click(within(modal).getByLabelText(/^kind$/i, { selector: "input" }));
    await user.click(await screen.findByRole("option", { name: "API" }));
    await user.type(within(modal).getByRole("combobox", { name: /allowed types/i }), "openapi{Enter}grpc{Enter}");
    await user.click(within(modal).getByRole("button", { name: /save/i }));

    await waitFor(() => expect(findCall(mockFetch, "POST", "/api/v1/entity-types")).toBeDefined());
    const body = JSON.parse((findCall(mockFetch, "POST", "/api/v1/entity-types")![1] as RequestInit).body as string);
    expect(body).toEqual({ kind: "API", types: ["openapi", "grpc"] });
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  test("modal validation blocks an empty submission and User is not offered", async () => {
    serveDictionaries(mockFetch);
    const user = userEvent.setup();
    renderWithProviders(<Types />);

    await user.click(await screen.findByRole("button", { name: /new dictionary/i }));
    const modal = screen.getByRole("dialog");
    // The kind Select offers only type-bearing kinds — User's spec has no type field.
    await user.click(within(modal).getByLabelText(/^kind$/i, { selector: "input" }));
    expect(await screen.findByRole("option", { name: "Domain" })).toBeInTheDocument();
    expect(screen.queryByRole("option", { name: "User" })).not.toBeInTheDocument();
    await user.keyboard("{Escape}");
    await user.click(within(modal).getByRole("button", { name: /save/i }));

    expect(await screen.findByText(/pick a type-bearing kind/i)).toBeInTheDocument();
    expect(screen.getByText(/add at least one type/i)).toBeInTheDocument();
    expect(findCall(mockFetch, "POST", "/api/v1/entity-types")).toBeUndefined();
  });

  test("an admin edits a dictionary — the modal prefills and PUTs to its id", async () => {
    serveDictionaries(mockFetch, { "PUT /api/v1/entity-types/2": { status: 204 } });
    const user = userEvent.setup();
    renderWithProviders(<Types />);

    await user.click(await screen.findByRole("button", { name: "Edit Group" }));
    const modal = screen.getByRole("dialog");
    expect(within(modal).getByLabelText(/^kind$/i, { selector: "input" })).toHaveValue("Group");
    await user.type(within(modal).getByRole("combobox", { name: /allowed types/i }), "squad{Enter}");
    await user.click(within(modal).getByRole("button", { name: /save/i }));

    await waitFor(() => expect(findCall(mockFetch, "PUT", "/api/v1/entity-types/2")).toBeDefined());
    const body = JSON.parse((findCall(mockFetch, "PUT", "/api/v1/entity-types/2")![1] as RequestInit).body as string);
    expect(body).toEqual({ kind: "Group", types: ["team", "squad"] });
  });

  test("a 409 on save renders the conflict message inline", async () => {
    serveDictionaries(mockFetch, { "POST /api/v1/entity-types": { status: 409 } });
    const user = userEvent.setup();
    renderWithProviders(<Types />);

    await user.click(await screen.findByRole("button", { name: /new dictionary/i }));
    const modal = screen.getByRole("dialog");
    await user.click(within(modal).getByLabelText(/^kind$/i, { selector: "input" }));
    await user.click(await screen.findByRole("option", { name: "Component" }));
    await user.type(within(modal).getByRole("combobox", { name: /allowed types/i }), "service{Enter}");
    await user.click(within(modal).getByRole("button", { name: /save/i }));

    expect(await screen.findByText(/already has a type dictionary/i)).toBeInTheDocument();
    // The 409 is about the kind — it marks the FIELD, not the generic alert.
    expect(within(modal).getByLabelText(/^kind$/i, { selector: "input" })).toHaveAttribute(
      "aria-invalid",
      "true",
    );
    expect(screen.getByRole("dialog")).toBeInTheDocument();
  });

  test("a non-conflict failure keeps the generic alert", async () => {
    serveDictionaries(mockFetch, { "POST /api/v1/entity-types": { status: 500 } });
    const user = userEvent.setup();
    renderWithProviders(<Types />);

    await user.click(await screen.findByRole("button", { name: /new dictionary/i }));
    const modal = screen.getByRole("dialog");
    await user.click(within(modal).getByLabelText(/^kind$/i, { selector: "input" }));
    await user.click(await screen.findByRole("option", { name: "Component" }));
    await user.type(within(modal).getByRole("combobox", { name: /allowed types/i }), "service{Enter}");
    await user.click(within(modal).getByRole("button", { name: /save/i }));

    expect(await screen.findByText("Action failed (500)")).toBeInTheDocument();
  });

  test("an admin deletes a dictionary after confirming", async () => {
    serveDictionaries(mockFetch, { "DELETE /api/v1/entity-types/1": { status: 204 } });
    const user = userEvent.setup();
    renderWithProviders(<Types />);

    await user.click(await screen.findByRole("button", { name: "Delete Component" }));
    expect(await screen.findByText(/will not save with a spec.type until/i)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /^delete$/i }));

    await waitFor(() => expect(findCall(mockFetch, "DELETE", "/api/v1/entity-types/1")).toBeDefined());
  });
});
