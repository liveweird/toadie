import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor } from "@testing-library/react";
import { Route, Routes, useLocation } from "react-router-dom";
import EditBlueprint from "./EditBlueprint";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const TOKEN_KEY = "toadie.auth.token";
const ROLES_KEY = "toadie.auth.roles";

type FetchMock = ReturnType<typeof vi.fn>;

const STORED = {
  id: 7,
  identifier: "microservice",
  title: "Microservice",
  description: "A deployable unit.",
  schema: {
    properties: {
      language: { type: "string", title: "Language" },
      priority: { type: "number", title: "Priority" },
    },
    required: ["language"],
  },
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
};

const SYSTEM_TEAM = {
  id: 3,
  identifier: "_team",
  title: "Team",
  description: "",
  schema: { properties: {}, required: [] },
  relations: {
    parent: { title: "Parent team", target: "_team", required: false, many: false },
  },
  mirrorProperties: {},
  calculationProperties: {},
  aggregationProperties: {},
  createdBy: 1,
  creatorName: "Alice",
  creatorDeleted: false,
  createdAt: 1,
  updatedAt: 1,
  system: true,
};

function PathProbe() {
  const location = useLocation();
  return <div data-testid="probe">{location.pathname}</div>;
}

function renderEdit(route = "/blueprints/7/edit") {
  return renderWithProviders(
    <Routes>
      <Route path="/blueprints/:id/edit" element={<EditBlueprint />} />
      <Route path="/blueprints" element={<PathProbe />} />
    </Routes>,
    { route },
  );
}

describe("EditBlueprint page", () => {
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

  test("a non-admin bounces to the list without fetching", () => {
    localStorage.setItem(ROLES_KEY, "[]");
    renderEdit();
    expect(screen.getByTestId("probe")).toHaveTextContent("/blueprints");
    expect(mockFetch).not.toHaveBeenCalled();
  });

  test("a non-numeric id redirects to the list", () => {
    renderEdit("/blueprints/abc/edit");
    expect(screen.getByTestId("probe")).toHaveTextContent("/blueprints");
  });

  test("pre-fills the form, PUTs the edited document, and navigates to the list", async () => {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      const method = init?.method ?? "GET";
      if (method === "GET" && url === "/api/v1/blueprints/7") {
        return Promise.resolve(jsonResponse(200, STORED));
      }
      if (method === "PUT" && url === "/api/v1/blueprints/7") {
        return Promise.resolve(new Response(null, { status: 204 }));
      }
      if (method === "GET" && url === "/api/v1/blueprints") {
        return Promise.resolve(jsonResponse(200, { items: [STORED] }));
      }
      return Promise.resolve(jsonResponse(404, {}));
    });
    const user = userEvent.setup();
    renderEdit();

    const identifierInput = (await screen.findByLabelText(/^identifier/i)) as HTMLInputElement;
    await waitFor(() => expect(identifierInput.value).toBe("microservice"));
    // The blueprint's own Title field and each property row's Title field share the label
    // text "Title" — the blueprint's is the first in document order (identity fields render
    // above the Properties fieldset).
    const titleInput = screen.getAllByLabelText(/^title/i)[0] as HTMLInputElement;
    expect(titleInput.value).toBe("Microservice");

    await user.click(screen.getByRole("button", { name: /^save$/i }));

    await waitFor(() => expect(screen.getByTestId("probe")).toHaveTextContent("/blueprints"));
    const putCall = mockFetch.mock.calls.find(
      ([url, init]) => (init as RequestInit | undefined)?.method === "PUT" && url === "/api/v1/blueprints/7",
    );
    expect(putCall).toBeDefined();
    const body = JSON.parse((putCall![1] as RequestInit).body as string);
    expect(body.identifier).toBe("microservice");
    expect(body.schema.required).toEqual(["language"]);
  });

  test("the stored first property starts expanded, the second starts collapsed", async () => {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      const method = init?.method ?? "GET";
      if (method === "GET" && url === "/api/v1/blueprints/7") {
        return Promise.resolve(jsonResponse(200, STORED));
      }
      if (method === "GET" && url === "/api/v1/blueprints") {
        return Promise.resolve(jsonResponse(200, { items: [STORED] }));
      }
      return Promise.resolve(jsonResponse(404, {}));
    });
    renderEdit();

    const languageToggle = await screen.findByRole("button", { name: "Toggle language" });
    expect(languageToggle).toHaveAttribute("aria-expanded", "true");
    const priorityToggle = screen.getByRole("button", { name: "Toggle priority" });
    expect(priorityToggle).toHaveAttribute("aria-expanded", "false");
  });

  test("404 on load shows the not-found alert with a back link", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(404, { title: "nf", status: 404 })));
    renderEdit();

    expect(await screen.findByText("Blueprint not found.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^save$/i })).not.toBeInTheDocument();
  });

  test("a 409 on save renders the conflict message and stays on the editor", async () => {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      const method = init?.method ?? "GET";
      if (method === "GET" && url === "/api/v1/blueprints/7") {
        return Promise.resolve(jsonResponse(200, STORED));
      }
      if (method === "PUT" && url === "/api/v1/blueprints/7") {
        return Promise.resolve(jsonResponse(409, { title: "Conflict", status: 409 }));
      }
      if (method === "GET" && url === "/api/v1/blueprints") {
        return Promise.resolve(jsonResponse(200, { items: [STORED] }));
      }
      return Promise.resolve(jsonResponse(404, {}));
    });
    const user = userEvent.setup();
    renderEdit();

    await waitFor(async () =>
      expect((await screen.findByLabelText(/^identifier/i) as HTMLInputElement).value).toBe("microservice"),
    );
    await user.click(screen.getByRole("button", { name: /^save$/i }));

    expect(await screen.findByText(/already exists/i)).toBeInTheDocument();
    expect(screen.queryByTestId("probe")).not.toBeInTheDocument();
  });

  test("a system blueprint shows the System badge, a read-only identifier, and locks its seeded row", async () => {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      const method = init?.method ?? "GET";
      if (method === "GET" && url === "/api/v1/blueprints/3") {
        return Promise.resolve(jsonResponse(200, SYSTEM_TEAM));
      }
      if (method === "GET" && url === "/api/v1/blueprints") {
        return Promise.resolve(jsonResponse(200, { items: [SYSTEM_TEAM] }));
      }
      return Promise.resolve(jsonResponse(404, {}));
    });
    renderEdit("/blueprints/3/edit");

    const identifierInput = (await screen.findByLabelText(/^identifier/i)) as HTMLInputElement;
    expect(identifierInput).toHaveAttribute("readonly");
    expect(identifierInput.value).toBe("_team");
    expect(screen.getByText("A system blueprint's identifier cannot be changed.")).toBeInTheDocument();
    expect(screen.getByText("System")).toBeInTheDocument();

    expect(screen.getByRole("button", { name: "Remove relation 1" })).toBeDisabled();
    expect(screen.getByText("Seeded")).toBeInTheDocument();
  });
});
