import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor } from "@testing-library/react";
import { Route, Routes, useLocation } from "react-router-dom";
import CreateConversionMapping from "./CreateConversionMapping";
import { conversionMappingResponse } from "../test/fixtures";
import { mockFetch, type MockRoute } from "../test/http";
import { renderWithProviders } from "../test/render";

const TOKEN_KEY = "toadie.auth.token";
const ROLES_KEY = "toadie.auth.roles";

function PathProbe() {
  const location = useLocation();
  return <div data-testid="probe">{location.pathname}</div>;
}

function renderCreate(routes: MockRoute[]) {
  const fetchStub = mockFetch([{ match: "/api/v1/conversion-mappings", respond: { status: 200, body: { items: [] } } }, ...routes]);
  vi.stubGlobal("fetch", fetchStub);
  renderWithProviders(
    <Routes>
      <Route path="/ontology/mappings/new" element={<CreateConversionMapping />} />
      <Route path="/ontology/mappings" element={<PathProbe />} />
    </Routes>,
    { route: "/ontology/mappings/new" },
  );
  return fetchStub;
}

const posts = (fetchStub: ReturnType<typeof mockFetch>) => fetchStub.mock.calls.filter(([, init]) => init?.method === "POST");

async function replaceDocument(user: ReturnType<typeof userEvent.setup>, text: string) {
  const field = screen.getByLabelText(/^mapping document/i);
  await user.clear(field);
  await user.click(field);
  await user.paste(text);
}

describe("CreateConversionMapping page", () => {
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
    renderCreate([]);
    expect(screen.getByTestId("probe")).toHaveTextContent("/ontology/mappings");
  });

  test("a blank name and a malformed document block the submit and send nothing", async () => {
    const user = userEvent.setup();
    const fetchStub = renderCreate([]);

    await replaceDocument(user, "{ nope");
    await user.click(await screen.findByRole("button", { name: /^create$/i }));

    expect(await screen.findByText("Enter a name")).toBeInTheDocument();
    expect(screen.getByText(/not valid JSON/i)).toBeInTheDocument();
    expect(posts(fetchStub)).toHaveLength(0);
  });

  test("a valid submit POSTs the name and the parsed document, then returns to the list", async () => {
    const user = userEvent.setup();
    const fetchStub = renderCreate([
      { method: "POST", match: "/api/v1/conversion-mappings", respond: { status: 201, body: conversionMappingResponse({ id: 9 }) } },
    ]);

    await user.type(await screen.findByLabelText(/^name/i), "  My mapping ");
    await user.click(screen.getByRole("button", { name: /^create$/i }));

    await waitFor(() => expect(screen.getByTestId("probe")).toHaveTextContent("/ontology/mappings"));
    const [, init] = posts(fetchStub)[0];
    expect(JSON.parse(init!.body as string)).toEqual({
      name: "My mapping",
      document: { schemaVersion: 1, apiVersion: "backstage.io/v1alpha1", pairs: [] },
    });
  });

  test("a 400 with findings shows them positioned in the aside and stays on the page", async () => {
    const user = userEvent.setup();
    renderCreate([
      {
        method: "POST",
        match: "/api/v1/conversion-mappings",
        respond: {
          status: 400,
          body: {
            title: "Bad Request",
            status: 400,
            findings: [{ code: "UNKNOWN_BLUEPRINT", path: "/pairs/0/blueprint", message: "Unknown blueprint 'nope'" }],
          },
        },
      },
    ]);

    await user.type(await screen.findByLabelText(/^name/i), "Bad");
    await user.click(screen.getByRole("button", { name: /^create$/i }));

    expect(await screen.findByText("The mapping was not saved — it has blocking findings")).toBeInTheDocument();
    expect(screen.getByText("Unknown blueprint")).toBeInTheDocument();
    expect(screen.getByText("/pairs/0/blueprint")).toBeInTheDocument();
    expect(screen.getByText("Unknown blueprint 'nope'")).toBeInTheDocument();
    expect(screen.queryByTestId("probe")).not.toBeInTheDocument();
  });

  test("a 409 shows the name-taken message", async () => {
    const user = userEvent.setup();
    renderCreate([{ method: "POST", match: "/api/v1/conversion-mappings", respond: { status: 409, body: { title: "Conflict", status: 409 } } }]);

    await user.type(await screen.findByLabelText(/^name/i), "Taken");
    await user.click(screen.getByRole("button", { name: /^create$/i }));

    expect(await screen.findByText("A conversion mapping with this name already exists")).toBeInTheDocument();
  });

  test("a 400 without findings shows the size/registry-full message", async () => {
    const user = userEvent.setup();
    renderCreate([{ method: "POST", match: "/api/v1/conversion-mappings", respond: { status: 400, body: { title: "Bad Request", status: 400 } } }]);

    await user.type(await screen.findByLabelText(/^name/i), "Big");
    await user.click(screen.getByRole("button", { name: /^create$/i }));

    expect(await screen.findByText(/registry may be full/i)).toBeInTheDocument();
  });
});
