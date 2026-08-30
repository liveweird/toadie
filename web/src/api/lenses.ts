// Lenses API — named, saveable snapshots of the shared catalog filter set, applicable from
// any of the filterable views (Hierarchy/Files/Graph/Errors). PRIVATE lenses are visible
// only to their creator; PUBLIC lenses are visible to everyone but creator-only mutable.
// Thin endpoint wrappers: transport (authedFetch/ApiError) in ./http, types from ./schema.

import { jsonRequest, voidRequest } from "./http";
import type { paths } from "./schema";

export type Lens = paths["/api/v1/lenses"]["get"]["responses"]["200"]["content"]["application/json"]["items"][number];
export type LensBody = paths["/api/v1/lenses"]["post"]["requestBody"]["content"]["application/json"];
export type LensFilters = LensBody["filters"];
type LensList = paths["/api/v1/lenses"]["get"]["responses"]["200"]["content"]["application/json"];

export async function getLenses(): Promise<Lens[]> {
  return (await jsonRequest<LensList>("/api/v1/lenses")).items;
}

export async function createLens(body: LensBody): Promise<Lens> {
  return jsonRequest<Lens>("/api/v1/lenses", { method: "POST", body: JSON.stringify(body) });
}

export async function updateLens(id: number, body: LensBody): Promise<void> {
  await voidRequest(`/api/v1/lenses/${id}`, { method: "PUT", body: JSON.stringify(body) });
}

export async function deleteLens(id: number): Promise<void> {
  await voidRequest(`/api/v1/lenses/${id}`, { method: "DELETE" });
}
