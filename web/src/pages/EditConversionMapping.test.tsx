import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor } from "@testing-library/react";
import { Route, Routes, useLocation } from "react-router-dom";
import EditConversionMapping from "./EditConversionMapping";
import { conversionMappingResponse } from "../test/fixtures";
import { mockFetch, type MockRoute } from "../test/http";
import { renderWithProviders } from "../test/render";

const TOKEN_KEY = "toadie.auth.token";
const ROLES_KEY = "toadie.auth.roles";

function PathProbe() {
  const location = useLocation();
  return <div data-testid="probe">{location.pathname}</div>;
}

function renderEdit(routes: MockRoute[]) {
  const fetchStub = mockFetch([{ match: "/api/v1/conversion-mappings", respond: { status: 200, body: { items: [] } } }, ...routes]);
  vi.stubGlobal("fetch", fetchStub);
  renderWithProviders(
    <Routes>
      <Route path="/ontology/mappings/:id/edit" element={<EditConversionMapping />} />
      <Route path="/ontology/mappings" element={<PathProbe />} />
    </Routes>,
    { route: "/ontology/mappings/4/edit" },
  );
  return fetchStub;
}

const STORED = conversionMappingResponse({
  id: 4,
  name: "Stored",
  document: { schemaVersion: 1, apiVersion: "backstage.io/v1alpha1", pairs: [] },
  findings: [{ code: "UNKNOWN_BLUEPRINT", path: "/pairs/0/blueprint", message: "Blueprint 'gone' no longer exists" }],
  drift: [{ code: "VOCABULARY_DRIFT", path: "/pairs/1", message: "Values differ" }],
});

describe("EditConversionMapping page", () => {
  beforeEach(() => {
    localStorage.setItem(TOKEN_KEY, "fake-token");
    localStorage.setItem(ROLES_KEY, JSON.stringify(["ADMIN"]));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("a non-admin bounces to the list", () => {
    localStorage.setItem(ROLES_KEY, "[]");
    renderEdit([]);
    expect(screen.getByTestId("probe")).toHaveTextContent("/ontology/mappings");
  });

  test("loads the mapping into the form and shows stored findings and non-blocking drift", async () => {
    renderEdit([{ match: "/api/v1/conversion-mappings/4", respond: { status: 200, body: STORED } }]);

    expect(await screen.findByDisplayValue("Stored")).toBeInTheDocument();
    expect((screen.getByLabelText(/^mapping document/i) as HTMLTextAreaElement).value).toContain('"pairs": []');
    expect(screen.getByText(/this mapping went stale/i)).toBeInTheDocument();
    expect(screen.getByText("Blueprint 'gone' no longer exists")).toBeInTheDocument();
    expect(screen.getByText(/does not block saving/i)).toBeInTheDocument();
    expect(screen.getByText("Values differ")).toBeInTheDocument();
  });

  test("Save PUTs the edited name and returns to the list", async () => {
    const user = userEvent.setup();
    const fetchStub = renderEdit([
      { match: "/api/v1/conversion-mappings/4", respond: { status: 200, body: STORED } },
      { method: "PUT", match: "/api/v1/conversion-mappings/4", respond: new Response(null, { status: 204 }) },
    ]);

    const name = await screen.findByDisplayValue("Stored");
    await user.clear(name);
    await user.type(name, "Renamed");
    await user.click(screen.getByRole("button", { name: /^save$/i }));

    await waitFor(() => expect(screen.getByTestId("probe")).toHaveTextContent("/ontology/mappings"));
    const put = fetchStub.mock.calls.find(([, init]) => init?.method === "PUT")!;
    expect(JSON.parse(put[1]!.body as string).name).toBe("Renamed");
  });

  test("a 404 on load shows the gone message with a way back", async () => {
    renderEdit([{ match: "/api/v1/conversion-mappings/4", respond: { status: 404, body: { title: "Not Found", status: 404 } } }]);

    expect(await screen.findByText("This conversion mapping no longer exists")).toBeInTheDocument();
    expect(screen.getAllByRole("link", { name: "Back to conversion mappings" }).length).toBeGreaterThan(0);
  });

  test("a 404 on save shows the gone message", async () => {
    const user = userEvent.setup();
    renderEdit([
      { match: "/api/v1/conversion-mappings/4", respond: { status: 200, body: STORED } },
      { method: "PUT", match: "/api/v1/conversion-mappings/4", respond: { status: 404, body: { title: "Not Found", status: 404 } } },
    ]);

    await screen.findByDisplayValue("Stored");
    await user.click(screen.getByRole("button", { name: /^save$/i }));

    expect(await screen.findByText(/no longer exists — reload the page/i)).toBeInTheDocument();
  });
});
