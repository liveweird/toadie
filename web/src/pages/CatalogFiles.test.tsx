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
  sourceUrl: string | null;
  lastSyncedAt: number;
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
    // Synced from a repo, then edited locally (updatedAt > lastSyncedAt).
    sourceUrl: "https://raw.githubusercontent.com/acme/payments/main/catalog-info.yaml",
    lastSyncedAt: 1755800000000,
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
    sourceUrl: null,
    lastSyncedAt: 0,
  },
];

function filesPage(items: FileRow[], total = items.length) {
  return jsonResponse(200, { items, page: 1, pageSize: 20, total });
}

// The filter combos load their options from the admin-curated registries/dictionaries.
const NAMESPACE_ENTRIES = {
  items: [
    { id: 1, value: "default", isDefault: true },
    { id: 2, value: "team-a", isDefault: false },
  ],
};
const TAG_CATEGORIES = {
  items: [{ id: 1, name: "Tech", tags: ["java", "billing"], kinds: ["Component"] }],
};
const LIFECYCLE_ENTRIES = {
  items: [
    { id: 1, value: "production", isDefault: false },
    { id: 2, value: "experimental", isDefault: false },
  ],
};
const ENTITY_TYPES = {
  items: [
    { id: 1, kind: "Component", types: ["service", "library"] },
    { id: 2, kind: "API", types: ["openapi"] },
  ],
};
const LABELS = {
  items: [{ id: 1, key: "example.com/tier", values: ["backend", "edge"], kinds: ["Component"] }],
};

function registryResponse(url: string): Response | null {
  if (url.startsWith("/api/v1/dictionaries/namespaces"))
    return jsonResponse(200, NAMESPACE_ENTRIES);
  if (url.startsWith("/api/v1/dictionaries/lifecycles"))
    return jsonResponse(200, LIFECYCLE_ENTRIES);
  if (url.startsWith("/api/v1/entity-types")) return jsonResponse(200, ENTITY_TYPES);
  if (url.startsWith("/api/v1/labels")) return jsonResponse(200, LABELS);
  if (url.startsWith("/api/v1/tag-categories")) return jsonResponse(200, TAG_CATEGORIES);
  return null;
}

function setupMocks(mockFetch: FetchMock, byUrl: (url: string) => Response) {
  mockFetch.mockImplementation((url: string) => {
    const registry = registryResponse(url);
    if (registry) return Promise.resolve(registry);
    return url.startsWith("/api/v1/files")
      ? Promise.resolve(byUrl(url))
      : Promise.resolve(jsonResponse(404, {}));
  });
}

async function pickFilterOption(label: string, option: string) {
  fireEvent.click(screen.getByLabelText(label, { selector: "input" }));
  fireEvent.click(await screen.findByRole("option", { name: option }));
}

// Row actions live in the per-row Operations dropdown — open it, then click the item.
async function rowOperation(
  user: ReturnType<typeof userEvent.setup>,
  name: string,
  operation: string,
) {
  await user.click(await screen.findByRole("button", { name: `Operations for ${name}` }));
  await user.click(await screen.findByRole("menuitem", { name: operation }));
}

function renderPage() {
  return renderWithProviders(<CatalogFiles />, { route: "/files" });
}

async function calledWith(mockFetch: FetchMock, fragment: string) {
  await waitFor(() => {
    const called = mockFetch.mock.calls.some(
      ([url]) =>
        typeof url === "string" && url.startsWith("/api/v1/files?") && url.includes(fragment),
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

  test("renders rows with title; tags/type/owner/creator are gone from the list", async () => {
    setupMocks(mockFetch, () => filesPage(SEED_FILES));
    renderPage();

    expect(await screen.findByText("payments-svc")).toBeInTheDocument();
    expect(screen.getByText("Payments")).toBeInTheDocument();
    // The tags, type/lifecycle, owner, and created-by columns were deliberately removed
    // (tags remain a FILTER — the rows just don't spend width on the badges any more).
    expect(screen.queryByText("java")).not.toBeInTheDocument();
    expect(screen.queryByText("billing")).not.toBeInTheDocument();
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

  test("the always-visible Kind pills are a visible set driving repeated kind=", async () => {
    setupMocks(mockFetch, () => filesPage(SEED_FILES));
    renderPage();

    await screen.findByText("payments-svc");
    // No panel-opening needed — the pills live above the table; all-on sends NO kind param.
    expect(
      mockFetch.mock.calls.some(
        ([url]) => typeof url === "string" && url.startsWith("/api/v1/files?") && url.includes("kind="),
      ),
    ).toBe(false);

    // Hiding Group sends the six still-visible kinds — Group itself never travels.
    fireEvent.click(screen.getByRole("checkbox", { name: "Group" }));
    await calledWith(mockFetch, "kind=Component&kind=API&kind=System&kind=Domain&kind=Resource&kind=User");
    expect(
      mockFetch.mock.calls.some(
        ([url]) => typeof url === "string" && url.includes("kind=Group"),
      ),
    ).toBe(false);
  });

  test("with every Kind pill off the list is empty and nothing is fetched", async () => {
    setupMocks(mockFetch, () => filesPage(SEED_FILES));
    renderPage();

    await screen.findByText("payments-svc");
    const before = mockFetch.mock.calls.length;
    for (const kind of ["Component", "API", "System", "Domain", "Resource", "Group", "User"]) {
      fireEvent.click(screen.getByRole("checkbox", { name: kind }));
    }
    expect(await screen.findByText("No catalog files")).toBeInTheDocument();
    // The API cannot express match-nothing — the page never asks (six subset fetches at
    // most while toggling down, none for the empty set).
    const listCallsAfter = mockFetch.mock.calls
      .slice(before)
      .filter(([url]) => typeof url === "string" && url.startsWith("/api/v1/files?"));
    expect(listCallsAfter.every(([url]) => (url as string).includes("kind="))).toBe(true);
  });

  test("rows show their kind badge", async () => {
    setupMocks(mockFetch, () => filesPage(SEED_FILES));
    renderPage();

    await screen.findByText("payments-svc");
    // Scoped to the table — the always-visible Kind pills carry the same words.
    const table = screen.getByRole("table");
    expect(within(table).getByText("Component")).toBeInTheDocument();
    expect(within(table).getByText("API")).toBeInTheDocument();
    // Each badge carries the kind's tier dot (Component = tier 2, API = tier 3).
    const badgeDot = (kind: string) =>
      within(table).getByText(kind).closest(".mantine-Badge-root")?.querySelector("[data-tier]");
    expect(badgeDot("Component")).toHaveAttribute("data-tier", "2");
    expect(badgeDot("API")).toHaveAttribute("data-tier", "3");
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

  test("picking Type and Lifecycle filters refetches with type= and lifecycle=", async () => {
    setupMocks(mockFetch, () => filesPage(SEED_FILES));
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("payments-svc");
    await user.click(screen.getByRole("button", { name: /filters/i }));
    await pickFilterOption("Type", "library");
    await calledWith(mockFetch, "type=library");
    await pickFilterOption("Lifecycle", "experimental");
    await calledWith(mockFetch, "lifecycle=experimental");
  });

  test("picking an Owner refetches with the full entity reference", async () => {
    // The owner options come from the identity pool (the pageSize=100 loop), which offers
    // stored Groups/Users as full refs.
    const groupRow: FileRow = {
      id: 7,
      kind: "Group",
      name: "platform",
      namespace: "default",
      title: null,
      type: "team",
      lifecycle: "production",
      owner: "group:platform",
      tags: [],
      creatorName: "A",
      creatorDeleted: false,
      updatedAt: 1755900000000,
      sourceUrl: null,
      lastSyncedAt: 0,
    };
    setupMocks(mockFetch, (url) =>
      url.includes("pageSize=100") ? filesPage([groupRow]) : filesPage(SEED_FILES),
    );
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("payments-svc");
    await user.click(screen.getByRole("button", { name: /filters/i }));
    await pickFilterOption("Owner", "group:default/platform");
    await calledWith(mockFetch, "owner=group%3Adefault%2Fplatform");
  });

  test("the label pair refetches with label= and repeated labelValue=", async () => {
    setupMocks(mockFetch, () => filesPage(SEED_FILES));
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("payments-svc");
    await user.click(screen.getByRole("button", { name: /filters/i }));
    expect(screen.getByLabelText("Label values", { selector: "input" })).toBeDisabled();
    await pickFilterOption("Label", "example.com/tier");
    await calledWith(mockFetch, "label=example.com%2Ftier");
    await pickFilterOption("Label values", "backend");
    await pickFilterOption("Label values", "edge");
    await calledWith(mockFetch, "labelValue=backend&labelValue=edge");
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
          url.startsWith("/api/v1/files?") &&
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
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("payments-svc");
    expect(screen.getByRole("link", { name: /new catalog file/i })).toHaveAttribute(
      "href",
      "/files/new",
    );
    // The row's NAME is the direct way in — the Operations menu's Edit item is the same route.
    expect(screen.getByRole("link", { name: "Edit payments-svc" })).toHaveAttribute(
      "href",
      "/files/1/edit",
    );
    await user.click(screen.getByRole("button", { name: "Operations for payments-svc" }));
    expect(await screen.findByRole("menuitem", { name: "Edit" })).toHaveAttribute(
      "href",
      "/files/1/edit",
    );
  });

  test("columns are ordered Name, Kind, Namespace, Updated, Last sync — the title rides the Name cell", async () => {
    setupMocks(mockFetch, () => filesPage(SEED_FILES));
    renderPage();

    const name = await screen.findByText("payments-svc");
    const headers = screen.getAllByRole("columnheader").map((th) => th.textContent);
    expect(headers.slice(0, 5)).toEqual(["Name", "Kind", "Namespace", "Updated", "Last sync"]);
    // Updated is relative text (Intl "yesterday"/"3 days ago"/…) carrying the precise
    // timestamp as its hover text.
    const cells = within(name.closest("tr")!).getAllByRole("cell");
    const updated = cells[3].querySelector("[title]")!;
    expect(updated.textContent).not.toBe("");
    expect(updated.getAttribute("title")).toMatch(/\d/);
  });

  test("confirming a delete triggers DELETE, refetches, and toasts", async () => {
    const toast = vi.spyOn(notifications, "show").mockReturnValue("id");
    let listCount = 0;
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      const method = init?.method ?? "GET";
      if (method === "DELETE" && /^\/api\/v1\/files\/\d+$/.test(url)) {
        return Promise.resolve(new Response(null, { status: 204 }));
      }
      if (url.startsWith("/api/v1/files?")) {
        listCount++;
        return Promise.resolve(filesPage(listCount === 1 ? SEED_FILES : [SEED_FILES[0]]));
      }
      return Promise.resolve(jsonResponse(404, {}));
    });
    const user = userEvent.setup();
    renderPage();

    await rowOperation(user, "web-portal", "Delete");
    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: /^delete$/i }));

    await waitFor(() => expect(screen.queryByText("web-portal")).not.toBeInTheDocument());
    const deleteCall = mockFetch.mock.calls.find(
      ([url, init]) =>
        (init as RequestInit | undefined)?.method === "DELETE" && url === "/api/v1/files/2",
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

    await rowOperation(user, "web-portal", "Delete");
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
      if (url === "/api/v1/files/1") return Promise.resolve(jsonResponse(500, {}));
      if (url.startsWith("/api/v1/files?")) return Promise.resolve(filesPage(SEED_FILES));
      return Promise.resolve(jsonResponse(404, {}));
    });
    const user = userEvent.setup();
    renderPage();

    await rowOperation(user, "payments-svc", "Export as YAML");
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
      if (url === "/api/v1/files/1") {
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
      if (url.startsWith("/api/v1/files?")) return Promise.resolve(filesPage(SEED_FILES));
      return Promise.resolve(jsonResponse(404, {}));
    });
    const user = userEvent.setup();
    renderPage();

    await rowOperation(user, "payments-svc", "Export as YAML");

    await waitFor(() => expect(click).toHaveBeenCalledOnce());
    expect(createObjectURL).toHaveBeenCalledOnce();
    click.mockRestore();
  });

  test("links to the import page", async () => {
    setupMocks(mockFetch, () => filesPage(SEED_FILES));
    renderPage();
    const link = await screen.findByRole("link", { name: "Import new YAML" });
    expect(link).toHaveAttribute("href", "/files/import");
  });

  test("the Last sync column shows sync state and greys Sync from source out on source-less rows", async () => {
    setupMocks(mockFetch, () => filesPage(SEED_FILES));
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("payments-svc");
    // The sourced row: a relative time plus the local-changes marker (updatedAt moved past
    // the sync stamp); the source-less row reads "No source".
    expect(screen.getByText("Local changes")).toBeInTheDocument();
    expect(screen.getByText("No source")).toBeInTheDocument();

    // The item is always offered — greyed out until the row carries a source, rather than
    // vanishing, so its absence never reads as "this file cannot be synced at all".
    await user.click(screen.getByRole("button", { name: "Operations for payments-svc" }));
    expect(await screen.findByRole("menuitem", { name: "Sync from source" })).toBeEnabled();
    await user.keyboard("{Escape}");

    await user.click(screen.getByRole("button", { name: "Operations for web-portal" }));
    await screen.findByRole("menuitem", { name: "Edit" });
    expect(screen.getByRole("menuitem", { name: "Sync from source" })).toBeDisabled();
  });

  test("the Last sync header sorts by lastSyncedAt", async () => {
    setupMocks(mockFetch, () => filesPage(SEED_FILES));
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("payments-svc");
    await user.click(screen.getByRole("button", { name: /last sync/i }));
    await calledWith(mockFetch, "sort=lastSyncedAt");
  });

  test("Sync from repo opens the sync modal for the row", async () => {
    // The modal's own data flows are covered in SyncCatalogFileModal.test.tsx — here the
    // sub-requests get just enough shape for the modal to open cleanly.
    setupMocks(mockFetch, (url) => {
      if (url === "/api/v1/files/1/sync") {
        return jsonResponse(200, {
          sourceUrl: SEED_FILES[0].sourceUrl,
          lastSyncedAt: 0,
          syncedDocument: null,
        });
      }
      if (url === "/api/v1/files/1") {
        return jsonResponse(200, {
          id: 1,
          kind: "Component",
          metadata: { name: "payments-svc", namespace: "default" },
          spec: {},
          createdBy: 1,
          creatorName: "Alice Creator",
          creatorDeleted: false,
          createdAt: 1,
          updatedAt: SEED_FILES[0].updatedAt,
          sourceUrl: SEED_FILES[0].sourceUrl,
          lastSyncedAt: 0,
        });
      }
      if (url === "/api/v1/files/fetch") return jsonResponse(502, { status: 502 });
      return filesPage(SEED_FILES);
    });
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("payments-svc");
    await rowOperation(user, "payments-svc", "Sync from source");
    expect(await screen.findByText("Sync from source — payments-svc")).toBeInTheDocument();
  });
});
