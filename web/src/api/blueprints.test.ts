import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { jsonResponse } from "../test/http";
import {
  createBlueprint,
  deleteBlueprint,
  getBlueprint,
  listBlueprints,
  updateBlueprint,
  type BlueprintBody,
} from "./blueprints";

type FetchMock = ReturnType<typeof vi.fn>;

const BODY: BlueprintBody = {
  identifier: "microservice",
  title: "Microservice",
  schema: { properties: {}, required: [] },
  relations: {},
  mirrorProperties: {},
  calculationProperties: {},
  aggregationProperties: {},
};

describe("blueprints API wrappers", () => {
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

  test("listBlueprints unwraps the items envelope", async () => {
    mockFetch.mockResolvedValue(jsonResponse(200, { items: [{ id: 1, ...BODY }] }));
    const blueprints = await listBlueprints();
    expect(lastCall()[0]).toBe("/api/v1/blueprints");
    expect(blueprints).toEqual([{ id: 1, ...BODY }]);
  });

  test("getBlueprint GETs the id route", async () => {
    mockFetch.mockResolvedValue(jsonResponse(200, { id: 3, ...BODY }));
    const blueprint = await getBlueprint(3);
    expect(lastCall()[0]).toBe("/api/v1/blueprints/3");
    expect(blueprint.id).toBe(3);
  });

  test("createBlueprint POSTs the body and returns the created blueprint", async () => {
    mockFetch.mockResolvedValue(jsonResponse(201, { id: 9, ...BODY }));
    const created = await createBlueprint(BODY);
    const [url, init] = lastCall();
    expect(url).toBe("/api/v1/blueprints");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body as string)).toEqual(BODY);
    expect(created.id).toBe(9);
  });

  test("updateBlueprint PUTs to the id route", async () => {
    mockFetch.mockResolvedValue(new Response(null, { status: 204 }));
    await updateBlueprint(9, BODY);
    const [url, init] = lastCall();
    expect(url).toBe("/api/v1/blueprints/9");
    expect(init.method).toBe("PUT");
  });

  test("deleteBlueprint DELETEs the id route", async () => {
    mockFetch.mockResolvedValue(new Response(null, { status: 204 }));
    await deleteBlueprint(9);
    const [url, init] = lastCall();
    expect(url).toBe("/api/v1/blueprints/9");
    expect(init.method).toBe("DELETE");
  });
});
