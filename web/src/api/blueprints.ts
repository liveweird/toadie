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

// -- Bulk import (Phase 6, v1.28.0 — .claude/docs/port-data-model.md "Import and export") ---
// POST /api/v1/blueprints/import(/check): per-row report-and-skip over a batch of Port-shaped
// blueprint documents, ADMIN-gated (both endpoints). `checkBlueprintImport` is the dry-run
// (identical row shape, nothing stored).

export type BlueprintImportResponse =
  paths["/api/v1/blueprints/import"]["post"]["responses"]["200"]["content"]["application/json"];

async function postBlueprintImport(
  path: "import" | "import/check",
  documents: Record<string, unknown>[],
  replaceExisting: boolean,
  sourceUrl?: string,
): Promise<BlueprintImportResponse> {
  return jsonRequest<BlueprintImportResponse>(`/api/v1/blueprints/${path}`, {
    method: "POST",
    body: JSON.stringify({ documents, replaceExisting, sourceUrl }),
  });
}

/**
 * `sourceUrl` (2.10.0, the fetch-from-URL import flow, `importEntities`'s twin one level down):
 * every CREATED/UPDATED row gets it as its source reference AND starts synced. Omit for
 * pasted/uploaded batches.
 */
export async function importBlueprints(
  documents: Record<string, unknown>[],
  replaceExisting: boolean,
  sourceUrl?: string,
): Promise<BlueprintImportResponse> {
  return postBlueprintImport("import", documents, replaceExisting, sourceUrl);
}

/** The dry-run: identical row shape, nothing stored or audited. `sourceUrl` is accepted for
 *  parity with `importBlueprints` (the server ignores its effect on a dry-run's classification). */
export async function checkBlueprintImport(
  documents: Record<string, unknown>[],
  replaceExisting: boolean,
  sourceUrl?: string,
): Promise<BlueprintImportResponse> {
  return postBlueprintImport("import/check", documents, replaceExisting, sourceUrl);
}

// -- Source references & HTTP re-sync (2.10.0) — the `api/entities.ts` twin, one level up:
// `POST /api/v1/blueprints/fetch` (SSRF-guarded, the shared `infra/fetch/UrlFetcher`) and
// `GET`/`POST /api/v1/blueprints/{id}/sync`. Unlike the catalog's repo sync, the blueprint sync
// NEVER waives: a fetched copy failing validation is refused outright (`400`).

export type BlueprintSyncState =
  paths["/api/v1/blueprints/{id}/sync"]["get"]["responses"]["200"]["content"]["application/json"];

export type FetchBlueprintUrlResult =
  paths["/api/v1/blueprints/fetch"]["post"]["responses"]["200"]["content"]["application/json"];

/** Server-side fetch of a URL (SSRF-guarded, ADMIN only); returns the raw text — a Port
 *  blueprint JSON document, parsing stays a client concern. The shared fetcher behind
 *  `POST /entities/fetch`, one connection pool for both. */
export async function fetchBlueprintUrl(url: string): Promise<FetchBlueprintUrlResult> {
  return jsonRequest<FetchBlueprintUrlResult>("/api/v1/blueprints/fetch", {
    method: "POST",
    body: JSON.stringify({ url }),
  });
}

/** The blueprint's sync state: source URL, last-sync stamp, and the baseline document
 *  (including the merged `hierarchyRelations`). Any authenticated user may read it. */
export async function getBlueprintSyncState(id: number): Promise<BlueprintSyncState> {
  return jsonRequest<BlueprintSyncState>(`/api/v1/blueprints/${id}/sync`);
}

/**
 * The HTTP→DB sync (ADMIN only): overwrites the stored definition with `document` (the parsed
 * remote copy) and stamps the sync state. No waiver — a submitted document failing validation
 * is a `400`, exactly like a strict create/replace.
 */
export async function syncBlueprint(id: number, document: BlueprintBody): Promise<void> {
  await voidRequest(`/api/v1/blueprints/${id}/sync`, {
    method: "POST",
    body: JSON.stringify({ document }),
  });
}
