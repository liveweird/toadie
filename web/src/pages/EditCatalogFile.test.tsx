import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor, within } from "@testing-library/react";
import { Route, Routes, useLocation } from "react-router-dom";
import EditCatalogFile from "./EditCatalogFile";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const TOKEN_KEY = "toadie.auth.token";

type FetchMock = ReturnType<typeof vi.fn>;

const STORED_FILE = {
  id: 7,
  kind: "Component",
  metadata: {
    name: "stored-svc",
    namespace: "team-a",
    title: "Stored",
    description: "desc",
    labels: { tier: "backend" },
    annotations: {},
    tags: ["java"],
    links: [],
  },
  spec: {
    type: "service",
    lifecycle: "production",
    owner: "group:platform",
    system: null,
    subcomponentOf: null,
    providesApis: [],
    consumesApis: [],
    dependsOn: [],
    dependencyOf: [],
  },
  createdBy: 1,
  creatorName: "Alice",
  creatorDeleted: false,
  createdAt: 1000,
  updatedAt: 2000,
};

function PathProbe() {
  const location = useLocation();
  return <div data-testid="probe">{location.pathname}</div>;
}

function renderEdit(route = "/catalog-files/7/edit") {
  return renderWithProviders(
    <Routes>
      <Route path="/catalog-files/:id/edit" element={<EditCatalogFile />} />
      <Route path="/catalog-files" element={<PathProbe />} />
    </Routes>,
    { route },
  );
}

function mockGetAndPut(mockFetch: FetchMock, putStatus = 204) {
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    if (method === "GET" && url === "/api/v1/catalog-files/7") {
      return Promise.resolve(jsonResponse(200, STORED_FILE));
    }
    if (method === "PUT" && url === "/api/v1/catalog-files/7") {
      return Promise.resolve(
        putStatus === 204
          ? new Response(null, { status: 204 })
          : jsonResponse(putStatus, { title: "x", status: putStatus }),
      );
    }
    return Promise.resolve(jsonResponse(404, {}));
  });
}

describe("EditCatalogFile page", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem(TOKEN_KEY, "fake-token");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("pre-fills the form, PUTs the edited document, and navigates to the list", async () => {
    mockGetAndPut(mockFetch);
    const user = userEvent.setup();
    renderEdit();

    const nameInput = (await screen.findByLabelText(/^name( \*)?$/i)) as HTMLInputElement;
    await waitFor(() => expect(nameInput.value).toBe("stored-svc"));
    expect((screen.getByLabelText("Title") as HTMLInputElement).value).toBe("Stored");

    await user.clear(screen.getByLabelText("Title"));
    await user.type(screen.getByLabelText("Title"), "Renamed");
    await user.click(screen.getByRole("button", { name: /^save$/i }));

    await waitFor(() => expect(screen.getByTestId("probe")).toHaveTextContent("/catalog-files"));
    const putCall = mockFetch.mock.calls.find(
      ([url, init]) => (init as RequestInit | undefined)?.method === "PUT" && url === "/api/v1/catalog-files/7",
    );
    expect(putCall).toBeDefined();
    expect(JSON.parse((putCall![1] as RequestInit).body as string)).toEqual({
      kind: "Component",
      metadata: {
        name: "stored-svc",
        namespace: "team-a",
        title: "Renamed",
        description: "desc",
        labels: { tier: "backend" },
        annotations: {},
        tags: ["java"],
        links: [],
      },
      spec: {
        type: "service",
        lifecycle: "production",
        owner: "group:platform",
        providesApis: [],
        consumesApis: [],
        dependsOn: [],
        dependencyOf: [],
      },
    });
  });

  test("404 on load shows the not-found alert with a back link", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(404, { title: "nf", status: 404 })));
    renderEdit();

    expect(await screen.findByText("Catalog file not found.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /back to catalog files/i })).toHaveAttribute(
      "href",
      "/catalog-files",
    );
    expect(screen.queryByRole("button", { name: /^save$/i })).not.toBeInTheDocument();
  });

  test.each([
    [404, /no longer exists/i],
    [409, /already exists/i],
    [500, /save failed \(500\)/i],
  ])("a %i on save surfaces an error and stays on the editor", async (status, pattern) => {
    mockGetAndPut(mockFetch, status);
    const user = userEvent.setup();
    renderEdit();

    const nameInput = (await screen.findByLabelText(/^name( \*)?$/i)) as HTMLInputElement;
    await waitFor(() => expect(nameInput.value).toBe("stored-svc"));
    await user.click(screen.getByRole("button", { name: /^save$/i }));

    expect(await screen.findByText(pattern)).toBeInTheDocument();
    expect(screen.queryByTestId("probe")).not.toBeInTheDocument();
  });

  test("a soft-rejected save opens the Save-anyway modal; confirming retries with allowInvalid", async () => {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      const method = init?.method ?? "GET";
      if (method === "GET" && url === "/api/v1/catalog-files/7") {
        return Promise.resolve(jsonResponse(200, STORED_FILE));
      }
      if (method === "PUT" && url === "/api/v1/catalog-files/7") {
        return Promise.resolve(jsonResponse(400, { title: "Bad Request", status: 400 }));
      }
      if (method === "PUT" && url === "/api/v1/catalog-files/7?allowInvalid=true") {
        return Promise.resolve(new Response(null, { status: 204 }));
      }
      if (method === "POST" && url === "/api/v1/catalog-files/check") {
        return Promise.resolve(
          jsonResponse(200, {
            findings: [
              {
                field: "spec.subcomponentOf",
                reference: "component:team-a/stored-svc",
                status: "SELF_REFERENCE",
              },
            ],
          }),
        );
      }
      return Promise.resolve(jsonResponse(404, {}));
    });
    const user = userEvent.setup();
    renderEdit();

    const nameInput = (await screen.findByLabelText(/^name( \*)?$/i)) as HTMLInputElement;
    await waitFor(() => expect(nameInput.value).toBe("stored-svc"));
    await user.type(
      screen.getByLabelText(/subcomponent of/i, { selector: "input" }),
      "component:team-a/stored-svc",
    );
    await user.click(screen.getByRole("button", { name: /^save$/i }));

    // The strict PUT was rejected for the self reference — the modal lists it (scoped: the
    // live check panel renders the same finding text beside the form).
    const modal = await screen.findByRole("dialog");
    expect(within(modal).getByText("Save with findings?")).toBeInTheDocument();
    expect(within(modal).getByText(/an entity cannot reference itself/)).toBeInTheDocument();

    await user.click(within(modal).getByRole("button", { name: /save anyway/i }));
    await waitFor(() => expect(screen.getByTestId("probe")).toHaveTextContent("/catalog-files"));
    const waived = mockFetch.mock.calls.find(
      ([url, init]) =>
        (init as RequestInit | undefined)?.method === "PUT" &&
        url === "/api/v1/catalog-files/7?allowInvalid=true",
    );
    expect(waived).toBeDefined();
  });

  test("a non-numeric id redirects to the list without fetching the file", () => {
    mockGetAndPut(mockFetch);
    renderEdit("/catalog-files/abc/edit");
    expect(screen.getByTestId("probe")).toHaveTextContent("/catalog-files");
    // The page-level pools (identities, namespaces) may fetch — the DETAIL must not.
    expect(
      mockFetch.mock.calls.some(([url]) => /\/api\/v1\/catalog-files\/\w+$/.test(url as string)),
    ).toBe(false);
  });
});
