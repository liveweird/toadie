import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import { renderWithProviders, screen, waitFor, within } from "../test/render";
import CatalogFileDrawer from "./CatalogFileDrawer";

type FetchMock = ReturnType<typeof vi.fn>;

const FILE = {
  id: 7,
  kind: "Component",
  metadata: {
    name: "payments-svc",
    namespace: "default",
    title: "Payments",
    description: "Takes the money.",
    tags: ["backend"],
    labels: { tier: "gold" },
    annotations: { "example.com/a": "1", "example.com/b": "2" },
  },
  spec: { type: "service", lifecycle: "production", owner: "group:default/payments" },
  createdBy: 1,
  creatorName: "Alice Admin",
  creatorDeleted: false,
  createdAt: 1_700_000_000_000,
  updatedAt: 1_700_000_100_000,
  sourceUrl: null as string | null,
  lastSyncedAt: 0,
};

function jsonResponse(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

function mockDetail(mockFetch: FetchMock, file: unknown = FILE, status = 200) {
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    if (url === "/api/v1/files/check" && init?.method === "POST")
      return Promise.resolve(jsonResponse(200, { findings: [{ field: "spec.owner", reference: "group:default/payments", status: "MISSING", message: null }] }));
    if (url === "/api/v1/files/7") return Promise.resolve(jsonResponse(status, file));
    return Promise.resolve(jsonResponse(200, { items: [] }));
  });
}

function Probe() {
  const { pathname, search } = useLocation();
  return <p data-testid="probe">{pathname + search}</p>;
}

function renderDrawer(route = "/files?file=7") {
  return renderWithProviders(
    <>
      <CatalogFileDrawer />
      <Routes>
        <Route path="*" element={<Probe />} />
      </Routes>
    </>,
    { route },
  );
}

describe("CatalogFileDrawer", () => {
  let mockFetch: FetchMock;
  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("toadie.auth.token", "fake-token");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("stays closed without ?file", () => {
    mockDetail(mockFetch);
    renderDrawer("/files");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(mockFetch).not.toHaveBeenCalledWith("/api/v1/files/7", expect.anything());
  });

  test("shows the file's summary, live findings, and YAML; Sync is disabled without a source", async () => {
    mockDetail(mockFetch);
    renderDrawer();
    const dialog = await screen.findByRole("dialog");
    expect(await within(dialog).findByText("payments-svc")).toBeInTheDocument();
    expect(within(dialog).getByText("Component")).toBeInTheDocument();
    expect(within(dialog).getByText("Payments")).toBeInTheDocument();
    expect(within(dialog).getByText("Takes the money.")).toBeInTheDocument();
    expect(within(dialog).getByText("group:default/payments", { selector: "code" })).toBeInTheDocument();
    expect(within(dialog).getByText("backend")).toBeInTheDocument();
    expect(within(dialog).getByText("tier=gold")).toBeInTheDocument();
    expect(within(dialog).getByText("Alice Admin")).toBeInTheDocument();
    expect(within(dialog).getByLabelText("YAML preview")).toHaveTextContent("kind: Component");
    await waitFor(() => expect(within(dialog).getByText(/No stored entity matches/)).toBeInTheDocument());
    expect(within(dialog).getByRole("button", { name: "Sync from source" })).toBeDisabled();
    expect(within(dialog).getByRole("link", { name: "Edit" })).toHaveAttribute("href", "/files/7/edit");
  });

  test("a sourced file enables Sync and links the source", async () => {
    mockDetail(mockFetch, { ...FILE, sourceUrl: "https://example.com/catalog-info.yaml", lastSyncedAt: FILE.updatedAt });
    renderDrawer();
    const dialog = await screen.findByRole("dialog");
    expect(await within(dialog).findByRole("button", { name: "Sync from source" })).toBeEnabled();
    expect(within(dialog).getByRole("link", { name: "https://example.com/catalog-info.yaml" })).toHaveAttribute("target", "_blank");
  });

  test("closing removes ?file from the address", async () => {
    mockDetail(mockFetch);
    const user = userEvent.setup();
    renderDrawer();
    await screen.findByText("payments-svc");
    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.getByTestId("probe")).toHaveTextContent("/files"));
    expect(screen.getByTestId("probe")).not.toHaveTextContent("file=");
  });

  test("a missing file reads as not found, with no actions", async () => {
    mockDetail(mockFetch, { title: "nf", status: 404 }, 404);
    renderDrawer();
    expect(await screen.findByText("Catalog file not found.")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Edit" })).not.toBeInTheDocument();
  });
});
