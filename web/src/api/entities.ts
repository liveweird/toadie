// Entities API (Port migration phase 2, v1.24.0) — instances of a blueprint: every entity
// belongs to one blueprint, carries `properties` typed by that blueprint's schema, and
// `relations` naming other entities of the target blueprints. Any authenticated user may
// mutate (a shared workspace, like catalog files — no admin gate anywhere in this feature).
// Thin endpoint wrappers: transport (authedFetch/ApiError) in ./http, types from ./schema.

import { ApiError, buildQuery, jsonRequest, voidRequest } from "./http";
import type { paths } from "./schema";

export type Entity =
  paths["/api/v1/entities"]["get"]["responses"]["200"]["content"]["application/json"]["items"][number];
export type EntityBody = paths["/api/v1/entities"]["post"]["requestBody"]["content"]["application/json"];
export type EntityPage = paths["/api/v1/entities"]["get"]["responses"]["200"]["content"]["application/json"];
export type EntityFinding = Entity["findings"][number];

export type ListEntitiesQuery = {
  /** Equality filter on the blueprint identifier; an unknown identifier answers 200 empty. */
  blueprint?: string;
  /** Phase 4 ownership: case-insensitive match against the entity's EFFECTIVE team, or the
   *  row itself being the `_team` entity named by this value. Blank/absent is no filter. */
  team?: string;
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
    team: q.team,
    q: q.q,
    page: q.page,
    pageSize: q.pageSize,
    sort: q.sort,
  });
  return jsonRequest<EntityPage>(`/api/v1/entities?${params}`);
}

/**
 * A create/replace `400`'s `findings` list (`EntityInvalidProblem`), read DEFENSIVELY off the
 * problem body via `ApiError`'s own public `body` field — the sanctioned access `ApiError`
 * offers beyond its typed `detail`/`instance` getters, since `findings` is specific to this
 * one response shape rather than a generic RFC 7807 member. Any other 400 (an unknown
 * blueprint, a malformed body) or non-400/network error answers an empty array, same as "no
 * findings to paint" — never throws.
 */
export function entitySaveFindings(err: unknown): EntityFinding[] {
  if (!(err instanceof ApiError) || err.status !== 400) return [];
  const body = err.body as { findings?: unknown } | null;
  return Array.isArray(body?.findings) ? (body.findings as EntityFinding[]) : [];
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
  /** Same EFFECTIVE-team/self-`_team`-match rule as the list's `team` filter — keeps a
   *  team-filtered graph showing that team's own node and its ownership edges. */
  team?: string;
  /** Substring over identifier OR title. */
  q?: string;
};

export async function getEntityGraph(query: GetEntityGraphQuery = {}): Promise<EntityGraph> {
  const params = buildQuery({ blueprint: query.blueprints, team: query.team, q: query.q });
  return jsonRequest<EntityGraph>(`/api/v1/entities/graph${params ? `?${params}` : ""}`);
}
