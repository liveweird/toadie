// Entities API (Port migration phase 2, v1.24.0) — instances of a blueprint: every entity
// belongs to one blueprint, carries `properties` typed by that blueprint's schema, and
// `relations` naming other entities of the target blueprints. Any authenticated user may
// mutate (a shared workspace, like catalog files — no admin gate anywhere in this feature).
// Thin endpoint wrappers: transport (authedFetch/ApiError) in ./http, types from ./schema.

import { buildQuery, jsonRequest, voidRequest } from "./http";
import type { paths } from "./schema";

export type Entity =
  paths["/api/v1/entities"]["get"]["responses"]["200"]["content"]["application/json"]["items"][number];
export type EntityBody = paths["/api/v1/entities"]["post"]["requestBody"]["content"]["application/json"];
export type EntityPage = paths["/api/v1/entities"]["get"]["responses"]["200"]["content"]["application/json"];
export type EntityFinding = Entity["findings"][number];

export type ListEntitiesQuery = {
  /** Equality filter on the blueprint identifier; an unknown identifier answers 200 empty. */
  blueprint?: string;
  /** Substring over identifier OR title. */
  q?: string;
  page: number;
  pageSize: number;
  /** Whitelist identifier/title/updatedAt, "-" prefix for desc; default identifier. */
  sort?: string;
};

export async function listEntities(q: ListEntitiesQuery): Promise<EntityPage> {
  const params = buildQuery({
    blueprint: q.blueprint,
    q: q.q,
    page: q.page,
    pageSize: q.pageSize,
    sort: q.sort,
  });
  return jsonRequest<EntityPage>(`/api/v1/entities?${params}`);
}

export async function getEntity(id: number): Promise<Entity> {
  return jsonRequest<Entity>(`/api/v1/entities/${id}`);
}

export async function createEntity(body: EntityBody): Promise<Entity> {
  return jsonRequest<Entity>("/api/v1/entities", { method: "POST", body: JSON.stringify(body) });
}

export async function updateEntity(id: number, body: EntityBody): Promise<void> {
  await voidRequest(`/api/v1/entities/${id}`, { method: "PUT", body: JSON.stringify(body) });
}

export async function deleteEntity(id: number): Promise<void> {
  await voidRequest(`/api/v1/entities/${id}`, { method: "DELETE" });
}

// -- Entity graph (Port migration phase 3, v1.25.0) --------------------------------------
// GET /api/v1/entities/graph?blueprint=<repeated>&q= — the rendered-together view over
// entities: nodes are shown entities (blueprint/search filtered), edges are relation values
// with both ends shown (the catalog graph's rule); `hierarchy` marks the edge as the source
// blueprint's admin-picked `hierarchyRelation`. Node id grammar: `"<blueprint>|<identifier>"`.

export type EntityGraph =
  paths["/api/v1/entities/graph"]["get"]["responses"]["200"]["content"]["application/json"];
export type EntityGraphNode = EntityGraph["nodes"][number];
export type EntityGraphEdge = EntityGraph["edges"][number];

export type GetEntityGraphQuery = {
  /** Any-of over blueprint identifiers; an unknown identifier folds to an empty graph. */
  blueprints?: readonly string[];
  /** Substring over identifier OR title. */
  q?: string;
};

export async function getEntityGraph(query: GetEntityGraphQuery = {}): Promise<EntityGraph> {
  const params = buildQuery({ blueprint: query.blueprints, q: query.q });
  return jsonRequest<EntityGraph>(`/api/v1/entities/graph${params ? `?${params}` : ""}`);
}
