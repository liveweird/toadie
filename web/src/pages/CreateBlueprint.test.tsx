import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor, within } from "@testing-library/react";
import { Route, Routes, useLocation } from "react-router-dom";
import CreateBlueprint from "./CreateBlueprint";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const TOKEN_KEY = "toadie.auth.token";
const ROLES_KEY = "toadie.auth.roles";

type FetchMock = ReturnType<typeof vi.fn>;

function PathProbe() {
  const location = useLocation();
  return <div data-testid="probe">{location.pathname}</div>;
}

function renderCreate() {
  return renderWithProviders(
    <Routes>
      <Route path="/blueprints/new" element={<CreateBlueprint />} />
      <Route path="/blueprints" element={<PathProbe />} />
    </Routes>,
    { route: "/blueprints/new" },
  );
}

describe("CreateBlueprint page", () => {
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

  test("a non-admin bounces to the list", () => {
    localStorage.setItem(ROLES_KEY, "[]");
    renderCreate();
    expect(screen.getByTestId("probe")).toHaveTextContent("/blueprints");
  });

  test("an empty submit shows the identifier/title errors and sends nothing", async () => {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if ((init?.method ?? "GET") === "GET" && url === "/api/v1/blueprints") {
        return Promise.resolve(jsonResponse(200, { items: [] }));
      }
      return Promise.resolve(jsonResponse(404, {}));
    });
    const user = userEvent.setup();
    renderCreate();

    await user.click(await screen.findByRole("button", { name: /^create$/i }));

    expect(await screen.findByText(/1–100 characters of letters, digits/i)).toBeInTheDocument();
    expect(screen.getByText(/required, up to 100 characters/i)).toBeInTheDocument();
    expect(mockFetch.mock.calls.some(([, init]) => (init as RequestInit | undefined)?.method === "POST")).toBe(false);
  });

  test("a valid submit POSTs the Port-shaped request and navigates to the list", async () => {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      const method = init?.method ?? "GET";
      if (method === "GET" && url === "/api/v1/blueprints") {
        return Promise.resolve(jsonResponse(200, { items: [] }));
      }
      if (method === "POST" && url === "/api/v1/blueprints") {
        return Promise.resolve(jsonResponse(201, { id: 1 }));
      }
      return Promise.resolve(jsonResponse(404, {}));
    });
    const user = userEvent.setup();
    renderCreate();

    await user.type(await screen.findByLabelText(/^identifier/i), "microservice");
    await user.type(screen.getByLabelText(/^title/i), "Microservice");
    await user.click(screen.getByRole("button", { name: /^create$/i }));

    await waitFor(() => expect(screen.getByTestId("probe")).toHaveTextContent("/blueprints"));
    const postCall = mockFetch.mock.calls.find(
      ([url, init]) => (init as RequestInit | undefined)?.method === "POST" && url === "/api/v1/blueprints",
    );
    expect(postCall).toBeDefined();
    const body = JSON.parse((postCall![1] as RequestInit).body as string);
    expect(body.identifier).toBe("microservice");
    expect(body.title).toBe("Microservice");
    expect(body.schema).toEqual({ properties: {}, required: [] });
  });

  test("a collapsed row with a blank title auto-expands on Create and no POST fires", async () => {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if ((init?.method ?? "GET") === "GET" && url === "/api/v1/blueprints") {
        return Promise.resolve(jsonResponse(200, { items: [] }));
      }
      return Promise.resolve(jsonResponse(404, {}));
    });
    const user = userEvent.setup();
    renderCreate();

    await user.type(await screen.findByLabelText(/^identifier/i), "microservice");
    await user.type(screen.getByLabelText(/^title/i), "Microservice");

    await user.click(screen.getByRole("button", { name: "Add property" }));
    const row = screen.getByTestId("properties-row-0");
    await user.type(within(row).getByRole("textbox", { name: /^property id/i }), "lang");
    // Leave the property's own Title blank, then collapse the row before saving.
    await user.click(within(row).getByRole("button", { name: "Toggle lang" }));
    expect(within(row).queryByRole("textbox", { name: /^title/i })).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /^create$/i }));

    // The blocked submit re-opens the row and focuses it — the hidden required error is
    // never stranded behind a collapsed header.
    expect(await within(row).findByRole("textbox", { name: /^title/i })).toBeInTheDocument();
    expect(within(row).getByRole("button", { name: "Toggle lang" })).toHaveAttribute("aria-expanded", "true");
    expect(within(row).getByRole("textbox", { name: /^title/i })).toHaveAttribute("aria-invalid", "true");
    expect(mockFetch.mock.calls.some(([, init]) => (init as RequestInit | undefined)?.method === "POST")).toBe(false);
  });

  test("the JSON preview follows the identifier field", async () => {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if ((init?.method ?? "GET") === "GET" && url === "/api/v1/blueprints") {
        return Promise.resolve(jsonResponse(200, { items: [] }));
      }
      return Promise.resolve(jsonResponse(404, {}));
    });
    const user = userEvent.setup();
    renderCreate();

    await user.type(await screen.findByLabelText(/^identifier/i), "svc");
    expect(await screen.findByText(/"identifier": "svc"/)).toBeInTheDocument();
  });
});
