import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor, within } from "@testing-library/react";
import Tags from "./Tags";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const TOKEN_KEY = "toadie.auth.token";
const ROLES_KEY = "toadie.auth.roles";

type FetchMock = ReturnType<typeof vi.fn>;

const REGISTRY = {
  items: [
    { id: 1, name: "Languages", tags: ["java", "c++"], kinds: ["Component"] },
    { id: 2, name: "Teams", tags: ["core"], kinds: ["Group", "User"] },
  ],
};

/** GET serves the registry; mutations answer per [mutations]. */
function serveCategories(mockFetch: FetchMock, mutations: Record<string, { status: number; body?: unknown }> = {}) {
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    if (method === "GET" && url === "/api/v1/tag-categories") {
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

describe("Tags page", () => {
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
    serveCategories(mockFetch);
    renderWithProviders(<Tags />);

    expect(await screen.findByText("Languages")).toBeInTheDocument();
    expect(screen.getByText("java")).toBeInTheDocument();
    expect(screen.getByText("Group")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /new category/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /edit/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /delete/i })).not.toBeInTheDocument();
  });

  test("an admin creates a category through the modal", async () => {
    serveCategories(mockFetch, {
      "POST /api/v1/tag-categories": {
        status: 201,
        body: { id: 9, name: "Stages", tags: ["dev"], kinds: ["Component"] },
      },
    });
    const user = userEvent.setup();
    renderWithProviders(<Tags />);

    await user.click(await screen.findByRole("button", { name: /new category/i }));
    const modal = screen.getByRole("dialog");
    await user.type(within(modal).getByLabelText(/category name/i, { selector: "input" }), "Stages");
    await user.type(within(modal).getByRole("combobox", { name: /^tags$/i }), "dev{Enter}prod{Enter}");
    await user.click(within(modal).getByRole("combobox", { name: /applies to kinds/i }));
    await user.click(await screen.findByRole("option", { name: "Component" }));
    await user.click(within(modal).getByRole("button", { name: /save/i }));

    await waitFor(() => expect(findCall(mockFetch, "POST", "/api/v1/tag-categories")).toBeDefined());
    const body = JSON.parse((findCall(mockFetch, "POST", "/api/v1/tag-categories")![1] as RequestInit).body as string);
    expect(body).toEqual({ name: "Stages", tags: ["dev", "prod"], kinds: ["Component"] });
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  test("modal validation blocks an empty submission", async () => {
    serveCategories(mockFetch);
    const user = userEvent.setup();
    renderWithProviders(<Tags />);

    await user.click(await screen.findByRole("button", { name: /new category/i }));
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: /save/i }));

    expect(await screen.findByText(/category name must be/i)).toBeInTheDocument();
    expect(screen.getByText(/add at least one tag/i)).toBeInTheDocument();
    expect(screen.getByText(/pick at least one kind/i)).toBeInTheDocument();
    expect(findCall(mockFetch, "POST", "/api/v1/tag-categories")).toBeUndefined();
  });

  test("an admin edits a category — the modal prefills and PUTs to its id", async () => {
    serveCategories(mockFetch, { "PUT /api/v1/tag-categories/2": { status: 204 } });
    const user = userEvent.setup();
    renderWithProviders(<Tags />);

    await user.click(await screen.findByRole("button", { name: "Edit Teams" }));
    const modal = screen.getByRole("dialog");
    expect(within(modal).getByLabelText(/category name/i, { selector: "input" })).toHaveValue("Teams");
    await user.type(within(modal).getByRole("combobox", { name: /^tags$/i }), "platform{Enter}");
    await user.click(within(modal).getByRole("button", { name: /save/i }));

    await waitFor(() => expect(findCall(mockFetch, "PUT", "/api/v1/tag-categories/2")).toBeDefined());
    const body = JSON.parse((findCall(mockFetch, "PUT", "/api/v1/tag-categories/2")![1] as RequestInit).body as string);
    expect(body).toEqual({ name: "Teams", tags: ["core", "platform"], kinds: ["Group", "User"] });
  });

  test("a 409 on save renders the combined conflict message inline", async () => {
    serveCategories(mockFetch, { "POST /api/v1/tag-categories": { status: 409 } });
    const user = userEvent.setup();
    renderWithProviders(<Tags />);

    await user.click(await screen.findByRole("button", { name: /new category/i }));
    const modal = screen.getByRole("dialog");
    await user.type(within(modal).getByLabelText(/category name/i, { selector: "input" }), "Teams");
    await user.type(within(modal).getByRole("combobox", { name: /^tags$/i }), "core{Enter}");
    await user.click(within(modal).getByRole("combobox", { name: /applies to kinds/i }));
    await user.click(await screen.findByRole("option", { name: "Group" }));
    await user.click(within(modal).getByRole("button", { name: /save/i }));

    expect(await screen.findByText(/name or one of its tags is already in use/i)).toBeInTheDocument();
    expect(screen.getByRole("dialog")).toBeInTheDocument();
  });

  test("an admin deletes a category after confirming", async () => {
    serveCategories(mockFetch, { "DELETE /api/v1/tag-categories/1": { status: 204 } });
    const user = userEvent.setup();
    renderWithProviders(<Tags />);

    await user.click(await screen.findByRole("button", { name: "Delete Languages" }));
    expect(await screen.findByText(/will not save until the tags are re-registered/i)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /^delete$/i }));

    await waitFor(() => expect(findCall(mockFetch, "DELETE", "/api/v1/tag-categories/1")).toBeDefined());
  });
});
