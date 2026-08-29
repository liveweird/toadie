// Catalog-files API — CRUD + the paginated list. Thin endpoint wrappers: transport
// (authedFetch/ApiError) in ./http, types from the generated ./schema.

import { ApiError, buildQuery, jsonRequest, voidRequest } from "./http";
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
  tag?: string;
};

export async function listCatalogFiles(q: CatalogFileListQuery): Promise<CatalogFilePage> {
  const params = buildQuery({
    page: q.page,
    pageSize: q.pageSize,
    sort: q.sort,
    name: q.name,
    namespace: q.namespace,
    kind: q.kind,
    tag: q.tag,
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

/** The write options: `allowInvalid` waives the soft checks (the editor's Save-anyway flow). */
export type CatalogSaveOptions = { allowInvalid?: boolean };

// An omit-when-false param (the buildQuery contract): only `true` ever travels.
const saveQuery = (opts?: CatalogSaveOptions) =>
  buildQuery({ allowInvalid: opts?.allowInvalid || undefined });

export async function createCatalogFile(
  req: CatalogFileRequest,
  opts?: CatalogSaveOptions,
): Promise<CatalogFileResponse> {
  const params = saveQuery(opts);
  return jsonRequest<CatalogFileResponse>(`/api/v1/catalog-files${params ? `?${params}` : ""}`, {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export async function updateCatalogFile(
  id: number,
  req: CatalogFileRequest,
  opts?: CatalogSaveOptions,
): Promise<void> {
  const params = saveQuery(opts);
  await voidRequest(`/api/v1/catalog-files/${id}${params ? `?${params}` : ""}`, {
    method: "PUT",
    body: JSON.stringify(req),
  });
}

export async function deleteCatalogFile(id: number): Promise<void> {
  await voidRequest(`/api/v1/catalog-files/${id}`, { method: "DELETE" });
}

export type CrossCheckReport =
  paths["/api/v1/catalog-files/cross-check"]["get"]["responses"]["200"]["content"]["application/json"];
export type DocumentCheckReport =
  paths["/api/v1/catalog-files/check"]["post"]["responses"]["200"]["content"]["application/json"];
export type DocumentCheckFinding = components["schemas"]["DocumentCheckFinding"];

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

/**
 * Classifies a save rejection for the Save-anyway flow: on a strict-save 400, asks /check
 * whether the document carries SOFT findings (waivable via `allowInvalid`). Null = not a
 * soft rejection (a structural 400, another status, or the check itself failed) — map it
 * through the ordinary error path instead.
 */
export async function softRejectionFindings(
  err: unknown,
  req: CatalogFileRequest,
): Promise<DocumentCheckFinding[] | null> {
  if (!(err instanceof ApiError) || err.status !== 400) return null;
  try {
    const report = await checkCatalogFile(req);
    return report.findings.length > 0 ? report.findings : null;
  } catch {
    return null;
  }
}

export type CatalogExport =
  paths["/api/v1/catalog-files/export"]["get"]["responses"]["200"]["content"]["application/json"];
export type ImportResult =
  paths["/api/v1/catalog-files/import"]["post"]["responses"]["200"]["content"]["application/json"];
export type ImportFileResult = components["schemas"]["ImportFileResult"];

/** The workspace (or one namespace) as structured documents — the SPA renders the YAML. */
export async function exportCatalogFiles(namespace?: string): Promise<CatalogExport> {
  const params = buildQuery({ namespace });
  return jsonRequest<CatalogExport>(`/api/v1/catalog-files/export${params ? `?${params}` : ""}`);
}

/** Report & skip: one result row per document, 200 even when every document failed. */
export async function importCatalogFiles(files: CatalogFileRequest[]): Promise<ImportResult> {
  return jsonRequest<ImportResult>("/api/v1/catalog-files/import", {
    method: "POST",
    body: JSON.stringify({ files }),
  });
}

export type FetchUrlResult =
  paths["/api/v1/catalog-files/fetch"]["post"]["responses"]["200"]["content"]["application/json"];

/** Server-side fetch of a catalog-info.yaml URL (SSRF-guarded); returns the raw text. */
export async function fetchCatalogUrl(url: string): Promise<FetchUrlResult> {
  return jsonRequest<FetchUrlResult>("/api/v1/catalog-files/fetch", {
    method: "POST",
    body: JSON.stringify({ url }),
  });
}
