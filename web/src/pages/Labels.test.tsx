import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor, within } from "@testing-library/react";
import Labels from "./Labels";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const TOKEN_KEY = "toadie.auth.token";
const ROLES_KEY = "toadie.auth.roles";

type FetchMock = ReturnType<typeof vi.fn>;

const REGISTRY = {
  items: [
    { id: 1, key: "example.com/tier", values: ["backend", "frontend"], kinds: ["Component"] },
    { id: 2, key: "team", values: ["core"], kinds: ["Group", "User"] },
  ],
};

/** GET serves the registry; mutations answer per [mutations] and re-serve the registry after. */
function serveLabels(mockFetch: FetchMock, mutations: Record<string, { status: number; body?: unknown }> = {}) {
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    if (method === "GET" && url === "/api/v1/labels") {
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

describe("Labels page", () => {
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
    serveLabels(mockFetch);
    renderWithProviders(<Labels />);

    expect(await screen.findByText("example.com/tier")).toBeInTheDocument();
    expect(screen.getByText("backend")).toBeInTheDocument();
    expect(screen.getByText("Group")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /new label/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /edit/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /delete/i })).not.toBeInTheDocument();
  });

  test("an admin creates a label through the modal", async () => {
    serveLabels(mockFetch, {
      "POST /api/v1/labels": { status: 201, body: { id: 9, key: "stage", values: ["dev"], kinds: ["Component"] } },
    });
    const user = userEvent.setup();
    renderWithProviders(<Labels />);

    await user.click(await screen.findByRole("button", { name: /new label/i }));
    const modal = screen.getByRole("dialog");
    await user.type(within(modal).getByLabelText(/^key$/i), "stage");
    await user.type(within(modal).getByRole("combobox", { name: /allowed values/i }), "dev{Enter}prod{Enter}");
    await user.click(within(modal).getByRole("combobox", { name: /applies to kinds/i }));
    await user.click(await screen.findByRole("option", { name: "Component" }));
    await user.click(within(modal).getByRole("button", { name: /save/i }));

    await waitFor(() => expect(findCall(mockFetch, "POST", "/api/v1/labels")).toBeDefined());
    const body = JSON.parse((findCall(mockFetch, "POST", "/api/v1/labels")![1] as RequestInit).body as string);
    expect(body).toEqual({ key: "stage", values: ["dev", "prod"], kinds: ["Component"] });
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  test("modal validation blocks an empty submission", async () => {
    serveLabels(mockFetch);
    const user = userEvent.setup();
    renderWithProviders(<Labels />);

    await user.click(await screen.findByRole("button", { name: /new label/i }));
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: /save/i }));

    expect(await screen.findByText(/optional lowercase-domain prefix/i)).toBeInTheDocument();
    expect(screen.getByText(/add at least one allowed value/i)).toBeInTheDocument();
    expect(screen.getByText(/pick at least one kind/i)).toBeInTheDocument();
    expect(findCall(mockFetch, "POST", "/api/v1/labels")).toBeUndefined();
  });

  test("an admin edits a label — the modal prefills and PUTs to its id", async () => {
    serveLabels(mockFetch, { "PUT /api/v1/labels/2": { status: 204 } });
    const user = userEvent.setup();
    renderWithProviders(<Labels />);

    await user.click(await screen.findByRole("button", { name: "Edit team" }));
    const modal = screen.getByRole("dialog");
    expect(within(modal).getByLabelText(/^key$/i)).toHaveValue("team");
    await user.type(within(modal).getByRole("combobox", { name: /allowed values/i }), "platform{Enter}");
    await user.click(within(modal).getByRole("button", { name: /save/i }));

    await waitFor(() => expect(findCall(mockFetch, "PUT", "/api/v1/labels/2")).toBeDefined());
    const body = JSON.parse((findCall(mockFetch, "PUT", "/api/v1/labels/2")![1] as RequestInit).body as string);
    expect(body).toEqual({ key: "team", values: ["core", "platform"], kinds: ["Group", "User"] });
  });

  test("a 409 on save renders the conflict message inline", async () => {
    serveLabels(mockFetch, { "POST /api/v1/labels": { status: 409 } });
    const user = userEvent.setup();
    renderWithProviders(<Labels />);

    await user.click(await screen.findByRole("button", { name: /new label/i }));
    const modal = screen.getByRole("dialog");
    await user.type(within(modal).getByLabelText(/^key$/i), "team");
    await user.type(within(modal).getByRole("combobox", { name: /allowed values/i }), "core{Enter}");
    await user.click(within(modal).getByRole("combobox", { name: /applies to kinds/i }));
    await user.click(await screen.findByRole("option", { name: "Group" }));
    await user.click(within(modal).getByRole("button", { name: /save/i }));

    expect(await screen.findByText(/a label with this key already exists/i)).toBeInTheDocument();
    expect(screen.getByRole("dialog")).toBeInTheDocument();
  });

  test("an admin deletes a label after confirming", async () => {
    serveLabels(mockFetch, { "DELETE /api/v1/labels/1": { status: 204 } });
    const user = userEvent.setup();
    renderWithProviders(<Labels />);

    await user.click(await screen.findByRole("button", { name: "Delete example.com/tier" }));
    expect(await screen.findByText(/will not save until the label is re-registered/i)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /^delete$/i }));

    await waitFor(() => expect(findCall(mockFetch, "DELETE", "/api/v1/labels/1")).toBeDefined());
  });
});
