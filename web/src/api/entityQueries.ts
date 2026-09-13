// Saved entity queries API (phase 7 follow-up, "PR3 — saved entity queries") — named,
// saveable entity-query-language texts, applicable from either the Entity graph or Entity
// hierarchy canvas. PRIVATE queries are visible only to their creator; PUBLIC queries are
// visible to everyone but creator-only mutable — the `lenses.ts` shape one level down (a
// saved query carries the query TEXT instead of the catalog filter payload).
// Thin endpoint wrappers: transport (authedFetch/ApiError) in ./http, types from ./schema.

import { jsonRequest, voidRequest } from "./http";
import type { paths } from "./schema";

export type SavedEntityQuery =
  paths["/api/v1/entity-queries"]["get"]["responses"]["200"]["content"]["application/json"]["items"][number];
export type SavedEntityQueryBody =
  paths["/api/v1/entity-queries"]["post"]["requestBody"]["content"]["application/json"];
type SavedEntityQueryList =
  paths["/api/v1/entity-queries"]["get"]["responses"]["200"]["content"]["application/json"];

export async function getSavedEntityQueries(): Promise<SavedEntityQuery[]> {
  return (await jsonRequest<SavedEntityQueryList>("/api/v1/entity-queries")).items;
}

export async function createSavedEntityQuery(body: SavedEntityQueryBody): Promise<SavedEntityQuery> {
  return jsonRequest<SavedEntityQuery>("/api/v1/entity-queries", { method: "POST", body: JSON.stringify(body) });
}

export async function updateSavedEntityQuery(id: number, body: SavedEntityQueryBody): Promise<void> {
  await voidRequest(`/api/v1/entity-queries/${id}`, { method: "PUT", body: JSON.stringify(body) });
}

export async function deleteSavedEntityQuery(id: number): Promise<void> {
  await voidRequest(`/api/v1/entity-queries/${id}`, { method: "DELETE" });
}
