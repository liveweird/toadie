import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { Route, Routes, useLocation } from "react-router-dom";
import EntityErrors from "./EntityErrors";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";
import { blueprintResponse, entityErrorsReport } from "../test/fixtures";

const TOKEN_KEY = "toadie.auth.token";
const ROLES_KEY = "toadie.auth.roles";
const USER_ID_KEY = "toadie.auth.userId";

type FetchMock = ReturnType<typeof vi.fn>;

const BLUEPRINTS = [
  blueprintResponse({ id: 1, identifier: "service", title: "Service" }),
  blueprintResponse({ id: 2, identifier: "team", title: "Team" }),
];

const REPORT = entityErrorsReport({
  entities: [
    {
      id: 10,
      blueprintId: 1,
      blueprint: "service",
      blueprintTitle: "Service",
      identifier: "checkout",
      title: "Checkout",
      team: ["platform"],
      findings: [
        { code: "REQUIRED_MISSING", field: "properties.name", message: "name is required on checkout" },
      ],
    },
    {
      id: 11,
      blueprintId: 1,
      blueprint: "service",
      blueprintTitle: "Service",
      identifier: "billing",
      title: "Billing",
      team: [],
      findings: [
        { code: "OWNERSHIP_UNRESOLVED", field: "team", message: "the inherited path resolved to nothing" },
      ],
    },
  ],
  blueprints: [
    {
      id: 2,
      identifier: "team",
      title: "Team",
      findings: [
        {
          code: "CALCULATION_COMPILE_FAILED",
          field: "calculationProperties.cost",
          message: "jq: error: syntax error, unexpected ...",
        },
      ],
    },
  ],
  savedQueries: [
    {
      id: 5,
      name: "broken-query",
      visibility: "PRIVATE",
      createdBy: 1,
      query: "MATCH (n:nope) RETURN n",
      diagnostics: [
        {
          code: "UNKNOWN_LABEL",
          message: "Unknown blueprint 'nope'",
          line: 1,
          column: 8,
          endLine: 1,
          endColumn: 12,
          suggestion: "service",
        },
      ],
    },
  ],
  checkedEntities: 2,
  checkedBlueprints: 2,
  checkedSavedQueries: 1,
});

function mockReport(mockFetch: FetchMock, body: unknown = REPORT, status = 200) {
  mockFetch.mockImplementation((url: string) => {
    if (url.startsWith("/api/v1/blueprints")) return Promise.resolve(jsonResponse(200, { items: BLUEPRINTS }));
    if (url.startsWith("/api/v1/entities/errors")) return Promise.resolve(jsonResponse(status, body));
    return Promise.resolve(jsonResponse(404, {}));
  });
}

function PathProbe() {
  const location = useLocation();
  return <div data-testid="probe">{location.pathname}</div>;
}

function renderPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/ontology/errors" element={<EntityErrors />} />
      <Route path="/entity-graph" element={<PathProbe />} />
    </Routes>,
    { route: "/ontology/errors" },
  );
}

/** The number shown on a summary tile, located by the tile's label. */
function tileValue(label: string): string | null {
  const tile = document.querySelector(`[data-tile="${label}"]`)!;
  return tile.firstElementChild?.textContent ?? null;
}

describe("EntityErrors page", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem(TOKEN_KEY, "fake-token");
    localStorage.setItem(ROLES_KEY, "[]");
    localStorage.setItem(USER_ID_KEY, "1");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("renders the summary tiles and class chips from the unfiltered report", async () => {
    mockReport(mockFetch);
    renderPage();

    expect(await screen.findByText("Entities checked")).toBeInTheDocument();
    await waitFor(() => expect(tileValue("Entities checked")).toBe("2"));
    expect(tileValue("Blueprints checked")).toBe("2");
    expect(tileValue("Errors")).toBe("4");
    expect(screen.getByRole("checkbox", { name: "Stale" })).toBeInTheDocument();
    expect(screen.getByRole("checkbox", { name: "Ownership" })).toBeInTheDocument();
    expect(screen.getByRole("checkbox", { name: "Saved queries" })).toBeInTheDocument();
    expect(screen.getByRole("checkbox", { name: "Computed properties" })).toBeInTheDocument();
  });

  test("a stale entity finding links to its editor and shows the red label", async () => {
    mockReport(mockFetch);
    renderPage();

    const link = await screen.findByRole("link", { name: "Edit checkout" });
    expect(link).toHaveAttribute("href", "/entities/10/edit");
    const badge = screen.getByText("Required property missing").closest("[class*='Badge-root']")!;
    expect(badge.getAttribute("style")).toContain("red");
  });

  test("ownership, computed, and saved-query findings show orange labels", async () => {
    mockReport(mockFetch);
    renderPage();

    expect(
      (await screen.findByText("Ownership unresolved")).closest("[class*='Badge-root']")!.getAttribute("style"),
    ).toContain("orange");
    expect(
      screen.getByText("Calculation does not compile").closest("[class*='Badge-root']")!.getAttribute("style"),
    ).toContain("orange");
    expect(
      screen.getByText("Unknown blueprint").closest("[class*='Badge-root']")!.getAttribute("style"),
    ).toContain("orange");
  });

  test("a blueprint row links to its editor for an admin", async () => {
    mockReport(mockFetch);
    localStorage.setItem(ROLES_KEY, JSON.stringify(["ADMIN"]));
    renderPage();

    const link = await screen.findByRole("link", { name: "Edit team" });
    expect(link).toHaveAttribute("href", "/blueprints/2/edit");
  });

  test("a non-admin sees the blueprint identifier as plain text", async () => {
    mockReport(mockFetch);
    renderPage();

    const row = (await screen.findByText("Calculation does not compile")).closest("tr")!;
    expect(screen.queryByRole("link", { name: "Edit team" })).not.toBeInTheDocument();
    expect(within(row).getByText("team")).toBeInTheDocument();
  });

  test("Open in graph writes the shared entity-query state and navigates to the Entity graph", async () => {
    mockReport(mockFetch);
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole("button", { name: "Open in graph" }));

    expect(await screen.findByTestId("probe")).toHaveTextContent("/entity-graph");
    expect(localStorage.getItem("toadie.viewSettings.entityQuery.text")).toBe(
      JSON.stringify("MATCH (n:nope) RETURN n"),
    );
    expect(localStorage.getItem("toadie.viewSettings.entityQuery.applied")).toBe(
      JSON.stringify("MATCH (n:nope) RETURN n"),
    );
    expect(localStorage.getItem("toadie.viewSettings.entityQuery.picked")).toBe(JSON.stringify("5"));
  });

  test("toggling the Stale chip filters rows client-side", async () => {
    mockReport(mockFetch);
    renderPage();
    await screen.findByText("Required property missing");

    fireEvent.click(screen.getByRole("checkbox", { name: "Stale" }));
    expect(screen.queryByText("Required property missing")).not.toBeInTheDocument();
    expect(screen.getByText("Ownership unresolved")).toBeInTheDocument();
    expect(tileValue("Errors")).toBe("3");

    fireEvent.click(screen.getByRole("checkbox", { name: "Stale" }));
    expect(await screen.findByText("Required property missing")).toBeInTheDocument();
    expect(tileValue("Errors")).toBe("4");
  });

  test("hiding every blueprint pill shows the empty state and stops fetching the report", async () => {
    mockReport(mockFetch);
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole("checkbox", { name: "service" });

    await user.click(screen.getByRole("checkbox", { name: "service" }));
    await user.click(screen.getByRole("checkbox", { name: "team" }));

    expect(await screen.findByText(/no errors/i)).toBeInTheDocument();

    mockFetch.mockClear();
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(mockFetch).not.toHaveBeenCalled();
  });

  test("shows an alert when the report fails to load", async () => {
    mockReport(mockFetch, { title: "boom", status: 500 }, 500);
    renderPage();

    expect(await screen.findByText("Failed to load the ontology errors report")).toBeInTheDocument();
  });

  test("the loading spinner is named for assistive tech, not an aria-label on a bare span", async () => {
    let release!: () => void;
    const held = new Promise<void>((resolve) => {
      release = resolve;
    });
    mockFetch.mockImplementation(async (url: string) => {
      if (url.startsWith("/api/v1/blueprints")) return jsonResponse(200, { items: BLUEPRINTS });
      if (url.startsWith("/api/v1/entities/errors")) {
        await held;
        return jsonResponse(200, REPORT);
      }
      return jsonResponse(404, {});
    });
    renderPage();

    expect(await screen.findByRole("status")).toBeInTheDocument();
    release();
    await screen.findByText("Required property missing");
  });
});
