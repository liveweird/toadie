import { afterEach, describe, expect, test, vi } from "vitest";
import {
  createIntegrationClient,
  listIntegrationClients,
  revokeIntegrationClient,
} from "./integrationClients";
import { jsonResponse } from "../test/http";

afterEach(() => vi.unstubAllGlobals());

describe("integration clients API", () => {
  test("lists a requested page", async () => {
    const body = { items: [], page: 2, pageSize: 40, total: 0 };
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, body));
    vi.stubGlobal("fetch", fetchMock);

    await expect(listIntegrationClients(2, 40)).resolves.toEqual(body);
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/v1/integration-clients?page=2&pageSize=40",
      expect.any(Object),
    );
  });

  test("creates and revokes a client", async () => {
    const created = {
      client: { id: 7, name: "warehouse", createdAt: 1, createdByName: "Admin", revoked: false, scope: "read" },
      apiKey: "toadie_int_secret",
    };
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(201, created))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(createIntegrationClient("warehouse", "read")).resolves.toEqual(created);
    await expect(revokeIntegrationClient(7)).resolves.toBeUndefined();
    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      "/api/v1/integration-clients",
      expect.objectContaining({ method: "POST", body: JSON.stringify({ name: "warehouse", scope: "read" }) }),
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      "/api/v1/integration-clients/7/revoke",
      expect.objectContaining({ method: "POST" }),
    );
  });
});
