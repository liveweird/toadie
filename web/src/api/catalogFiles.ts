// Catalog-files API — CRUD + the paginated list. Thin endpoint wrappers: transport
// (authedFetch/ApiError) in ./http, types from the generated ./schema.

import { buildQuery, jsonRequest, voidRequest } from "./http";
import type { components, paths } from "./schema";

export type CatalogFilePage =
  paths["/api/v1/catalog-files"]["get"]["responses"]["200"]["content"]["application/json"];
export type CatalogFileListItem = components["schemas"]["CatalogFileListItem"];
export type CatalogFileRequest = components["schemas"]["CatalogFileRequest"];
export type CatalogFileResponse =
  paths["/api/v1/catalog-files/{id}"]["get"]["responses"]["200"]["content"]["application/json"];

type CatalogFileListQuery = {
  page: number;
  pageSize: number;
  sort?: string;
  name?: string;
  namespace?: string;
};

export async function listCatalogFiles(q: CatalogFileListQuery): Promise<CatalogFilePage> {
  const params = buildQuery({
    page: q.page,
    pageSize: q.pageSize,
    sort: q.sort,
    name: q.name,
    namespace: q.namespace,
  });
  return jsonRequest<CatalogFilePage>(`/api/v1/catalog-files?${params}`);
}

export async function getCatalogFile(id: number): Promise<CatalogFileResponse> {
  return jsonRequest<CatalogFileResponse>(`/api/v1/catalog-files/${id}`);
}

export async function createCatalogFile(req: CatalogFileRequest): Promise<CatalogFileResponse> {
  return jsonRequest<CatalogFileResponse>("/api/v1/catalog-files", {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export async function updateCatalogFile(id: number, req: CatalogFileRequest): Promise<void> {
  await voidRequest(`/api/v1/catalog-files/${id}`, {
    method: "PUT",
    body: JSON.stringify(req),
  });
}

export async function deleteCatalogFile(id: number): Promise<void> {
  await voidRequest(`/api/v1/catalog-files/${id}`, { method: "DELETE" });
}

export type CrossCheckReport =
  paths["/api/v1/catalog-files/cross-check"]["get"]["responses"]["200"]["content"]["application/json"];
export type CrossCheckStatus = components["schemas"]["CrossCheckStatus"];
export type DocumentCheckReport =
  paths["/api/v1/catalog-files/check"]["post"]["responses"]["200"]["content"]["application/json"];

/** The workspace report: every stored file's references resolved against the store. */
export async function getCrossCheckReport(): Promise<CrossCheckReport> {
  return jsonRequest<CrossCheckReport>("/api/v1/catalog-files/cross-check");
}

/** The editor's live check of one (possibly unsaved) document against the store. */
export async function checkCatalogFile(req: CatalogFileRequest): Promise<DocumentCheckReport> {
  return jsonRequest<DocumentCheckReport>("/api/v1/catalog-files/check", {
    method: "POST",
    body: JSON.stringify(req),
  });
}
