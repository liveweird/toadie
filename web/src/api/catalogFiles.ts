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
  kind?: string;
};

export async function listCatalogFiles(q: CatalogFileListQuery): Promise<CatalogFilePage> {
  const params = buildQuery({
    page: q.page,
    pageSize: q.pageSize,
    sort: q.sort,
    name: q.name,
    namespace: q.namespace,
    kind: q.kind,
  });
  return jsonRequest<CatalogFilePage>(`/api/v1/catalog-files?${params}`);
}

/** Every stored file's list row, paging until the server total is reached (the pool loop). */
export async function listAllCatalogFiles(): Promise<CatalogFilePage["items"]> {
  const items: CatalogFilePage["items"] = [];
  let page = 1;
  for (;;) {
    const result = await listCatalogFiles({ page, pageSize: 100, sort: "name" });
    items.push(...result.items);
    if (items.length >= result.total || result.items.length === 0) return items;
    page += 1;
  }
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

export type CatalogGraph =
  paths["/api/v1/catalog-files/graph"]["get"]["responses"]["200"]["content"]["application/json"];
export type GraphNode = components["schemas"]["GraphNode"];

/** The rendered-together graph; a namespace narrows which files' references are expanded. */
export async function getCatalogGraph(namespace?: string): Promise<CatalogGraph> {
  const params = buildQuery({ namespace });
  return jsonRequest<CatalogGraph>(`/api/v1/catalog-files/graph${params ? `?${params}` : ""}`);
}

/** The editor's live check of one (possibly unsaved) document against the store. */
export async function checkCatalogFile(req: CatalogFileRequest): Promise<DocumentCheckReport> {
  return jsonRequest<DocumentCheckReport>("/api/v1/catalog-files/check", {
    method: "POST",
    body: JSON.stringify(req),
  });
}
