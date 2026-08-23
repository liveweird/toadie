import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { jsonResponse } from "../test/http";
import {
  checkCatalogFile,
  createCatalogFile,
  deleteCatalogFile,
  exportCatalogFiles,
  fetchCatalogUrl,
  getCatalogFile,
  getCatalogGraph,
  getCrossCheckReport,
  importCatalogFiles,
  listAllCatalogFiles,
  listCatalogFiles,
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
      kind: "Component",
    });
    expect(lastCall()[0]).toBe(
      "/api/v1/catalog-files?page=2&pageSize=40&sort=-name&name=web&namespace=team-a&kind=Component",
    );
  });

  test("listCatalogFiles omits absent filters", async () => {
    mockFetch.mockResolvedValue(jsonResponse(200, { items: [], page: 1, pageSize: 20, total: 0 }));
    await listCatalogFiles({ page: 1, pageSize: 20 });
    expect(lastCall()[0]).toBe("/api/v1/catalog-files?page=1&pageSize=20");
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
    expect(url).toBe("/api/v1/catalog-files/5");
    expect(init.method).toBeUndefined(); // GET
  });

  test("createCatalogFile POSTs the document", async () => {
    mockFetch.mockResolvedValue(jsonResponse(200, { id: 9 }));
    await createCatalogFile(REQUEST);
    const [url, init] = lastCall();
    expect(url).toBe("/api/v1/catalog-files");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body as string)).toEqual(REQUEST);
  });

  test("updateCatalogFile PUTs the document to the id path", async () => {
    mockFetch.mockResolvedValue(new Response(null, { status: 204 }));
    await updateCatalogFile(5, REQUEST);
    const [url, init] = lastCall();
    expect(url).toBe("/api/v1/catalog-files/5");
    expect(init.method).toBe("PUT");
    expect(JSON.parse(init.body as string)).toEqual(REQUEST);
  });

  test("deleteCatalogFile DELETEs the id path", async () => {
    mockFetch.mockResolvedValue(new Response(null, { status: 204 }));
    await deleteCatalogFile(5);
    const [url, init] = lastCall();
    expect(url).toBe("/api/v1/catalog-files/5");
    expect(init.method).toBe("DELETE");
  });

  test("getCrossCheckReport GETs the workspace report", async () => {
    mockFetch.mockResolvedValue(
      jsonResponse(200, { findings: [], checkedFiles: 0, checkedReferences: 0 }),
    );
    await getCrossCheckReport();
    expect(lastCall()[0]).toBe("/api/v1/catalog-files/cross-check");
  });

  test("getCatalogGraph appends the namespace only when given", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(200, { nodes: [], edges: [] })));
    await getCatalogGraph();
    expect(lastCall()[0]).toBe("/api/v1/catalog-files/graph");
    await getCatalogGraph("team-a");
    expect(lastCall()[0]).toBe("/api/v1/catalog-files/graph?namespace=team-a");
  });

  test("checkCatalogFile POSTs the document to the check endpoint", async () => {
    mockFetch.mockResolvedValue(jsonResponse(200, { findings: [] }));
    await checkCatalogFile(REQUEST);
    const [url, init] = lastCall();
    expect(url).toBe("/api/v1/catalog-files/check");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body as string)).toEqual(REQUEST);
  });

  test("exportCatalogFiles appends the namespace only when given", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(200, { files: [] })));
    await exportCatalogFiles();
    expect(lastCall()[0]).toBe("/api/v1/catalog-files/export");
    await exportCatalogFiles("team-a");
    expect(lastCall()[0]).toBe("/api/v1/catalog-files/export?namespace=team-a");
  });

  test("importCatalogFiles POSTs the files array wrapped in the request envelope", async () => {
    mockFetch.mockResolvedValue(jsonResponse(200, { results: [] }));
    await importCatalogFiles([REQUEST]);
    const [url, init] = lastCall();
    expect(url).toBe("/api/v1/catalog-files/import");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body as string)).toEqual({ files: [REQUEST] });
  });

  test("fetchCatalogUrl POSTs the url", async () => {
    mockFetch.mockResolvedValue(jsonResponse(200, { content: "kind: Component" }));
    await fetchCatalogUrl("https://example.com/catalog-info.yaml");
    const [url, init] = lastCall();
    expect(url).toBe("/api/v1/catalog-files/fetch");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body as string)).toEqual({
      url: "https://example.com/catalog-info.yaml",
    });
  });
});
