import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import Errors from "./Errors";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const TOKEN_KEY = "toadie.auth.token";

type FetchMock = ReturnType<typeof vi.fn>;

const REPORT = {
  findings: [
    {
      fileId: 1,
      fileKind: "Component",
      fileName: "svc-a",
      fileNamespace: "default",
      field: "spec.dependsOn",
      reference: "component:ghost",
      status: "MISSING",
      message: null,
    },
    {
      fileId: 2,
      fileKind: "Component",
      fileName: "svc-b",
      fileNamespace: "team-a",
      field: "spec.dependsOn",
      reference: "orders-db",
      status: "KIND_REQUIRED",
      message: null,
    },
    {
      fileId: 1,
      fileKind: "Component",
      fileName: "svc-a",
      fileNamespace: "default",
      field: "spec.owner",
      reference: "component:team-x",
      status: "WRONG_KIND",
      message: null,
    },
    {
      fileId: 3,
      fileKind: "API",
      fileName: "orders-api",
      fileNamespace: "default",
      field: "document",
      reference: "",
      status: "STRUCTURE_INVALID",
      message: "spec.definition is required for kind API",
    },
  ],
  checkedFiles: 3,
  checkedReferences: 5,
};

function mockReport(mockFetch: FetchMock, body: unknown = REPORT, status = 200) {
  // The page also fires the filter panel's option-source fetches — 404 them, the Selects
  // simply stay empty; only the report URL (params optional) answers with the fixture.
  mockFetch.mockImplementation((url: string) =>
    url.startsWith("/api/v1/files/errors")
      ? Promise.resolve(jsonResponse(status, body))
      : Promise.resolve(jsonResponse(404, {})),
  );
}

function renderPage() {
  return renderWithProviders(<Errors />, { route: "/errors" });
}

/** The number shown on a summary tile, located by the tile's label. */
function tileValue(label: string): string | null {
  const tile = document.querySelector(`[data-tile="${label}"]`)!;
  return tile.firstElementChild?.textContent ?? null;
}

describe("Errors page", () => {
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

  test("renders the summary counters and the shared filter panel", async () => {
    mockReport(mockFetch);
    renderPage();

    expect(await screen.findByText("Files checked")).toBeInTheDocument();
    await waitFor(() => expect(tileValue("Files checked")).toBe("3"));
    expect(tileValue("References checked")).toBe("5");
    expect(tileValue("Errors")).toBe("4");
    // The class chips carry per-class counts from the unfiltered report, outside their names.
    expect(screen.getByRole("checkbox", { name: "References" })).toBeInTheDocument();
    // The Files list's collapsible filter panel and the kind pills ride along.
    expect(screen.getByRole("button", { name: /filters/i })).toBeInTheDocument();
    expect(screen.getByRole("checkbox", { name: "Component" })).toBeInTheDocument();
  });

  test("every finding renders as an error row with its kind", async () => {
    mockReport(mockFetch);
    renderPage();

    expect(await screen.findByText("component:ghost")).toBeInTheDocument();
    expect(screen.getByText("orders-db")).toBeInTheDocument();
    expect(screen.getByText("Kind required")).toBeInTheDocument();
    expect(screen.getByText("component:team-x")).toBeInTheDocument();
    expect(screen.getByText("Wrong kind")).toBeInTheDocument();
    // The Kind column (the kind PILLS also spell "API" — scope to the finding's row).
    const structuralRow = screen.getByText("orders-api").closest("tr")!;
    expect(within(structuralRow).getByText("API")).toBeInTheDocument();
  });

  test("file names link to the file's editor", async () => {
    mockReport(mockFetch);
    renderPage();

    // svc-a carries two findings — grouped into ONE row with one link.
    const links = await screen.findAllByRole("link", { name: "Edit svc-a" });
    expect(links).toHaveLength(1);
    expect(links[0]).toHaveAttribute("href", "/files/1/edit");
  });

  test("status badges follow the colour vocabulary: hard classes red, soft classes orange", async () => {
    mockReport(mockFetch);
    renderPage();

    // A soft (waivable) reference finding is orange; the report-only verdict on a rule that
    // is HARD on writes is red — the badge colour rides Mantine's CSS vars on the root.
    const soft = (await screen.findByText("Not found")).closest("[class*='Badge-root']")!;
    expect(soft.getAttribute("style")).toContain("orange");
    const hard = screen.getByText("Invalid structure").closest("[class*='Badge-root']")!;
    expect(hard.getAttribute("style")).toContain("red");
  });

  test("a STRUCTURE_INVALID row shows the validator's own message and no value", async () => {
    mockReport(mockFetch);
    renderPage();

    expect(await screen.findByText("spec.definition is required for kind API")).toBeInTheDocument();
    expect(screen.getByText("Invalid structure")).toBeInTheDocument();
    // Reference rows show the STATIC per-status explanation instead.
    // The static explanation rides the status badge as hover text, once per finding.
    expect(screen.getAllByText("Not found")[0].closest("[class*='Badge-root']")).toHaveAttribute(
      "title",
      "No stored entity matches this reference.",
    );
  });

  test("toggling an error-class pill filters the rows client-side", async () => {
    mockReport(mockFetch);
    renderPage();
    expect(await screen.findByText("component:ghost")).toBeInTheDocument();

    // References off: the three reference rows vanish, the structural row stays.
    fireEvent.click(screen.getByRole("checkbox", { name: "References" }));
    expect(screen.queryByText("component:ghost")).not.toBeInTheDocument();
    expect(screen.queryByText("orders-db")).not.toBeInTheDocument();
    expect(screen.getByText("Invalid structure")).toBeInTheDocument();
    expect(tileValue("Errors")).toBe("1");

    // Back on: the rows return (all-on is the default, persisted per view).
    fireEvent.click(screen.getByRole("checkbox", { name: "References" }));
    expect(screen.getByText("component:ghost")).toBeInTheDocument();
    expect(tileValue("Errors")).toBe("4");
  });

  test("an all-clear workspace shows the happy empty state", async () => {
    mockReport(mockFetch, { findings: [], checkedFiles: 3, checkedReferences: 7 });
    renderPage();

    const empty = await screen.findByText(/no errors/i);
    expect(tileValue("Errors")).toBe("0");
    // The empty-state cell spans every column — pins the page's columnCount literal.
    expect(empty.closest("td")).toHaveAttribute(
      "colspan",
      String(screen.getAllByRole("columnheader").length),
    );
  });

  test("shows an alert when the report fails to load", async () => {
    mockReport(mockFetch, { title: "boom", status: 500 }, 500);
    renderPage();

    expect(await screen.findByText("Failed to load the errors report")).toBeInTheDocument();
  });
});
