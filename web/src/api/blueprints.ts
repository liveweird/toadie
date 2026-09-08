// Blueprint registry API — Port-compatible user-definable entity kinds (phase 1: define,
// list, edit, delete; nothing attaches entities to them yet). ADMIN-only mutations,
// any-authenticated reads (the registries' posture). Thin endpoint wrappers: transport
// (authedFetch/ApiError) in ./http, types from ./schema.

import { jsonRequest, voidRequest } from "./http";
import type { paths } from "./schema";

export type Blueprint =
  paths["/api/v1/blueprints"]["get"]["responses"]["200"]["content"]["application/json"]["items"][number];
export type BlueprintBody = paths["/api/v1/blueprints"]["post"]["requestBody"]["content"]["application/json"];
type BlueprintList = paths["/api/v1/blueprints"]["get"]["responses"]["200"]["content"]["application/json"];

export async function listBlueprints(): Promise<Blueprint[]> {
  return (await jsonRequest<BlueprintList>("/api/v1/blueprints")).items;
}

export async function getBlueprint(id: number): Promise<Blueprint> {
  return jsonRequest<Blueprint>(`/api/v1/blueprints/${id}`);
}

export async function createBlueprint(body: BlueprintBody): Promise<Blueprint> {
  return jsonRequest<Blueprint>("/api/v1/blueprints", { method: "POST", body: JSON.stringify(body) });
}

export async function updateBlueprint(id: number, body: BlueprintBody): Promise<void> {
  await voidRequest(`/api/v1/blueprints/${id}`, { method: "PUT", body: JSON.stringify(body) });
}

export async function deleteBlueprint(id: number): Promise<void> {
  await voidRequest(`/api/v1/blueprints/${id}`, { method: "DELETE" });
}
