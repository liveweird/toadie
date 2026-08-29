import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { notifications } from "@mantine/notifications";
import CatalogFiles from "./CatalogFiles";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const TOKEN_KEY = "toadie.auth.token";

type FetchMock = ReturnType<typeof vi.fn>;

type FileRow = {
  id: number;
  kind: string;
  name: string;
  namespace: string;
  title: string | null;
  type: string;
  lifecycle: string;
  owner: string;
  tags: string[];
  creatorName: string;
  creatorDeleted: boolean;
  updatedAt: number;
};

const SEED_FILES: FileRow[] = [
  {
    id: 1,
    kind: "Component",
    name: "payments-svc",
    namespace: "default",
    title: "Payments",
    type: "service",
    lifecycle: "production",
    owner: "group:platform",
    tags: ["java", "billing"],
    creatorName: "Alice Creator",
    creatorDeleted: false,
    updatedAt: 1755900000000,
  },
  {
    id: 2,
    kind: "API",
    name: "web-portal",
    namespace: "team-a",
    title: null,
    type: "website",
    lifecycle: "experimental",
    owner: "team-a",
    tags: [],
    creatorName: "Bob Builder",
    creatorDeleted: true,
    updatedAt: 1755900000000,
  },
];

function filesPage(items: FileRow[], total = items.length) {
  return jsonResponse(200, { items, page: 1, pageSize: 20, total });
}

// The namespace and tag filter combos load their options from the admin-curated registries.
const NAMESPACE_ENTRIES = {
  items: [
    { id: 1, value: "default", isDefault: true },
    { id: 2, value: "team-a", isDefault: false },
  ],
};
const TAG_CATEGORIES = {
  items: [{ id: 1, name: "Tech", tags: ["java", "billing"], kinds: ["Component"] }],
};

function registryResponse(url: string): Response | null {
  if (url.startsWith("/api/v1/dictionaries/namespaces"))
    return jsonResponse(200, NAMESPACE_ENTRIES);
  if (url.startsWith("/api/v1/tag-categories")) return jsonResponse(200, TAG_CATEGORIES);
  return null;
}

function setupMocks(mockFetch: FetchMock, byUrl: (url: string) => Response) {
  mockFetch.mockImplementation((url: string) => {
    const registry = registryResponse(url);
    if (registry) return Promise.resolve(registry);
    return url.startsWith("/api/v1/catalog-files")
      ? Promise.resolve(byUrl(url))
      : Promise.resolve(jsonResponse(404, {}));
  });
}

async function pickFilterOption(label: string, option: string) {
  fireEvent.click(screen.getByLabelText(label, { selector: "input" }));
  fireEvent.click(await screen.findByRole("option", { name: option }));
}

function renderPage() {
  return renderWithProviders(<CatalogFiles />, { route: "/catalog-files" });
}

async function calledWith(mockFetch: FetchMock, fragment: string) {
  await waitFor(() => {
    const called = mockFetch.mock.calls.some(
      ([url]) =>
        typeof url === "string" && url.startsWith("/api/v1/catalog-files?") && url.includes(fragment),
    );
    expect(called).toBe(true);
  });
}

describe("CatalogFiles page", () => {
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

  test("renders rows with title and tag badges; type/owner/creator are gone from the list", async () => {
    setupMocks(mockFetch, () => filesPage(SEED_FILES));
    renderPage();

    expect(await screen.findByText("payments-svc")).toBeInTheDocument();
    expect(screen.getByText("Payments")).toBeInTheDocument();
    expect(screen.getByText("java")).toBeInTheDocument();
    expect(screen.getByText("billing")).toBeInTheDocument();
    // The type/lifecycle, owner, and created-by columns were deliberately removed.
    expect(screen.queryByText("service")).not.toBeInTheDocument();
    expect(screen.queryByText("group:platform")).not.toBeInTheDocument();
    expect(screen.queryByText("Alice Creator")).not.toBeInTheDocument();
  });

  test("filters are collapsed by default and the toggle reveals them", async () => {
    setupMocks(mockFetch, () => filesPage(SEED_FILES));
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("payments-svc");
    const toggle = screen.getByRole("button", { name: /filters/i });
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByLabelText("Name")).not.toBeInTheDocument();

    await user.click(toggle);
    expect(toggle).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByLabelText("Name")).toBeInTheDocument();
    expect(screen.getByLabelText("Namespace", { selector: "input" })).toBeInTheDocument();
  });

  test("typing in the Name filter triggers a refetch with name=", async () => {
    setupMocks(mockFetch, () => filesPage(SEED_FILES));
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("payments-svc");
    await user.click(screen.getByRole("button", { name: /filters/i }));
    await user.type(screen.getByLabelText("Name"), "paym");

    await calledWith(mockFetch, "name=paym");
  });

  test("picking a Kind filter triggers a refetch with kind=", async () => {
    setupMocks(mockFetch, () => filesPage(SEED_FILES));
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("payments-svc");
    await user.click(screen.getByRole("button", { name: /filters/i }));
    fireEvent.click(screen.getByLabelText("Kind", { selector: "input" }));
    fireEvent.click(await screen.findByRole("option", { name: "Group" }));

    await calledWith(mockFetch, "kind=Group");
  });

  test("rows show their kind badge", async () => {
    setupMocks(mockFetch, () => filesPage(SEED_FILES));
    renderPage();

    await screen.findByText("payments-svc");
    expect(screen.getByText("Component")).toBeInTheDocument();
    expect(screen.getByText("API")).toBeInTheDocument();
  });

  test("picking a Namespace filter option triggers a refetch with namespace=", async () => {
    setupMocks(mockFetch, () => filesPage(SEED_FILES));
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("payments-svc");
    await user.click(screen.getByRole("button", { name: /filters/i }));
    await pickFilterOption("Namespace", "team-a");

    await calledWith(mockFetch, "namespace=team-a");
  });

  test("picking a Tags filter option triggers a refetch with tag=", async () => {
    setupMocks(mockFetch, () => filesPage(SEED_FILES));
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("payments-svc");
    await user.click(screen.getByRole("button", { name: /filters/i }));
    await pickFilterOption("Tags", "java");

    await calledWith(mockFetch, "tag=java");
  });

  test("clicking the Name sort header toggles to sort=-name", async () => {
    setupMocks(mockFetch, () => filesPage(SEED_FILES));
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("payments-svc");
    await user.click(screen.getByRole("button", { name: /^name$/i }));
    await calledWith(mockFetch, "sort=-name");
  });

  test("pagination button click triggers a GET with page=2", async () => {
    setupMocks(mockFetch, () => filesPage(SEED_FILES, 25));
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("payments-svc");
    await user.click(screen.getByRole("button", { name: "2" }));
    await calledWith(mockFetch, "page=2");
  });

  test("changing the page size refetches with pageSize and resets to page 1", async () => {
    setupMocks(mockFetch, () => filesPage(SEED_FILES));
    renderPage();

    await screen.findByText("payments-svc");
    fireEvent.click(screen.getByLabelText("Rows per page", { selector: "input" }));
    fireEvent.click(await screen.findByRole("option", { name: "40 / page" }));
    await waitFor(() => {
      const called = mockFetch.mock.calls.some(
        ([url]) =>
          typeof url === "string" &&
          url.startsWith("/api/v1/catalog-files?") &&
          url.includes("pageSize=40") &&
          url.includes("page=1"),
      );
      expect(called).toBe(true);
    });
  });

  test("shows an alert when the list fails to load", async () => {
    setupMocks(mockFetch, () => jsonResponse(500, { title: "boom", status: 500 }));
    renderPage();
    expect(await screen.findByText("Failed to load catalog files")).toBeInTheDocument();
  });

  test("shows the empty state when the API returns zero items", async () => {
    setupMocks(mockFetch, () => filesPage([], 0));
    renderPage();
    const empty = await screen.findByText("No catalog files");
    // The empty-state cell spans every column — pins the page's columnCount literal.
    expect(empty.closest("td")).toHaveAttribute(
      "colspan",
      String(screen.getAllByRole("columnheader").length),
    );
  });

  test("links to the create and edit pages", async () => {
    setupMocks(mockFetch, () => filesPage(SEED_FILES));
    renderPage();

    await screen.findByText("payments-svc");
    expect(screen.getByRole("link", { name: /new catalog file/i })).toHaveAttribute(
      "href",
      "/catalog-files/new",
    );
    expect(screen.getByRole("link", { name: "Edit payments-svc" })).toHaveAttribute(
      "href",
      "/catalog-files/1/edit",
    );
  });

  test("confirming a delete triggers DELETE, refetches, and toasts", async () => {
    const toast = vi.spyOn(notifications, "show").mockReturnValue("id");
    let listCount = 0;
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      const method = init?.method ?? "GET";
      if (method === "DELETE" && /^\/api\/v1\/catalog-files\/\d+$/.test(url)) {
        return Promise.resolve(new Response(null, { status: 204 }));
      }
      if (url.startsWith("/api/v1/catalog-files?")) {
        listCount++;
        return Promise.resolve(filesPage(listCount === 1 ? SEED_FILES : [SEED_FILES[0]]));
      }
      return Promise.resolve(jsonResponse(404, {}));
    });
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole("button", { name: "Delete web-portal" }));
    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: /^delete$/i }));

    await waitFor(() => expect(screen.queryByText("web-portal")).not.toBeInTheDocument());
    const deleteCall = mockFetch.mock.calls.find(
      ([url, init]) =>
        (init as RequestInit | undefined)?.method === "DELETE" && url === "/api/v1/catalog-files/2",
    );
    expect(deleteCall).toBeDefined();
    expect(listCount).toBeGreaterThanOrEqual(2);
    expect(toast).toHaveBeenCalledWith(
      expect.objectContaining({ message: "Catalog file deleted", color: "teal" }),
    );
    toast.mockRestore();
  });

  test("cancelling the delete modal issues no DELETE", async () => {
    setupMocks(mockFetch, () => filesPage(SEED_FILES));
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole("button", { name: "Delete web-portal" }));
    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: /cancel/i }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    const deleteCall = mockFetch.mock.calls.find(
      ([, init]) => (init as RequestInit | undefined)?.method === "DELETE",
    );
    expect(deleteCall).toBeUndefined();
  });

  test("a failed download shows the dismissible download alert", async () => {
    mockFetch.mockImplementation((url: string) => {
      if (url === "/api/v1/catalog-files/1") return Promise.resolve(jsonResponse(500, {}));
      if (url.startsWith("/api/v1/catalog-files?")) return Promise.resolve(filesPage(SEED_FILES));
      return Promise.resolve(jsonResponse(404, {}));
    });
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole("button", { name: "Download payments-svc" }));
    expect(await screen.findByText("Failed to download catalog file")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /close/i }));
    await waitFor(() =>
      expect(screen.queryByText("Failed to download catalog file")).not.toBeInTheDocument(),
    );
  });

  test("the download action fetches the document and hands over a YAML file", async () => {
    const createObjectURL = vi.fn().mockReturnValue("blob:fake");
    const revokeObjectURL = vi.fn();
    vi.stubGlobal("URL", Object.assign(URL, { createObjectURL, revokeObjectURL }));
    const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
    mockFetch.mockImplementation((url: string) => {
      if (url === "/api/v1/catalog-files/1") {
        return Promise.resolve(
          jsonResponse(200, {
            id: 1,
            kind: "Component",
            metadata: { name: "payments-svc", namespace: "default" },
            spec: { type: "service", lifecycle: "production", owner: "group:platform" },
            createdBy: 1,
            creatorName: "Alice Creator",
            creatorDeleted: false,
            createdAt: 1,
            updatedAt: 2,
          }),
        );
      }
      if (url.startsWith("/api/v1/catalog-files?")) return Promise.resolve(filesPage(SEED_FILES));
      return Promise.resolve(jsonResponse(404, {}));
    });
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole("button", { name: "Download payments-svc" }));

    await waitFor(() => expect(click).toHaveBeenCalledOnce());
    expect(createObjectURL).toHaveBeenCalledOnce();
    click.mockRestore();
  });

  test("links to the import page", async () => {
    setupMocks(mockFetch, () => filesPage(SEED_FILES));
    renderPage();
    const link = await screen.findByRole("link", { name: "Import YAML" });
    expect(link).toHaveAttribute("href", "/catalog-files/import");
  });

  test("export fetches the workspace and downloads one multi-document YAML", async () => {
    const createObjectURL = vi.fn().mockReturnValue("blob:fake");
    const revokeObjectURL = vi.fn();
    vi.stubGlobal("URL", Object.assign(URL, { createObjectURL, revokeObjectURL }));
    const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
    mockFetch.mockImplementation((url: string) => {
      if (url === "/api/v1/catalog-files/export") {
        return Promise.resolve(
          jsonResponse(200, {
            files: [
              {
                kind: "Component",
                metadata: { name: "payments-svc", namespace: "default" },
                spec: { type: "service", lifecycle: "production", owner: "platform" },
              },
              {
                kind: "Group",
                metadata: { name: "team-a", namespace: "default" },
                spec: { type: "team", children: [] },
              },
            ],
          }),
        );
      }
      if (url.startsWith("/api/v1/catalog-files?")) return Promise.resolve(filesPage(SEED_FILES));
      return Promise.resolve(jsonResponse(404, {}));
    });
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole("button", { name: "Export YAML" }));

    await waitFor(() => expect(click).toHaveBeenCalledOnce());
    const blob = createObjectURL.mock.calls[0][0] as Blob;
    const text = await blob.text();
    expect(text).toContain("name: payments-svc");
    expect(text).toContain("\n---\n");
    expect(text).toContain("name: team-a");
    click.mockRestore();
  });

  test("export passes the exact-namespace filter through and errors show the alert", async () => {
    mockFetch.mockImplementation((url: string) => {
      const registry = registryResponse(url);
      if (registry) return Promise.resolve(registry);
      if (url.startsWith("/api/v1/catalog-files/export")) {
        return Promise.resolve(jsonResponse(500, {}));
      }
      if (url.startsWith("/api/v1/catalog-files?")) return Promise.resolve(filesPage(SEED_FILES));
      return Promise.resolve(jsonResponse(404, {}));
    });
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole("button", { name: /filters/i }));
    await pickFilterOption("Namespace", "team-a");
    await calledWith(mockFetch, "namespace=team-a");

    await user.click(screen.getByRole("button", { name: "Export YAML" }));

    expect(await screen.findByText("Failed to export catalog files")).toBeInTheDocument();
    const exportCall = mockFetch.mock.calls.find(
      ([url]) => typeof url === "string" && url.startsWith("/api/v1/catalog-files/export"),
    );
    expect(exportCall?.[0]).toBe("/api/v1/catalog-files/export?namespace=team-a");
  });

  test("the export button is disabled while the list is empty", async () => {
    setupMocks(mockFetch, () => filesPage([]));
    renderPage();
    await screen.findByText("No catalog files");
    expect(screen.getByRole("button", { name: "Export YAML" })).toBeDisabled();
  });
});
