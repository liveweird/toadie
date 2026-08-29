import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor, within } from "@testing-library/react";
import Annotations from "./Annotations";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const TOKEN_KEY = "toadie.auth.token";
const ROLES_KEY = "toadie.auth.roles";

type FetchMock = ReturnType<typeof vi.fn>;

const REGISTRY = {
  items: [
    { id: 1, key: "github.com/project-slug", kinds: ["Component"] },
    { id: 2, key: "pagerduty.com/service-id", kinds: ["Component", "API"] },
  ],
};

/** GET serves the registry; mutations answer per [mutations]. */
function serveKeys(mockFetch: FetchMock, mutations: Record<string, { status: number; body?: unknown }> = {}) {
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    if (method === "GET" && url === "/api/v1/annotation-keys") {
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

describe("Annotations page", () => {
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
    serveKeys(mockFetch);
    renderWithProviders(<Annotations />);

    expect(await screen.findByText("github.com/project-slug")).toBeInTheDocument();
    expect(screen.getByText("API")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /new annotation key/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /edit/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /delete/i })).not.toBeInTheDocument();
  });

  test("an admin registers a key through the modal", async () => {
    serveKeys(mockFetch, {
      "POST /api/v1/annotation-keys": {
        status: 201,
        body: { id: 9, key: "grafana.com/dashboard-selector", kinds: ["Component"] },
      },
    });
    const user = userEvent.setup();
    renderWithProviders(<Annotations />);

    await user.click(await screen.findByRole("button", { name: /new annotation key/i }));
    const modal = screen.getByRole("dialog");
    await user.type(
      within(modal).getByLabelText(/^key$/i, { selector: "input" }),
      "grafana.com/dashboard-selector",
    );
    await user.click(within(modal).getByRole("combobox", { name: /applies to kinds/i }));
    await user.click(await screen.findByRole("option", { name: "Component" }));
    await user.click(within(modal).getByRole("button", { name: /save/i }));

    await waitFor(() => expect(findCall(mockFetch, "POST", "/api/v1/annotation-keys")).toBeDefined());
    const body = JSON.parse((findCall(mockFetch, "POST", "/api/v1/annotation-keys")![1] as RequestInit).body as string);
    expect(body).toEqual({ key: "grafana.com/dashboard-selector", kinds: ["Component"] });
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  test("modal validation blocks an empty submission", async () => {
    serveKeys(mockFetch);
    const user = userEvent.setup();
    renderWithProviders(<Annotations />);

    await user.click(await screen.findByRole("button", { name: /new annotation key/i }));
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: /save/i }));

    expect(await screen.findByText(/must be an optional lowercase-domain prefix/i)).toBeInTheDocument();
    expect(screen.getByText(/pick at least one kind/i)).toBeInTheDocument();
    expect(findCall(mockFetch, "POST", "/api/v1/annotation-keys")).toBeUndefined();
  });

  test("an admin edits a key — the modal prefills and PUTs to its id", async () => {
    serveKeys(mockFetch, { "PUT /api/v1/annotation-keys/2": { status: 204 } });
    const user = userEvent.setup();
    renderWithProviders(<Annotations />);

    await user.click(await screen.findByRole("button", { name: "Edit pagerduty.com/service-id" }));
    const modal = screen.getByRole("dialog");
    expect(within(modal).getByLabelText(/^key$/i, { selector: "input" })).toHaveValue(
      "pagerduty.com/service-id",
    );
    await user.click(within(modal).getByRole("combobox", { name: /applies to kinds/i }));
    await user.click(await screen.findByRole("option", { name: "System" }));
    await user.click(within(modal).getByRole("button", { name: /save/i }));

    await waitFor(() => expect(findCall(mockFetch, "PUT", "/api/v1/annotation-keys/2")).toBeDefined());
    const body = JSON.parse((findCall(mockFetch, "PUT", "/api/v1/annotation-keys/2")![1] as RequestInit).body as string);
    expect(body).toEqual({ key: "pagerduty.com/service-id", kinds: ["Component", "API", "System"] });
  });

  test("a 409 on save renders the conflict message inline", async () => {
    serveKeys(mockFetch, { "POST /api/v1/annotation-keys": { status: 409 } });
    const user = userEvent.setup();
    renderWithProviders(<Annotations />);

    await user.click(await screen.findByRole("button", { name: /new annotation key/i }));
    const modal = screen.getByRole("dialog");
    await user.type(within(modal).getByLabelText(/^key$/i, { selector: "input" }), "team");
    await user.click(within(modal).getByRole("combobox", { name: /applies to kinds/i }));
    await user.click(await screen.findByRole("option", { name: "Group" }));
    await user.click(within(modal).getByRole("button", { name: /save/i }));

    expect(await screen.findByText(/annotation key with this name already exists/i)).toBeInTheDocument();
    expect(screen.getByRole("dialog")).toBeInTheDocument();
  });

  test("an admin deletes a key after confirming", async () => {
    serveKeys(mockFetch, { "DELETE /api/v1/annotation-keys/1": { status: 204 } });
    const user = userEvent.setup();
    renderWithProviders(<Annotations />);

    await user.click(await screen.findByRole("button", { name: "Delete github.com/project-slug" }));
    expect(await screen.findByText(/will not save until the key is re-registered/i)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /^delete$/i }));

    await waitFor(() => expect(findCall(mockFetch, "DELETE", "/api/v1/annotation-keys/1")).toBeDefined());
  });
});
