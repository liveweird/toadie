import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor, within } from "@testing-library/react";
import { Route, Routes, useLocation } from "react-router-dom";
import ConversionMappings from "./ConversionMappings";
import { conversionMappingResponse } from "../test/fixtures";
import { jsonResponse, mockFetch, type MockRoute } from "../test/http";
import { renderWithProviders } from "../test/render";

const TOKEN_KEY = "toadie.auth.token";
const ROLES_KEY = "toadie.auth.roles";

const MAPPINGS = [
  conversionMappingResponse({ id: 1, name: "Clean mapping", document: { pairs: [{}, {}] } }),
  conversionMappingResponse({
    id: 2,
    name: "Stale mapping",
    findings: [{ code: "UNKNOWN_BLUEPRINT", path: "/pairs/0/blueprint", message: "gone" }],
    drift: [
      { code: "VOCABULARY_DRIFT", path: "/pairs/0", message: "a" },
      { code: "REGISTRY_KEY_MISSING", path: "/pairs/1", message: "b" },
    ],
  }),
];

function PathProbe() {
  const location = useLocation();
  return <div data-testid="probe">{location.pathname}</div>;
}

function render(routes: MockRoute[] = []) {
  const fetchStub = mockFetch([{ match: "/api/v1/conversion-mappings", respond: { status: 200, body: { items: MAPPINGS } } }, ...routes]);
  vi.stubGlobal("fetch", fetchStub);
  renderWithProviders(
    <Routes>
      <Route path="/ontology/mappings" element={<ConversionMappings />} />
      <Route path="/ontology/mappings/:id/edit" element={<PathProbe />} />
      <Route path="/ontology/errors" element={<PathProbe />} />
    </Routes>,
    { route: "/ontology/mappings" },
  );
  return fetchStub;
}

describe("ConversionMappings page", () => {
  beforeEach(() => {
    localStorage.setItem(TOKEN_KEY, "fake-token");
    localStorage.setItem(ROLES_KEY, JSON.stringify(["ADMIN"]));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("a regular user reads the table without any mutation control", async () => {
    localStorage.setItem(ROLES_KEY, "[]");
    render();

    expect(await screen.findByText("Clean mapping")).toBeInTheDocument();
    expect(screen.getByRole("table", { name: "Conversion mappings" })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /new mapping/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^Edit /i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^Delete /i })).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Errors report" })).toHaveAttribute("href", "/ontology/errors");
  });

  test("status chips: valid, blocking findings and drift counts, plus the pair count", async () => {
    render();

    const clean = (await screen.findByText("Clean mapping")).closest("tr")!;
    expect(within(clean).getByText("Valid")).toBeInTheDocument();
    expect(clean).toHaveTextContent("2");
    const stale = screen.getByText("Stale mapping").closest("tr")!;
    expect(within(stale).getByText("1 blocking finding")).toBeInTheDocument();
    expect(within(stale).getByText("2 drifts")).toBeInTheDocument();
    expect(within(stale).queryByText("Valid")).not.toBeInTheDocument();
  });

  test("an admin gets New mapping and Edit that navigates to the editor", async () => {
    const user = userEvent.setup();
    render();

    await screen.findByText("Clean mapping");
    expect(screen.getByRole("link", { name: /new mapping/i })).toHaveAttribute("href", "/ontology/mappings/new");
    await user.click(screen.getByRole("button", { name: "Edit Stale mapping" }));
    await waitFor(() => expect(screen.getByTestId("probe")).toHaveTextContent("/ontology/mappings/2/edit"));
  });

  test("Delete confirms, DELETEs by id and refetches the list", async () => {
    const user = userEvent.setup();
    const fetchStub = render([{ method: "DELETE", match: "/api/v1/conversion-mappings/1", respond: new Response(null, { status: 204 }) }]);

    await user.click(await screen.findByRole("button", { name: "Delete Clean mapping" }));
    expect(await screen.findByText("Delete conversion mapping?")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /^delete$/i }));

    await waitFor(() =>
      expect(fetchStub.mock.calls.some(([url, init]) => url === "/api/v1/conversion-mappings/1" && init?.method === "DELETE")).toBe(true),
    );
    await waitFor(() =>
      expect(fetchStub.mock.calls.filter(([url, init]) => url === "/api/v1/conversion-mappings" && (init?.method ?? "GET") === "GET").length).toBeGreaterThan(1),
    );
  });

  test("a failed load shows the inline error alert", async () => {
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(jsonResponse(500, {}))));
    renderWithProviders(<ConversionMappings />);
    expect(await screen.findByText("Could not load the conversion mappings")).toBeInTheDocument();
  });

  test("an empty registry shows the empty state", async () => {
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(jsonResponse(200, { items: [] }))));
    renderWithProviders(<ConversionMappings />);
    expect(await screen.findByText("No conversion mappings yet")).toBeInTheDocument();
  });
});
