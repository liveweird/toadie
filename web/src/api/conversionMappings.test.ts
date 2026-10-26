import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { ApiError } from "./http";
import {
  convertToEntityDocuments,
  getCatalogDocuments,
  mappingSaveFindings,
} from "./conversionMappings";
import { jsonResponse } from "../test/http";

describe("conversionMappings api", () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    localStorage.setItem("toadie.auth.token", "fake-token");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("getCatalogDocuments sends the filter set, entityId and an explicit merge=false", async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { files: [], rows: [] }));
    await getCatalogDocuments(7, { blueprints: ["svc", "db"], q: "alpha", team: "core", query: "MATCH (a) RETURN a", entityId: 3, merge: false });
    const url = new URL(fetchMock.mock.calls[0][0] as string, "http://x");
    expect(url.pathname).toBe("/api/v1/conversion-mappings/7/catalog-documents");
    expect(url.searchParams.getAll("blueprint")).toEqual(["svc", "db"]);
    expect(url.searchParams.get("q")).toBe("alpha");
    expect(url.searchParams.get("team")).toBe("core");
    expect(url.searchParams.get("query")).toBe("MATCH (a) RETURN a");
    expect(url.searchParams.get("entityId")).toBe("3");
    expect(url.searchParams.get("merge")).toBe("false");
  });

  test("getCatalogDocuments omits blank filters and keeps merge=true", async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { files: [], rows: [] }));
    await getCatalogDocuments(7, { q: "", merge: true });
    const url = new URL(fetchMock.mock.calls[0][0] as string, "http://x");
    expect([...url.searchParams.keys()]).toEqual(["merge"]);
    expect(url.searchParams.get("merge")).toBe("true");
  });

  test("convertToEntityDocuments POSTs files and merge", async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { documents: [], rows: [] }));
    await convertToEntityDocuments(4, { files: [], merge: true });
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/v1/conversion-mappings/4/entity-documents");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body as string)).toEqual({ files: [], merge: true });
  });

  test("mappingSaveFindings reads a 400's findings and is empty for anything else", () => {
    const findings = [{ code: "SCHEMA", path: "/pairs", message: "bad" }];
    expect(mappingSaveFindings(new ApiError(400, { findings }))).toEqual(findings);
    expect(mappingSaveFindings(new ApiError(400, { title: "x" }))).toEqual([]);
    expect(mappingSaveFindings(new ApiError(409, { findings }))).toEqual([]);
    expect(mappingSaveFindings(new Error("net"))).toEqual([]);
  });
});
