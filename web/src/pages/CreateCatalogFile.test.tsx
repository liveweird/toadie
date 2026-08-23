import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor } from "@testing-library/react";
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
    expect(mockFetch).not.toHaveBeenCalled();
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
    expect(mockFetch).not.toHaveBeenCalled();
  });

  test("submits the trimmed request, toasts, and navigates to the list", async () => {
    const toast = vi.spyOn(notifications, "show").mockReturnValue("id");
    mockPostStatus(mockFetch, 201, {
      id: 9,
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

  test("the YAML preview follows the form values", async () => {
    mockPostStatus(mockFetch, 201);
    const user = userEvent.setup();
    renderCreate();

    const preview = screen.getByLabelText("YAML preview");
    expect(preview).toHaveTextContent("kind: Component");

    await user.type(screen.getByLabelText(/^name( \*)?$/i), "preview-svc");
    expect(preview).toHaveTextContent("name: preview-svc");
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
