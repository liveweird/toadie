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

const ALL_ENTITIES_PAGE_SIZE = 100;
/** No relation-target picker or export needs more than this many rows — the server's
 *  MAX_ENTITIES_PER_BLUEPRINT cap (see `.claude/docs/persistence.md`); pooling stops early
 *  regardless of a larger stored total. */
const ALL_ENTITIES_MAX = 2000;

export type ListAllEntitiesQuery = { blueprint: string; team?: string; q?: string };

/**
 * Every active entity of one blueprint (optionally team/q-filtered), paging until the server
 * total is reached (the `listAllCatalogFiles`/`useEntityOptions` pool-loop idiom — there is no
 * dedicated options endpoint). Shared by the relation-target picker (`useEntityOptions`) and
 * the Entities page's JSON export (`utils/ontologyExport.ts`).
 */
export async function listAllEntities(query: ListAllEntitiesQuery): Promise<Entity[]> {
  const items: Entity[] = [];
  let page = 1;
  for (;;) {
    const result = await listEntities({ ...query, page, pageSize: ALL_ENTITIES_PAGE_SIZE, sort: "identifier" });
    items.push(...result.items);
    if (items.length >= result.total || result.items.length === 0 || items.length >= ALL_ENTITIES_MAX) {
      return items.slice(0, ALL_ENTITIES_MAX);
    }
    page += 1;
  }
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
// with both ends shown (the catalog graph's rule); `hierarchies` lists the hierarchy ids whose
// entry in the source blueprint's `hierarchyRelations` is this edge's relation. Node id grammar: `"<blueprint>|<identifier>"`.

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
  /** An entity query (phase 7, v2.0.0 — `.claude/docs/entity-query-language.md`), narrowing
   *  the shown set to the entities its `RETURN` variables bind to, intersected with the other
   *  filters. Blank/absent = no query. A refused query is the `EntityQueryInvalid` `400`. */
  query?: string;
};

export async function getEntityGraph(query: GetEntityGraphQuery = {}): Promise<EntityGraph> {
  const params = buildQuery({ blueprint: query.blueprints, team: query.team, q: query.q, query: query.query });
  return jsonRequest<EntityGraph>(`/api/v1/entities/graph${params ? `?${params}` : ""}`);
}

// -- Entity query check (phase 7, v2.0.0 — `.claude/docs/entity-query-language.md`) ----------
// POST /api/v1/entities/query/check: the editor's live diagnostics — parse + validate `query`
// against the current active blueprints/hierarchies without evaluating it, so the two
// evaluation-time codes (DEADLINE_EXCEEDED, BINDING_LIMIT) never appear here.

export type EntityQueryCheckResponse =
  paths["/api/v1/entities/query/check"]["post"]["responses"]["200"]["content"]["application/json"];
export type EntityQueryDiagnostic = EntityQueryCheckResponse["diagnostics"][number];

export async function checkEntityQuery(query: string): Promise<EntityQueryCheckResponse> {
  return jsonRequest<EntityQueryCheckResponse>("/api/v1/entities/query/check", {
    method: "POST",
    body: JSON.stringify({ query }),
  });
}

// -- Bulk import (Phase 6, v1.28.0 — .claude/docs/port-data-model.md "Import and export") ---
// POST /api/v1/entities/import(/check): per-row report-and-skip over a batch of Port-shaped
// entity documents, any-authenticated (no admin gate — the shared-workspace rule). The dry-run
// shares the row shape.

export type EntityImportResponse =
  paths["/api/v1/entities/import"]["post"]["responses"]["200"]["content"]["application/json"];

async function postEntityImport(
  path: "import" | "import/check",
  documents: Record<string, unknown>[],
  replaceExisting: boolean,
): Promise<EntityImportResponse> {
  return jsonRequest<EntityImportResponse>(`/api/v1/entities/${path}`, {
    method: "POST",
    body: JSON.stringify({ documents, replaceExisting }),
  });
}

export async function importEntities(
  documents: Record<string, unknown>[],
  replaceExisting: boolean,
): Promise<EntityImportResponse> {
  return postEntityImport("import", documents, replaceExisting);
}

/** The dry-run: identical row shape, nothing stored or audited. */
export async function checkEntityImport(
  documents: Record<string, unknown>[],
  replaceExisting: boolean,
): Promise<EntityImportResponse> {
  return postEntityImport("import/check", documents, replaceExisting);
}
