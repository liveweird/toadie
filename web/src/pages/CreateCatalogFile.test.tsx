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
  await user.type(screen.getByLabelText(/^owner( \*)?$/i), "group:default/platform");
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

  test("an invalid label key blocks submission with the key error", async () => {
    mockPostStatus(mockFetch, 201);
    const user = userEvent.setup();
    renderCreate();

    await fillMinimalForm(user);
    await user.click(screen.getByRole("button", { name: /add label/i }));
    await user.type(screen.getByLabelText("Labels Key 1"), "a/b/c");
    await user.type(screen.getByLabelText("Labels Value 1"), "backend");
    await user.click(screen.getByRole("button", { name: /^create$/i }));

    expect(await screen.findByText(/Key must be/)).toBeInTheDocument();
    expect(findSaveCall(mockFetch)).toBeUndefined();
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
      metadata: { name: "my-svc", namespace: "default", labels: {}, annotations: {}, tags: [], links: [] },
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
    expect(screen.getByLabelText(/^owner( \*)?$/i)).toBeInTheDocument();
    fireEvent.click(screen.getByLabelText("Kind", { selector: "input" }));
    fireEvent.click(await screen.findByRole("option", { name: "Group" }));

    // Group has no owner/lifecycle but gains profile + membership fields.
    expect(screen.queryByLabelText(/^owner( \*)?$/i)).not.toBeInTheDocument();
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
      metadata: { name: "team-a", namespace: "default", labels: {}, annotations: {}, tags: [], links: [] },
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
    await user.type(screen.getByLabelText(/^owner( \*)?$/i), "team-a");
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

  test("the YAML preview follows the form values", async () => {
    mockPostStatus(mockFetch, 201);
    const user = userEvent.setup();
    renderCreate();

    const preview = screen.getByLabelText("YAML preview");
    expect(preview).toHaveTextContent("kind: Component");

    await user.type(screen.getByLabelText(/^name( \*)?$/i), "preview-svc");
    expect(preview).toHaveTextContent("name: preview-svc");
  });

  test("the reference panel lists unresolved refs and the unverifiable count", async () => {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if ((init?.method ?? "GET") === "POST" && url === "/api/v1/catalog-files/check") {
        return Promise.resolve(
          jsonResponse(200, {
            findings: [
              { field: "spec.dependsOn", reference: "component:ghost", status: "MISSING" },
              { field: "spec.dependsOn", reference: "orders-db", status: "KIND_REQUIRED" },
              { field: "spec.owner", reference: "team-x", status: "UNVERIFIABLE" },
            ],
          }),
        );
      }
      return Promise.resolve(jsonResponse(404, {}));
    });
    renderCreate();

    expect(await screen.findByText("Unresolved references")).toBeInTheDocument();
    expect(screen.getByText("component:ghost")).toBeInTheDocument();
    expect(screen.getByText("orders-db")).toBeInTheDocument();
    expect(screen.getByText(/1 reference points at a kind/)).toBeInTheDocument();
    // The create page carries the unsaved-self-reference note.
    expect(screen.getByText(/references to itself show as not found/i)).toBeInTheDocument();
  });

  test("the reference panel shows the all-clear line when everything resolves", async () => {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if ((init?.method ?? "GET") === "POST" && url === "/api/v1/catalog-files/check") {
        return Promise.resolve(jsonResponse(200, { findings: [] }));
      }
      return Promise.resolve(jsonResponse(404, {}));
    });
    renderCreate();

    expect(await screen.findByText("All checkable references resolve.")).toBeInTheDocument();
    expect(screen.queryByText("Unresolved references")).not.toBeInTheDocument();
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
