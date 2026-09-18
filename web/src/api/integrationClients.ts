// Integration clients API — admin key management for the read-only GraphQL integration API.
// Thin endpoint wrappers: transport and session-bound refresh live in ./http.

import { buildQuery, jsonRequest, voidRequest } from "./http";
import type { paths } from "./schema";

export type IntegrationClientPage =
  paths["/api/v1/integration-clients"]["get"]["responses"]["200"]["content"]["application/json"];
export type IntegrationClient = IntegrationClientPage["items"][number];
export type IntegrationClientCreated =
  paths["/api/v1/integration-clients"]["post"]["responses"]["201"]["content"]["application/json"];
type IntegrationClientCreateBody =
  paths["/api/v1/integration-clients"]["post"]["requestBody"]["content"]["application/json"];

export async function listIntegrationClients(page: number, pageSize: number): Promise<IntegrationClientPage> {
  const query = buildQuery({ page, pageSize });
  return jsonRequest<IntegrationClientPage>(`/api/v1/integration-clients?${query}`);
}

export async function createIntegrationClient(name: string): Promise<IntegrationClientCreated> {
  const body: IntegrationClientCreateBody = { name };
  return jsonRequest<IntegrationClientCreated>("/api/v1/integration-clients", {
    method: "POST",
    body: JSON.stringify(body),
  });
}

export async function revokeIntegrationClient(id: number): Promise<void> {
  await voidRequest(`/api/v1/integration-clients/${id}/revoke`, { method: "POST" });
}
