import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { jsonResponse } from "../test/http";
import { ApiError } from "./http";
import {
  checkCatalogFile,
  createCatalogFile,
  deleteCatalogFile,
  fetchCatalogUrl,
  getCatalogFile,
  getCatalogErrors,
  getCatalogGraph,
  importCatalogFiles,
  listAllCatalogFiles,
  listCatalogFiles,
  softRejectionFindings,
  updateCatalogFile,
  type CatalogFileRequest,
} from "./catalogFiles";

type FetchMock = ReturnType<typeof vi.fn>;

const REQUEST: CatalogFileRequest = {
  kind: "Component",
  metadata: { name: "web-app", namespace: "default" },
  spec: { type: "service", lifecycle: "production", owner: "group:default/team-a" },
};

describe("catalogFiles API wrappers", () => {
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

  const lastCall = () => mockFetch.mock.calls.at(-1) as [string, RequestInit];

  test("listCatalogFiles assembles the full query string", async () => {
    mockFetch.mockResolvedValue(jsonResponse(200, { items: [], page: 2, pageSize: 40, total: 0 }));
    await listCatalogFiles({
      page: 2,
      pageSize: 40,
      sort: "-name",
      name: "web",
      namespace: "team-a",
      kind: ["Component", "API"],
    });
    expect(lastCall()[0]).toBe(
      "/api/v1/files?page=2&pageSize=40&sort=-name&name=web&namespace=team-a&kind=Component&kind=API",
    );
  });

  test("listCatalogFiles omits absent filters", async () => {
    mockFetch.mockResolvedValue(jsonResponse(200, { items: [], page: 1, pageSize: 20, total: 0 }));
    await listCatalogFiles({ page: 1, pageSize: 20 });
    expect(lastCall()[0]).toBe("/api/v1/files?page=1&pageSize=20");
  });

  test("listAllCatalogFiles stops on an empty page even when the total claims more", async () => {
    mockFetch.mockResolvedValue(jsonResponse(200, { items: [], page: 1, pageSize: 100, total: 5 }));
    await expect(listAllCatalogFiles()).resolves.toEqual([]);
    expect(mockFetch).toHaveBeenCalledTimes(1);
  });

  test("getCatalogFile GETs the id path", async () => {
    mockFetch.mockResolvedValue(jsonResponse(200, { id: 5 }));
    await getCatalogFile(5);
    const [url, init] = lastCall();
    expect(url).toBe("/api/v1/files/5");
    expect(init.method).toBeUndefined(); // GET
  });

  test("createCatalogFile POSTs the document", async () => {
    mockFetch.mockResolvedValue(jsonResponse(200, { id: 9 }));
    await createCatalogFile(REQUEST);
    const [url, init] = lastCall();
    expect(url).toBe("/api/v1/files");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body as string)).toEqual(REQUEST);
  });

  test("updateCatalogFile PUTs the document to the id path", async () => {
    mockFetch.mockResolvedValue(new Response(null, { status: 204 }));
    await updateCatalogFile(5, REQUEST);
    const [url, init] = lastCall();
    expect(url).toBe("/api/v1/files/5");
    expect(init.method).toBe("PUT");
    expect(JSON.parse(init.body as string)).toEqual(REQUEST);
  });

  test("deleteCatalogFile DELETEs the id path", async () => {
    mockFetch.mockResolvedValue(new Response(null, { status: 204 }));
    await deleteCatalogFile(5);
    const [url, init] = lastCall();
    expect(url).toBe("/api/v1/files/5");
    expect(init.method).toBe("DELETE");
  });

  test("getCatalogErrors appends only the populated filters", async () => {
    mockFetch.mockImplementation(() =>
      Promise.resolve(jsonResponse(200, { findings: [], checkedFiles: 0, checkedReferences: 0 })),
    );
    await getCatalogErrors();
    expect(lastCall()[0]).toBe("/api/v1/files/errors");
    await getCatalogErrors({ namespace: "team-a", type: "service" });
    expect(lastCall()[0]).toBe("/api/v1/files/errors?namespace=team-a&type=service");
  });

  test("getCatalogGraph appends only the populated filters", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(200, { nodes: [], edges: [] })));
    await getCatalogGraph();
    expect(lastCall()[0]).toBe("/api/v1/files/graph");
    await getCatalogGraph({ namespace: "team-a", type: "service" });
    expect(lastCall()[0]).toBe("/api/v1/files/graph?namespace=team-a&type=service");
  });

  test("the label filter pair travels as repeated labelValue keys on list and graph", async () => {
    mockFetch.mockImplementation(() =>
      Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 20, total: 0 })),
    );
    await listCatalogFiles({
      page: 1,
      pageSize: 20,
      label: "example.com/tier",
      labelValue: ["backend", "edge"],
    });
    expect(lastCall()[0]).toBe(
      "/api/v1/files?page=1&pageSize=20&label=example.com%2Ftier&labelValue=backend&labelValue=edge",
    );
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(200, { nodes: [], edges: [] })));
    await getCatalogGraph({ label: "example.com/tier", labelValue: ["backend"] });
    expect(lastCall()[0]).toBe("/api/v1/files/graph?label=example.com%2Ftier&labelValue=backend");
  });

  test("checkCatalogFile POSTs the document to the check endpoint", async () => {
    mockFetch.mockResolvedValue(jsonResponse(200, { findings: [] }));
    await checkCatalogFile(REQUEST);
    const [url, init] = lastCall();
    expect(url).toBe("/api/v1/files/check");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body as string)).toEqual(REQUEST);
  });

  test("importCatalogFiles POSTs the files array wrapped in the request envelope", async () => {
    mockFetch.mockResolvedValue(jsonResponse(200, { results: [] }));
    await importCatalogFiles([REQUEST]);
    const [url, init] = lastCall();
    expect(url).toBe("/api/v1/files/import");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body as string)).toEqual({ files: [REQUEST] });
  });

  test("fetchCatalogUrl POSTs the url", async () => {
    mockFetch.mockResolvedValue(jsonResponse(200, { content: "kind: Component" }));
    await fetchCatalogUrl("https://example.com/catalog-info.yaml");
    const [url, init] = lastCall();
    expect(url).toBe("/api/v1/files/fetch");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body as string)).toEqual({
      url: "https://example.com/catalog-info.yaml",
    });
  });
});

describe("softRejectionFindings", () => {
  test("reads the findings array off a 400 problem body — no /check round trip", () => {
    const fetchSpy = vi.fn();
    vi.stubGlobal("fetch", fetchSpy);
    const err = new ApiError(400, {
      title: "Bad Request",
      status: 400,
      findings: [{ field: "spec.owner", reference: "group:default/x", status: "MISSING" }],
    });
    expect(softRejectionFindings(err)).toEqual([
      { field: "spec.owner", reference: "group:default/x", status: "MISSING" },
    ]);
    expect(fetchSpy).not.toHaveBeenCalled();
    vi.unstubAllGlobals();
  });

  test("a 400 with no findings member answers null (a structural rejection)", () => {
    expect(softRejectionFindings(new ApiError(400, { title: "Bad Request", status: 400 }))).toBeNull();
  });

  test("a 400 with an empty findings array answers null", () => {
    expect(softRejectionFindings(new ApiError(400, { findings: [] }))).toBeNull();
  });

  test("a 400 with a malformed findings member answers null defensively", () => {
    expect(softRejectionFindings(new ApiError(400, { findings: "not-an-array" }))).toBeNull();
  });

  test("a non-400 ApiError answers null", () => {
    expect(softRejectionFindings(new ApiError(409, { findings: [{ status: "MISSING" }] }))).toBeNull();
  });

  test("a non-ApiError answers null", () => {
    expect(softRejectionFindings(new Error("network"))).toBeNull();
  });
});
