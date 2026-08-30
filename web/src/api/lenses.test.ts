import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { jsonResponse } from "../test/http";
import { createLens, deleteLens, getLenses, updateLens, type LensBody } from "./lenses";

type FetchMock = ReturnType<typeof vi.fn>;

const BODY: LensBody = {
  name: "Team A services",
  visibility: "PRIVATE",
  filters: { namespace: "team-a", kind: ["Component"] },
};

describe("lenses API wrappers", () => {
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

  test("getLenses unwraps the items envelope", async () => {
    mockFetch.mockResolvedValue(jsonResponse(200, { items: [{ id: 1, name: "Mine" }] }));
    const lenses = await getLenses();
    expect(lastCall()[0]).toBe("/api/v1/lenses");
    expect(lenses).toEqual([{ id: 1, name: "Mine" }]);
  });

  test("createLens POSTs the body and returns the created lens", async () => {
    mockFetch.mockResolvedValue(jsonResponse(201, { id: 9, ...BODY }));
    const created = await createLens(BODY);
    const [url, init] = lastCall();
    expect(url).toBe("/api/v1/lenses");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body as string)).toEqual(BODY);
    expect(created.id).toBe(9);
  });

  test("updateLens PUTs to the id route", async () => {
    mockFetch.mockResolvedValue(new Response(null, { status: 204 }));
    await updateLens(9, BODY);
    const [url, init] = lastCall();
    expect(url).toBe("/api/v1/lenses/9");
    expect(init.method).toBe("PUT");
  });

  test("deleteLens DELETEs the id route", async () => {
    mockFetch.mockResolvedValue(new Response(null, { status: 204 }));
    await deleteLens(9);
    const [url, init] = lastCall();
    expect(url).toBe("/api/v1/lenses/9");
    expect(init.method).toBe("DELETE");
  });
});
