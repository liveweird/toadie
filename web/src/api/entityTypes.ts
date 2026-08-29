// Entity-types API — the ADMIN-curated per-kind dictionaries of allowed `spec.type` values
// (an internal Toadie constraint; Backstage leaves the field an open string). One row = one
// kind's list; the dictionaries are independent of one another. Catalog writes are
// validated against them server-side (strict); the editor consumes the list as its Type
// picker's source. Thin endpoint wrappers: transport in ./http, types from ./schema.

import { jsonRequest, voidRequest } from "./http";
import type { paths } from "./schema";

export type EntityTypes =
  paths["/api/v1/entity-types"]["get"]["responses"]["200"]["content"]["application/json"]["items"][number];
export type EntityTypesBody =
  paths["/api/v1/entity-types"]["post"]["requestBody"]["content"]["application/json"];
type EntityTypesList = paths["/api/v1/entity-types"]["get"]["responses"]["200"]["content"]["application/json"];

export async function getEntityTypes(): Promise<EntityTypes[]> {
  return (await jsonRequest<EntityTypesList>("/api/v1/entity-types")).items;
}

export async function createEntityTypes(body: EntityTypesBody): Promise<EntityTypes> {
  return jsonRequest<EntityTypes>("/api/v1/entity-types", { method: "POST", body: JSON.stringify(body) });
}

export async function updateEntityTypes(id: number, body: EntityTypesBody): Promise<void> {
  await voidRequest(`/api/v1/entity-types/${id}`, { method: "PUT", body: JSON.stringify(body) });
}

export async function deleteEntityTypes(id: number): Promise<void> {
  await voidRequest(`/api/v1/entity-types/${id}`, { method: "DELETE" });
}
