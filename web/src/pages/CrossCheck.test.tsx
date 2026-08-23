import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { fireEvent, screen } from "@testing-library/react";
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
      reference: "team-x",
      status: "UNVERIFIABLE",
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
    expect(screen.getByText("2 problems")).toBeInTheDocument();
    expect(screen.getByText("1 not checkable yet")).toBeInTheDocument();
  });

  test("defaults to the problems view: errors visible, unverifiable hidden", async () => {
    mockReport(mockFetch);
    renderPage();

    expect(await screen.findByText("component:ghost")).toBeInTheDocument();
    expect(screen.getByText("orders-db")).toBeInTheDocument();
    expect(screen.getByText("Kind required")).toBeInTheDocument();
    expect(screen.queryByText("team-x")).not.toBeInTheDocument();
  });

  test("the filter switches to all findings", async () => {
    mockReport(mockFetch);
    renderPage();

    await screen.findByText("component:ghost");
    fireEvent.click(screen.getByLabelText("Show", { selector: "input" }));
    fireEvent.click(await screen.findByRole("option", { name: "All findings" }));

    expect(await screen.findByText("team-x")).toBeInTheDocument();
    expect(screen.getByText("Not checkable yet", { selector: ".mantine-Badge-label" })).toBeInTheDocument();
    expect(screen.getByText("component:ghost")).toBeInTheDocument();
  });

  test("file names link to the file's editor", async () => {
    mockReport(mockFetch);
    renderPage();

    const link = await screen.findByRole("link", { name: "Edit svc-a" });
    expect(link).toHaveAttribute("href", "/catalog-files/1/edit");
  });

  test("an all-clear workspace shows the happy empty state", async () => {
    mockReport(mockFetch, { findings: [], checkedFiles: 3, checkedReferences: 7 });
    renderPage();

    expect(await screen.findByText(/no problems found/i)).toBeInTheDocument();
    expect(screen.getByText("0 problems")).toBeInTheDocument();
  });

  test("an empty non-default filter shows the neutral empty state", async () => {
    const onlyErrors = { ...REPORT, findings: REPORT.findings.filter((f) => f.status !== "UNVERIFIABLE") };
    mockReport(mockFetch, onlyErrors);
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("component:ghost");
    fireEvent.click(screen.getByLabelText("Show", { selector: "input" }));
    await user.click(await screen.findByRole("option", { name: "Not checkable yet" }));

    expect(await screen.findByText(/nothing to show for this filter/i)).toBeInTheDocument();
  });

  test("shows an alert when the report fails to load", async () => {
    mockReport(mockFetch, { title: "boom", status: 500 }, 500);
    renderPage();

    expect(await screen.findByText("Failed to load the cross-check report")).toBeInTheDocument();
  });
});
