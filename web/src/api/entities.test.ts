import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { jsonResponse } from "../test/http";
import { getEntityGraph } from "./entities";

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
});
