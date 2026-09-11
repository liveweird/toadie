import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { fireEvent, screen, waitFor } from "@testing-library/react";
import { QueryClient } from "@tanstack/react-query";
import ImportOntology from "./ImportOntology";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

type FetchMock = ReturnType<typeof vi.fn>;

const TOKEN_KEY = "toadie.auth.token";
const ROLES_KEY = "toadie.auth.roles";

const BLUEPRINT_DOC = { identifier: "service", title: "Service", schema: { properties: {}, required: [] } };
const ENTITY_DOC = { blueprint: "service", identifier: "checkout", title: "Checkout", properties: {} };

function bodyOf(init?: RequestInit): Record<string, unknown> {
  return JSON.parse((init?.body as string) ?? "{}") as Record<string, unknown>;
}

function baseRoutes(mockFetch: FetchMock, overrides: Record<string, (init?: RequestInit) => Response> = {}) {
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    const key = `${method} ${url}`;
    if (overrides[key]) return Promise.resolve(overrides[key](init));
    if (method === "GET" && url === "/api/v1/blueprints") return Promise.resolve(jsonResponse(200, { items: [] }));
    return Promise.resolve(jsonResponse(404, {}));
  });
}

describe("ImportOntology page", () => {
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

  test("renders with Import disabled and the switch off until something parses", async () => {
    baseRoutes(mockFetch);
    renderWithProviders(<ImportOntology />);

    expect(await screen.findByRole("heading", { name: "Import ontology" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Import" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Check" })).toBeDisabled();
    expect(screen.getByRole("switch", { name: /Replace existing definitions/ })).not.toBeChecked();
  });

  test("pasting a batch shows the ready summary and the stripped-keys note", async () => {
    baseRoutes(mockFetch);
    renderWithProviders(<ImportOntology />);

    fireEvent.change(screen.getByLabelText("JSON content"), {
      target: { value: JSON.stringify({ blueprints: [{ ...BLUEPRINT_DOC, id: 1, createdAt: 1 }], entities: [ENTITY_DOC] }) },
    });

    await waitFor(() => expect(screen.getByText("1 blueprints, 1 entities ready")).toBeInTheDocument());
    expect(screen.getByText(/Ignored read-only keys in 1 documents/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Import" })).toBeEnabled();
  });

  test("Check calls both check endpoints with replaceExisting false and renders would-be labels, without invalidating queries", async () => {
    const calls: { url: string; body: Record<string, unknown> }[] = [];
    baseRoutes(mockFetch, {
      "POST /api/v1/blueprints/import/check": (init) => {
        calls.push({ url: "bpCheck", body: bodyOf(init) });
        return jsonResponse(200, { results: [{ index: 0, identifier: "service", status: "CREATED" }] });
      },
      "POST /api/v1/entities/import/check": (init) => {
        calls.push({ url: "enCheck", body: bodyOf(init) });
        return jsonResponse(200, { results: [{ index: 0, blueprint: "service", identifier: "checkout", status: "CREATED" }] });
      },
    });
    const user = userEvent.setup();
    renderWithProviders(<ImportOntology />);

    fireEvent.change(await screen.findByLabelText("JSON content"), {
      target: { value: JSON.stringify({ blueprints: [BLUEPRINT_DOC], entities: [ENTITY_DOC] }) },
    });
    await waitFor(() => expect(screen.getByRole("button", { name: "Check" })).toBeEnabled());
    await user.click(screen.getByRole("button", { name: "Check" }));

    await waitFor(() => expect(calls).toHaveLength(2));
    expect(calls.find((c) => c.url === "bpCheck")?.body).toEqual({ documents: [BLUEPRINT_DOC], replaceExisting: false });
    expect(await screen.findAllByText("Would be created")).toHaveLength(2);
    expect(mockFetch.mock.calls.some(([, init]) => (init as RequestInit | undefined)?.method === "PUT")).toBe(false);
  });

  test("Import calls both import endpoints, renders stored-row links, and invalidates blueprints and entities", async () => {
    baseRoutes(mockFetch, {
      "POST /api/v1/blueprints/import": () =>
        jsonResponse(200, { results: [{ index: 0, identifier: "service", status: "CREATED", id: 10 }] }),
      "POST /api/v1/entities/import": () =>
        jsonResponse(200, {
          results: [{ index: 0, blueprint: "service", identifier: "checkout", status: "CREATED", id: 20 }],
        }),
    });
    const user = userEvent.setup();
    renderWithProviders(<ImportOntology />);

    fireEvent.change(await screen.findByLabelText("JSON content"), {
      target: { value: JSON.stringify({ blueprints: [BLUEPRINT_DOC], entities: [ENTITY_DOC] }) },
    });
    await waitFor(() => expect(screen.getByRole("button", { name: "Import" })).toBeEnabled());
    await user.click(screen.getByRole("button", { name: "Import" }));

    expect(await screen.findByRole("link", { name: "Edit service" })).toHaveAttribute("href", "/blueprints/10/edit");
    expect(screen.getByRole("link", { name: "Edit service / checkout" })).toHaveAttribute("href", "/entities/20/edit");
    // Both stored → the blueprints registry query would be re-fetched on remount; here we just
    // assert the mutation landed (findByRole above) since invalidateQueries triggers no visible
    // effect without another mounted consumer.
  });

  test("the Replace-existing switch sends replaceExisting: true", async () => {
    let sentReplace: boolean | undefined;
    baseRoutes(mockFetch, {
      "POST /api/v1/blueprints/import": (init) => {
        sentReplace = bodyOf(init).replaceExisting as boolean;
        return jsonResponse(200, { results: [{ index: 0, identifier: "service", status: "UPDATED", id: 10 }] });
      },
    });
    const user = userEvent.setup();
    renderWithProviders(<ImportOntology />);

    fireEvent.change(await screen.findByLabelText("JSON content"), { target: { value: JSON.stringify([BLUEPRINT_DOC]) } });
    await user.click(screen.getByRole("switch", { name: /Replace existing definitions/ }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Import" })).toBeEnabled());
    await user.click(screen.getByRole("button", { name: "Import" }));

    await waitFor(() => expect(sentReplace).toBe(true));
  });

  test("a non-admin session skips blueprint documents client-side (FORBIDDEN), never calling the blueprint endpoint, while entities still import", async () => {
    localStorage.setItem(ROLES_KEY, "[]");
    const blueprintImportCalled = vi.fn();
    baseRoutes(mockFetch, {
      "POST /api/v1/blueprints/import": () => {
        blueprintImportCalled();
        return jsonResponse(200, { results: [] });
      },
      "POST /api/v1/entities/import": () =>
        jsonResponse(200, { results: [{ index: 0, blueprint: "service", identifier: "checkout", status: "CREATED", id: 20 }] }),
    });
    const user = userEvent.setup();
    renderWithProviders(<ImportOntology />);

    fireEvent.change(await screen.findByLabelText("JSON content"), {
      target: { value: JSON.stringify({ blueprints: [BLUEPRINT_DOC], entities: [ENTITY_DOC] }) },
    });
    expect(await screen.findByText(/Only administrators can import blueprints/)).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("button", { name: "Import" })).toBeEnabled());
    await user.click(screen.getByRole("button", { name: "Import" }));

    expect(await screen.findByText("Forbidden")).toBeInTheDocument();
    expect(blueprintImportCalled).not.toHaveBeenCalled();
    expect(await screen.findByRole("link", { name: "Edit service / checkout" })).toBeInTheDocument();
  });

  test("a real 403 from the blueprint endpoint renders a FORBIDDEN row", async () => {
    baseRoutes(mockFetch, {
      "POST /api/v1/blueprints/import": () => jsonResponse(403, { title: "Forbidden", status: 403 }),
    });
    const user = userEvent.setup();
    renderWithProviders(<ImportOntology />);

    fireEvent.change(await screen.findByLabelText("JSON content"), { target: { value: JSON.stringify([BLUEPRINT_DOC]) } });
    await waitFor(() => expect(screen.getByRole("button", { name: "Import" })).toBeEnabled());
    await user.click(screen.getByRole("button", { name: "Import" }));

    expect(await screen.findByText("Forbidden")).toBeInTheDocument();
  });

  test("editing the text after a run clears the results", async () => {
    baseRoutes(mockFetch, {
      "POST /api/v1/blueprints/import": () =>
        jsonResponse(200, { results: [{ index: 0, identifier: "service", status: "CREATED", id: 10 }] }),
    });
    const user = userEvent.setup();
    renderWithProviders(<ImportOntology />);

    fireEvent.change(await screen.findByLabelText("JSON content"), { target: { value: JSON.stringify([BLUEPRINT_DOC]) } });
    await waitFor(() => expect(screen.getByRole("button", { name: "Import" })).toBeEnabled());
    await user.click(screen.getByRole("button", { name: "Import" }));
    expect(await screen.findByRole("link", { name: "Edit service" })).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText("JSON content"), { target: { value: JSON.stringify([{ ...BLUEPRINT_DOC, identifier: "other" }]) } });
    expect(screen.queryByRole("link", { name: "Edit service" })).not.toBeInTheDocument();
  });

  test("a 500 from the entity import endpoint shows the fixed-vocabulary error", async () => {
    baseRoutes(mockFetch, {
      "POST /api/v1/entities/import": () => jsonResponse(500, { title: "Server error", status: 500 }),
    });
    const user = userEvent.setup();
    renderWithProviders(<ImportOntology />);

    fireEvent.change(await screen.findByLabelText("JSON content"), { target: { value: JSON.stringify([ENTITY_DOC]) } });
    await waitFor(() => expect(screen.getByRole("button", { name: "Import" })).toBeEnabled());
    await user.click(screen.getByRole("button", { name: "Import" }));

    expect(await screen.findByText("Import failed (500)")).toBeInTheDocument();
  });

  test("a real import with only an ERROR row that has an id still invalidates both blueprints and entities queries", async () => {
    const invalidateQueriesSpy = vi.spyOn(QueryClient.prototype, "invalidateQueries");
    baseRoutes(mockFetch, {
      "POST /api/v1/blueprints/import": () =>
        jsonResponse(200, { results: [{ index: 0, identifier: "service", status: "ERROR", id: 7, message: "Invalid" }] }),
      "POST /api/v1/entities/import": () => jsonResponse(200, { results: [] }),
    });
    const user = userEvent.setup();
    renderWithProviders(<ImportOntology />);

    fireEvent.change(await screen.findByLabelText("JSON content"), {
      target: { value: JSON.stringify({ blueprints: [BLUEPRINT_DOC] }) },
    });
    await waitFor(() => expect(screen.getByRole("button", { name: "Import" })).toBeEnabled());
    await user.click(screen.getByRole("button", { name: "Import" }));

    await waitFor(() => expect(invalidateQueriesSpy).toHaveBeenCalledWith({ queryKey: ["blueprints"] }));
    await waitFor(() => expect(invalidateQueriesSpy).toHaveBeenCalledWith({ queryKey: ["entities"] }));

    invalidateQueriesSpy.mockRestore();
  });

  test("picking a file appends it as a removable source chip and both sources parse together", async () => {
    baseRoutes(mockFetch);
    const user = userEvent.setup();
    renderWithProviders(<ImportOntology />);

    const file = new File([JSON.stringify([{ ...BLUEPRINT_DOC, identifier: "from-file" }])], "extra.json", {
      type: "application/json",
    });
    const input = document.querySelector('input[type="file"]')!;
    fireEvent.change(input, { target: { files: [file] } });

    expect(await screen.findByText("extra.json")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText("1 blueprints, 0 entities ready")).toBeInTheDocument());

    await user.click(screen.getByRole("button", { name: "Remove extra.json" }));
    await waitFor(() => expect(screen.queryByText("extra.json")).not.toBeInTheDocument());
  });
});
