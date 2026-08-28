import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { screen } from "@testing-library/react";
import CrossCheck from "./CrossCheck";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const TOKEN_KEY = "toadie.auth.token";

type FetchMock = ReturnType<typeof vi.fn>;

const REPORT = {
  findings: [
    {
      fileId: 1,
      fileName: "svc-a",
      fileNamespace: "default",
      field: "spec.dependsOn",
      reference: "component:ghost",
      status: "MISSING",
    },
    {
      fileId: 2,
      fileName: "svc-b",
      fileNamespace: "team-a",
      field: "spec.dependsOn",
      reference: "orders-db",
      status: "KIND_REQUIRED",
    },
    {
      fileId: 1,
      fileName: "svc-a",
      fileNamespace: "default",
      field: "spec.owner",
      reference: "component:team-x",
      status: "WRONG_KIND",
    },
  ],
  checkedFiles: 2,
  checkedReferences: 5,
};

function mockReport(mockFetch: FetchMock, body: unknown = REPORT, status = 200) {
  mockFetch.mockImplementation((url: string) =>
    url === "/api/v1/catalog-files/cross-check"
      ? Promise.resolve(jsonResponse(status, body))
      : Promise.resolve(jsonResponse(404, {})),
  );
}

function renderPage() {
  return renderWithProviders(<CrossCheck />, { route: "/cross-check" });
}

describe("CrossCheck page", () => {
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

  test("renders the summary counters", async () => {
    mockReport(mockFetch);
    renderPage();

    expect(await screen.findByText("2 files checked")).toBeInTheDocument();
    expect(screen.getByText("5 references checked")).toBeInTheDocument();
    expect(screen.getByText("3 problems")).toBeInTheDocument();
  });

  test("every finding renders as an error row — saves enforce resolution, so all block", async () => {
    mockReport(mockFetch);
    renderPage();

    expect(await screen.findByText("component:ghost")).toBeInTheDocument();
    expect(screen.getByText("orders-db")).toBeInTheDocument();
    expect(screen.getByText("Kind required")).toBeInTheDocument();
    expect(screen.getByText("component:team-x")).toBeInTheDocument();
    expect(screen.getByText("Wrong kind")).toBeInTheDocument();
  });

  test("file names link to the file's editor", async () => {
    mockReport(mockFetch);
    renderPage();

    // svc-a carries two findings — two rows, one link each, same target.
    const links = await screen.findAllByRole("link", { name: "Edit svc-a" });
    expect(links).toHaveLength(2);
    expect(links[0]).toHaveAttribute("href", "/catalog-files/1/edit");
  });

  test("an all-clear workspace shows the happy empty state", async () => {
    mockReport(mockFetch, { findings: [], checkedFiles: 3, checkedReferences: 7 });
    renderPage();

    expect(await screen.findByText(/no problems found/i)).toBeInTheDocument();
    expect(screen.getByText("0 problems")).toBeInTheDocument();
  });

  test("shows an alert when the report fails to load", async () => {
    mockReport(mockFetch, { title: "boom", status: 500 }, 500);
    renderPage();

    expect(await screen.findByText("Failed to load the cross-check report")).toBeInTheDocument();
  });
});
