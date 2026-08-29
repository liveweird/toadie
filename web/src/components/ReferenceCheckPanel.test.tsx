import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import ReferenceCheckPanel from "./ReferenceCheckPanel";
import type { CatalogFileRequest } from "../api/catalogFiles";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

type FetchMock = ReturnType<typeof vi.fn>;

const DOCUMENT: CatalogFileRequest = {
  kind: "Component",
  metadata: { name: "web-app", namespace: "default" },
  spec: { type: "service", lifecycle: "production", owner: "group:default/team-a" },
};

describe("ReferenceCheckPanel", () => {
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

  test("POSTs the document to the check endpoint and renders an all-clear", async () => {
    mockFetch.mockResolvedValue(jsonResponse(200, { findings: [] }));
    renderWithProviders(<ReferenceCheckPanel document={DOCUMENT} />);

    expect(screen.getByText("Findings")).toBeInTheDocument();

    expect(await screen.findByText("No findings — the document passes every check.", undefined, { timeout: 3000 }))
      .toBeInTheDocument();
    const [url, init] = mockFetch.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/v1/catalog-files/check");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body as string)).toEqual(DOCUMENT);
  });

  test("a document edit re-checks only after the 500 ms debounce", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(200, { findings: [] })));
    const { rerender } = renderWithProviders(<ReferenceCheckPanel document={DOCUMENT} />);
    await waitFor(() => expect(mockFetch).toHaveBeenCalledTimes(1), { timeout: 3000 });

    const edited = { ...DOCUMENT, metadata: { name: "web-app-renamed", namespace: "default" } };
    rerender(<ReferenceCheckPanel document={edited} />);

    // Well inside the debounce window nothing new is sent.
    await new Promise((resolve) => setTimeout(resolve, 150));
    expect(mockFetch).toHaveBeenCalledTimes(1);

    await waitFor(() => expect(mockFetch).toHaveBeenCalledTimes(2), { timeout: 3000 });
    const [, init] = mockFetch.mock.calls[1] as [string, RequestInit];
    expect(JSON.parse(init.body as string)).toEqual(edited);
  });

  test("renders the findings with their status messages", async () => {
    mockFetch.mockResolvedValue(
      jsonResponse(200, {
        findings: [
          { field: "spec.owner", reference: "group:default/team-a", status: "MISSING" },
          { field: "spec.dependsOn", reference: "template:default/starter", status: "WRONG_KIND" },
          { field: "spec.subcomponentOf", reference: "component:default/web-app", status: "SELF_REFERENCE" },
        ],
      }),
    );
    renderWithProviders(<ReferenceCheckPanel document={DOCUMENT} />);

    expect(await screen.findByText("Findings — saving will ask for confirmation", undefined, { timeout: 3000 }))
      .toBeInTheDocument();
    expect(screen.getByText("group:default/team-a")).toBeInTheDocument();
    expect(
      screen.getByText(/No stored entity matches this reference/),
    ).toBeInTheDocument();
    expect(
      screen.getByText(/names a kind this field does not allow/),
    ).toBeInTheDocument();
    expect(
      screen.getByText(/an entity cannot reference itself/),
    ).toBeInTheDocument();
    expect(screen.queryByText("No findings — the document passes every check.")).not.toBeInTheDocument();
  });

  test("a failed check stays silent — the panel is advisory", async () => {
    mockFetch.mockResolvedValue(jsonResponse(500, { title: "boom", status: 500 }));
    renderWithProviders(<ReferenceCheckPanel document={DOCUMENT} />);

    await waitFor(() => expect(mockFetch).toHaveBeenCalled(), { timeout: 3000 });

    expect(screen.getByText("Findings")).toBeInTheDocument();
    expect(screen.queryByText("Findings — saving will ask for confirmation")).not.toBeInTheDocument();
    expect(screen.queryByText("No findings — the document passes every check.")).not.toBeInTheDocument();
  });
});
