import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { fireEvent, screen, waitFor } from "@testing-library/react";
import { Route, Routes, useLocation } from "react-router-dom";
import { notifications } from "@mantine/notifications";
import CreateCatalogFile from "./CreateCatalogFile";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const TOKEN_KEY = "toadie.auth.token";

type FetchMock = ReturnType<typeof vi.fn>;

function PathProbe() {
  const location = useLocation();
  return <div data-testid="probe">{location.pathname}</div>;
}

function renderCreate() {
  return renderWithProviders(
    <Routes>
      <Route path="/catalog-files/new" element={<CreateCatalogFile />} />
      <Route path="/catalog-files" element={<PathProbe />} />
    </Routes>,
    { route: "/catalog-files/new" },
  );
}

function mockPostStatus(mockFetch: FetchMock, status: number, body: unknown = { title: "x", status }) {
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    if ((init?.method ?? "GET") === "POST" && url === "/api/v1/catalog-files") {
      return Promise.resolve(jsonResponse(status, body));
    }
    return Promise.resolve(jsonResponse(404, {}));
  });
}

function findSaveCall(mockFetch: FetchMock) {
  return mockFetch.mock.calls.find(
    ([url, init]) => (init as RequestInit | undefined)?.method === "POST" && url === "/api/v1/catalog-files",
  );
}

async function fillMinimalForm(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText(/^name( \*)?$/i), "my-svc");
  await user.type(screen.getByLabelText(/^type( \*)?$/i, { selector: "input" }), "service");
  await user.type(screen.getByLabelText(/^lifecycle( \*)?$/i, { selector: "input" }), "production");
  await user.type(screen.getByLabelText(/^owner( \*)?$/i, { selector: "input" }), "group:default/platform");
}

describe("CreateCatalogFile page", () => {
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

  test("client-side validation blocks an empty submission", async () => {
    mockPostStatus(mockFetch, 201);
    const user = userEvent.setup();
    renderCreate();

    await user.click(screen.getByRole("button", { name: /^create$/i }));

    expect(
      await screen.findByText(/1–63 alphanumeric characters with single/i),
    ).toBeInTheDocument();
    expect(screen.getAllByText("Required").length).toBeGreaterThanOrEqual(3);
    // The advisory reference panel may POST /check — only the SAVE call must be absent.
    expect(findSaveCall(mockFetch)).toBeUndefined();
    expect(screen.queryByTestId("probe")).not.toBeInTheDocument();
  });

  test("labels come from the registry pickers and land in the payload", async () => {
    // The registry offers one Component label; the row's two Selects are the only way in.
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if ((init?.method ?? "GET") === "GET" && url === "/api/v1/labels") {
        return Promise.resolve(
          jsonResponse(200, { items: [{ id: 1, key: "tier", values: ["backend", "frontend"], kinds: ["Component"] }] }),
        );
      }
      if ((init?.method ?? "GET") === "POST" && url === "/api/v1/catalog-files") {
        return Promise.resolve(
          jsonResponse(201, {
            id: 9,
            kind: "Component",
            metadata: { name: "my-svc", namespace: "default" },
            spec: { type: "service", lifecycle: "production", owner: "group:default/platform" },
            createdBy: 1,
            creatorName: "A",
            creatorDeleted: false,
            createdAt: 1,
            updatedAt: 1,
          }),
        );
      }
      return Promise.resolve(jsonResponse(404, {}));
    });
    const user = userEvent.setup();
    renderCreate();

    await fillMinimalForm(user);
    // Adding is gated on the registry offering a label for this kind.
    await waitFor(() => expect(screen.getByRole("button", { name: /add label/i })).toBeEnabled());
    await user.click(screen.getByRole("button", { name: /add label/i }));
    await user.click(screen.getByRole("combobox", { name: "Labels Key 1" }));
    await user.click(await screen.findByRole("option", { name: "tier" }));
    await user.click(screen.getByRole("combobox", { name: "Labels Value 1" }));
    await user.click(await screen.findByRole("option", { name: "backend" }));
    await user.click(screen.getByRole("button", { name: /^create$/i }));

    await waitFor(() => expect(findSaveCall(mockFetch)).toBeDefined());
    const body = JSON.parse((findSaveCall(mockFetch)![1] as RequestInit).body as string);
    expect(body.metadata.labels).toEqual({ tier: "backend" });
  });

  test("with the identity pool loaded, an unresolved owner blocks submission inline", async () => {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if ((init?.method ?? "GET") === "GET" && url.startsWith("/api/v1/catalog-files?")) {
        return Promise.resolve(
          jsonResponse(200, {
            items: [
              {
                id: 1,
                kind: "Group",
                name: "team-a",
                namespace: "default",
                title: null,
                type: "team",
                lifecycle: null,
                owner: null,
                creatorName: "A",
                creatorDeleted: false,
                updatedAt: 1,
              },
            ],
            page: 1,
            pageSize: 100,
            total: 1,
          }),
        );
      }
      return Promise.resolve(jsonResponse(404, {}));
    });
    const user = userEvent.setup();
    renderCreate();

    await user.type(screen.getByLabelText(/^name( \*)?$/i), "my-svc");
    await user.type(screen.getByLabelText(/^type( \*)?$/i, { selector: "input" }), "service");
    await user.type(screen.getByLabelText(/^lifecycle( \*)?$/i, { selector: "input" }), "production");
    await user.type(screen.getByLabelText(/^owner( \*)?$/i, { selector: "input" }), "ghost-team");
    // Wait for the pool so the resolution half of validation is armed.
    await waitFor(() =>
      expect(mockFetch.mock.calls.some(([u]) => (u as string).startsWith("/api/v1/catalog-files?"))).toBe(true),
    );
    await user.click(screen.getByRole("button", { name: /^create$/i }));

    expect(await screen.findByText(/"ghost-team" does not resolve to a stored entity/)).toBeInTheDocument();
    expect(findSaveCall(mockFetch)).toBeUndefined();
  });

  test("a reference to the entity itself blocks submission inline", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(404, {})));
    const user = userEvent.setup();
    renderCreate();

    await user.type(screen.getByLabelText(/^name( \*)?$/i), "my-svc");
    await user.type(screen.getByLabelText(/^type( \*)?$/i, { selector: "input" }), "service");
    await user.type(screen.getByLabelText(/^lifecycle( \*)?$/i, { selector: "input" }), "production");
    await user.type(screen.getByLabelText(/^owner( \*)?$/i, { selector: "input" }), "group:default/platform");
    // The short form resolves via the default kind (component) to this very document — the
    // self check needs no identity pool, so it fires even though every fetch 404s here.
    await user.type(screen.getByLabelText(/subcomponent of/i, { selector: "input" }), "my-svc");
    await user.click(screen.getByRole("button", { name: /^create$/i }));

    expect(await screen.findByText(/"my-svc" points at this entity itself/)).toBeInTheDocument();
    expect(findSaveCall(mockFetch)).toBeUndefined();
  });

  test("tags come from the grouped category picker and land in the payload", async () => {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if ((init?.method ?? "GET") === "GET" && url === "/api/v1/tag-categories") {
        return Promise.resolve(
          jsonResponse(200, {
            items: [
              { id: 1, name: "Languages", tags: ["java", "c++"], kinds: ["Component"] },
              { id: 2, name: "Teams", tags: ["core"], kinds: ["Group"] },
            ],
          }),
        );
      }
      if ((init?.method ?? "GET") === "POST" && url === "/api/v1/catalog-files") {
        return Promise.resolve(
          jsonResponse(201, {
            id: 9,
            kind: "Component",
            metadata: { name: "my-svc", namespace: "default" },
            spec: { type: "service", lifecycle: "production", owner: "group:default/platform" },
            createdBy: 1,
            creatorName: "A",
            creatorDeleted: false,
            createdAt: 1,
            updatedAt: 1,
          }),
        );
      }
      return Promise.resolve(jsonResponse(404, {}));
    });
    const user = userEvent.setup();
    renderCreate();

    await fillMinimalForm(user);
    // Only the Component category's tags are offered, grouped under its name; the Group
    // category never appears for a Component document.
    await user.click(screen.getByRole("combobox", { name: /^tags$/i }));
    expect(await screen.findByText("Languages")).toBeInTheDocument();
    expect(screen.queryByRole("option", { name: "core" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("option", { name: "java" }));
    await user.click(screen.getByRole("button", { name: /^create$/i }));

    await waitFor(() => expect(findSaveCall(mockFetch)).toBeDefined());
    const body = JSON.parse((findSaveCall(mockFetch)![1] as RequestInit).body as string);
    expect(body.metadata.tags).toEqual(["java"]);
  });

  test("with no registry label for the kind, adding is disabled behind the hint", async () => {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if ((init?.method ?? "GET") === "GET" && url === "/api/v1/labels") {
        return Promise.resolve(
          jsonResponse(200, { items: [{ id: 1, key: "tier", values: ["backend"], kinds: ["API"] }] }),
        );
      }
      return Promise.resolve(jsonResponse(404, {}));
    });
    const user = userEvent.setup();
    renderCreate();

    expect(await screen.findByText(/No labels are defined for kind Component/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /add label/i })).toBeDisabled();
    // The registry HAS an API label — switching kind re-enables adding.
    await user.click(screen.getByRole("combobox", { name: /^kind$/i }));
    await user.click(await screen.findByRole("option", { name: "API" }));
    await waitFor(() => expect(screen.getByRole("button", { name: /add label/i })).toBeEnabled());
  });

  test("submits the trimmed request, toasts, and navigates to the list", async () => {
    const toast = vi.spyOn(notifications, "show").mockReturnValue("id");
    mockPostStatus(mockFetch, 201, {
      id: 9,
      kind: "Component",
      metadata: { name: "my-svc", namespace: "default" },
      spec: { type: "service", lifecycle: "production", owner: "group:default/platform" },
      createdBy: 1,
      creatorName: "A",
      creatorDeleted: false,
      createdAt: 1,
      updatedAt: 1,
    });
    const user = userEvent.setup();
    renderCreate();

    await fillMinimalForm(user);
    await user.click(screen.getByRole("button", { name: /^create$/i }));

    await waitFor(() => expect(screen.getByTestId("probe")).toHaveTextContent("/catalog-files"));
    const postCall = mockFetch.mock.calls.find(
      ([url, init]) => (init as RequestInit | undefined)?.method === "POST" && url === "/api/v1/catalog-files",
    );
    expect(postCall).toBeDefined();
    expect(JSON.parse((postCall![1] as RequestInit).body as string)).toEqual({
      kind: "Component",
      // Blank namespace on the wire — the server resolves it to the flagged default.
      metadata: { name: "my-svc", namespace: "", labels: {}, annotations: {}, tags: [], links: [] },
      spec: {
        type: "service",
        lifecycle: "production",
        owner: "group:default/platform",
        providesApis: [],
        consumesApis: [],
        dependsOn: [],
        dependencyOf: [],
      },
    });
    expect(toast).toHaveBeenCalledWith(
      expect.objectContaining({ message: "Catalog file created", color: "teal" }),
    );
    toast.mockRestore();
  });

  test("switching the kind swaps the per-kind sections and submits a Group", async () => {
    mockPostStatus(mockFetch, 201, {
      id: 11,
      kind: "Group",
      metadata: { name: "team-a", namespace: "default" },
      spec: { type: "team", children: [], members: [] },
      createdBy: 1,
      creatorName: "A",
      creatorDeleted: false,
      createdAt: 1,
      updatedAt: 1,
    });
    const user = userEvent.setup();
    renderCreate();

    // Component sections show owner + APIs; switch to Group.
    expect(screen.getByLabelText(/^owner( \*)?$/i, { selector: "input" })).toBeInTheDocument();
    fireEvent.click(screen.getByLabelText("Kind", { selector: "input" }));
    fireEvent.click(await screen.findByRole("option", { name: "Group" }));

    // Group has no owner/lifecycle but gains profile + membership fields.
    expect(screen.queryByLabelText(/^owner( \*)?$/i, { selector: "input" })).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/^lifecycle( \*)?$/i)).not.toBeInTheDocument();
    expect(screen.getByLabelText("Display name")).toBeInTheDocument();
    expect(screen.getByLabelText("Members", { selector: "input" })).toBeInTheDocument();

    await user.type(screen.getByLabelText(/^name( \*)?$/i), "team-a");
    await user.type(screen.getByLabelText(/^type( \*)?$/i, { selector: "input" }), "team");
    await user.click(screen.getByRole("button", { name: /^create$/i }));

    await waitFor(() => expect(screen.getByTestId("probe")).toHaveTextContent("/catalog-files"));
    const postCall = mockFetch.mock.calls.find(
      ([url, init]) => (init as RequestInit | undefined)?.method === "POST" && url === "/api/v1/catalog-files",
    );
    expect(JSON.parse((postCall![1] as RequestInit).body as string)).toEqual({
      kind: "Group",
      metadata: { name: "team-a", namespace: "", labels: {}, annotations: {}, tags: [], links: [] },
      spec: { type: "team", children: [], members: [] },
    });
  });

  test("an API without its definition is blocked client-side", async () => {
    mockPostStatus(mockFetch, 201);
    const user = userEvent.setup();
    renderCreate();

    fireEvent.click(screen.getByLabelText("Kind", { selector: "input" }));
    fireEvent.click(await screen.findByRole("option", { name: "API" }));
    expect(screen.getByLabelText(/^definition( \*)?$/i)).toBeInTheDocument();

    await user.type(screen.getByLabelText(/^name( \*)?$/i), "billing-api");
    await user.type(screen.getByLabelText(/^type( \*)?$/i, { selector: "input" }), "openapi");
    await user.type(screen.getByLabelText(/^lifecycle( \*)?$/i, { selector: "input" }), "production");
    await user.type(screen.getByLabelText(/^owner( \*)?$/i, { selector: "input" }), "team-a");
    await user.click(screen.getByRole("button", { name: /^create$/i }));

    expect(await screen.findAllByText("Required")).not.toHaveLength(0);
    expect(findSaveCall(mockFetch)).toBeUndefined();
  });

  test("annotation and link rows can be added and removed", async () => {
    mockPostStatus(mockFetch, 201);
    const user = userEvent.setup();
    renderCreate();

    await user.click(screen.getByRole("button", { name: /add annotation/i }));
    await user.type(screen.getByLabelText("Annotations Key 1"), "github.com/project-slug");
    await user.click(screen.getByRole("button", { name: "Remove annotation 1" }));
    expect(screen.queryByLabelText("Annotations Key 1")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /add link/i }));
    await user.type(screen.getByLabelText("URL 1"), "https://example.com");
    await user.click(screen.getByRole("button", { name: "Remove link 1" }));
    expect(screen.queryByLabelText("URL 1")).not.toBeInTheDocument();
  });

  test("the owner picker suggests stored groups and inserts the shortened ref", async () => {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if ((init?.method ?? "GET") === "POST" && url === "/api/v1/catalog-files") {
        return Promise.resolve(jsonResponse(201, {
          id: 12, kind: "Component",
          metadata: { name: "my-svc", namespace: "default" },
          spec: { type: "service", lifecycle: "production", owner: "team-a" },
          createdBy: 1, creatorName: "A", creatorDeleted: false, createdAt: 1, updatedAt: 1,
        }));
      }
      if (url.startsWith("/api/v1/catalog-files?")) {
        // The identity pool behind the pickers.
        return Promise.resolve(jsonResponse(200, {
          items: [
            { id: 5, kind: "Group", name: "team-a", namespace: "default", title: null, type: "team", lifecycle: null, owner: null, creatorName: "A", creatorDeleted: false, updatedAt: 1 },
            { id: 6, kind: "API", name: "billing-api", namespace: "default", title: null, type: "openapi", lifecycle: "production", owner: null, creatorName: "A", creatorDeleted: false, updatedAt: 1 },
          ],
          page: 1, pageSize: 100, total: 2,
        }));
      }
      return Promise.resolve(jsonResponse(404, {}));
    });
    const user = userEvent.setup();
    renderCreate();

    await user.type(screen.getByLabelText(/^name( \*)?$/i), "my-svc");
    await user.type(screen.getByLabelText(/^type( \*)?$/i, { selector: "input" }), "service");
    await user.type(screen.getByLabelText(/^lifecycle( \*)?$/i, { selector: "input" }), "production");

    // Open the owner picker — the stored group is offered as its full identity;
    // the API is filtered out (wrong kind).
    fireEvent.click(screen.getByLabelText(/^owner( \*)?$/i, { selector: "input" }));
    await user.click(await screen.findByRole("option", { name: "group:default/team-a" }));
    expect(screen.queryByRole("option", { name: /billing-api/ })).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /^create$/i }));
    await waitFor(() => expect(screen.getByTestId("probe")).toHaveTextContent("/catalog-files"));
    const postCall = findSaveCall(mockFetch);
    expect(JSON.parse((postCall![1] as RequestInit).body as string).spec.owner).toBe("group:default/team-a");
  });

  test("the YAML preview follows the form values", async () => {
    mockPostStatus(mockFetch, 201);
    const user = userEvent.setup();
    renderCreate();

    const preview = screen.getByLabelText("YAML preview");
    expect(preview).toHaveTextContent("kind: Component");

    await user.type(screen.getByLabelText(/^name( \*)?$/i), "preview-svc");
    expect(preview).toHaveTextContent("name: preview-svc");
  });

  test("the reference panel lists the blocking findings", async () => {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if ((init?.method ?? "GET") === "POST" && url === "/api/v1/catalog-files/check") {
        return Promise.resolve(
          jsonResponse(200, {
            findings: [
              { field: "spec.dependsOn", reference: "component:ghost", status: "MISSING" },
              { field: "spec.dependsOn", reference: "orders-db", status: "KIND_REQUIRED" },
              { field: "spec.owner", reference: "component:team-x", status: "WRONG_KIND" },
            ],
          }),
        );
      }
      return Promise.resolve(jsonResponse(404, {}));
    });
    renderCreate();

    expect(await screen.findByText("References that will block saving")).toBeInTheDocument();
    expect(screen.getByText("component:ghost")).toBeInTheDocument();
    expect(screen.getByText("orders-db")).toBeInTheDocument();
    expect(screen.getByText("component:team-x")).toBeInTheDocument();
  });

  test("the reference panel shows the all-clear line when everything resolves", async () => {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if ((init?.method ?? "GET") === "POST" && url === "/api/v1/catalog-files/check") {
        return Promise.resolve(jsonResponse(200, { findings: [] }));
      }
      return Promise.resolve(jsonResponse(404, {}));
    });
    renderCreate();

    expect(await screen.findByText("All references resolve.")).toBeInTheDocument();
    expect(screen.queryByText("References that will block saving")).not.toBeInTheDocument();
  });

  test.each([
    [409, /already exists/i],
    [400, /validation error/i],
    [500, /create failed \(500\)/i],
  ])("a %i on save surfaces an error and stays on the form", async (status, pattern) => {
    mockPostStatus(mockFetch, status);
    const user = userEvent.setup();
    renderCreate();

    await fillMinimalForm(user);
    await user.click(screen.getByRole("button", { name: /^create$/i }));

    expect(await screen.findByText(pattern)).toBeInTheDocument();
    expect(screen.queryByTestId("probe")).not.toBeInTheDocument();
  });
});
