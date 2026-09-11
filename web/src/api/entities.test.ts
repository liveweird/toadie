import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { jsonResponse } from "../test/http";
import { ApiError } from "./http";
import { entitySaveFindings, getEntityGraph, listEntities } from "./entities";

type FetchMock = ReturnType<typeof vi.fn>;

describe("entities API wrappers", () => {
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

  test("getEntityGraph with no filters hits the bare path", async () => {
    mockFetch.mockResolvedValue(jsonResponse(200, { nodes: [], edges: [] }));
    await getEntityGraph();
    expect(lastCall()[0]).toBe("/api/v1/entities/graph");
  });

  test("getEntityGraph repeats blueprint= per entry (the server's IN semantics)", async () => {
    mockFetch.mockResolvedValue(jsonResponse(200, { nodes: [], edges: [] }));
    await getEntityGraph({ blueprints: ["team", "service"], q: "pay" });
    expect(lastCall()[0]).toBe("/api/v1/entities/graph?blueprint=team&blueprint=service&q=pay");
  });

  test("an empty blueprints array is an absent filter", async () => {
    mockFetch.mockResolvedValue(jsonResponse(200, { nodes: [], edges: [] }));
    await getEntityGraph({ blueprints: [] });
    expect(lastCall()[0]).toBe("/api/v1/entities/graph");
  });

  test("getEntityGraph sends team= when given", async () => {
    mockFetch.mockResolvedValue(jsonResponse(200, { nodes: [], edges: [] }));
    await getEntityGraph({ team: "platform" });
    expect(lastCall()[0]).toBe("/api/v1/entities/graph?team=platform");
  });

  test("listEntities sends team= alongside blueprint/q", async () => {
    mockFetch.mockResolvedValue(jsonResponse(200, { items: [], page: 1, pageSize: 20, total: 0 }));
    await listEntities({ blueprint: "service", team: "platform", page: 1, pageSize: 20 });
    expect(lastCall()[0]).toBe("/api/v1/entities?blueprint=service&team=platform&page=1&pageSize=20");
  });

  test("listEntities omits team when blank/absent", async () => {
    mockFetch.mockResolvedValue(jsonResponse(200, { items: [], page: 1, pageSize: 20, total: 0 }));
    await listEntities({ page: 1, pageSize: 20 });
    expect(lastCall()[0]).toBe("/api/v1/entities?page=1&pageSize=20");
  });
});

describe("entitySaveFindings", () => {
  test("reads the findings array off a 400 problem body", () => {
    const err = new ApiError(400, { title: "Invalid", status: 400, findings: [{ code: "TEAM_TARGET_MISSING", field: "team", message: "x" }] });
    expect(entitySaveFindings(err)).toEqual([{ code: "TEAM_TARGET_MISSING", field: "team", message: "x" }]);
  });

  test("a 400 with no findings member answers empty", () => {
    expect(entitySaveFindings(new ApiError(400, { title: "Invalid", status: 400 }))).toEqual([]);
  });

  test("a 400 with a malformed findings member answers empty defensively", () => {
    expect(entitySaveFindings(new ApiError(400, { findings: "not-an-array" }))).toEqual([]);
  });

  test("a non-400 ApiError answers empty", () => {
    expect(entitySaveFindings(new ApiError(409, { findings: [{ code: "TEAM_TARGET_MISSING" }] }))).toEqual([]);
  });

  test("a non-ApiError answers empty", () => {
    expect(entitySaveFindings(new Error("network"))).toEqual([]);
  });
});
