// Catalog-files API — CRUD + the paginated list. Thin endpoint wrappers: transport
// (authedFetch/ApiError) in ./http, types from the generated ./schema.

import { ApiError, buildQuery, jsonRequest, voidRequest } from "./http";
import type { components, paths } from "./schema";

export type CatalogFilePage =
  paths["/api/v1/files"]["get"]["responses"]["200"]["content"]["application/json"];
export type CatalogFileListItem = components["schemas"]["CatalogFileListItem"];
export type CatalogFileRequest = components["schemas"]["CatalogFileRequest"];
/** The create/replace body: the pure document plus the optional source reference. */
export type CatalogFileWriteRequest = components["schemas"]["CatalogFileWriteRequest"];
export type CatalogFileResponse =
  paths["/api/v1/files/{id}"]["get"]["responses"]["200"]["content"]["application/json"];

/**
 * The shared catalog-file filter set — the list, graph, and errors endpoints declare the
 * same whitelisted params (labelValue repeats as the server's documented IN idiom).
 */
export type CatalogFileFilterValues = {
  name?: string;
  namespace?: string;
  /** Any-of over the supported kinds — repeats as the server's documented IN idiom. */
  kind?: readonly string[];
  tag?: string;
  type?: string;
  lifecycle?: string;
  owner?: string;
  label?: string;
  labelValue?: readonly string[];
};

const filterParams = (f: CatalogFileFilterValues) => ({
  name: f.name,
  namespace: f.namespace,
  kind: f.kind,
  tag: f.tag,
  type: f.type,
  lifecycle: f.lifecycle,
  owner: f.owner,
  label: f.label,
  labelValue: f.labelValue,
});

type CatalogFileListQuery = CatalogFileFilterValues & {
  page: number;
  pageSize: number;
  sort?: string;
};

export async function listCatalogFiles(q: CatalogFileListQuery): Promise<CatalogFilePage> {
  const params = buildQuery({
    page: q.page,
    pageSize: q.pageSize,
    sort: q.sort,
    ...filterParams(q),
  });
  return jsonRequest<CatalogFilePage>(`/api/v1/files?${params}`);
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
  return jsonRequest<CatalogFileResponse>(`/api/v1/files/${id}`);
}

/** The write options: `allowInvalid` waives the soft checks (the editor's Save-anyway flow). */
export type CatalogSaveOptions = { allowInvalid?: boolean };

// An omit-when-false param (the buildQuery contract): only `true` ever travels.
const saveQuery = (opts?: CatalogSaveOptions) =>
  buildQuery({ allowInvalid: opts?.allowInvalid || undefined });

export async function createCatalogFile(
  req: CatalogFileWriteRequest,
  opts?: CatalogSaveOptions,
): Promise<CatalogFileResponse> {
  const params = saveQuery(opts);
  return jsonRequest<CatalogFileResponse>(`/api/v1/files${params ? `?${params}` : ""}`, {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export async function updateCatalogFile(
  id: number,
  req: CatalogFileWriteRequest,
  opts?: CatalogSaveOptions,
): Promise<void> {
  const params = saveQuery(opts);
  await voidRequest(`/api/v1/files/${id}${params ? `?${params}` : ""}`, {
    method: "PUT",
    body: JSON.stringify(req),
  });
}

export async function deleteCatalogFile(id: number): Promise<void> {
  await voidRequest(`/api/v1/files/${id}`, { method: "DELETE" });
}

export type ErrorsReport =
  paths["/api/v1/files/errors"]["get"]["responses"]["200"]["content"]["application/json"];
export type DocumentCheckReport =
  paths["/api/v1/files/check"]["post"]["responses"]["200"]["content"]["application/json"];
export type DocumentCheckFinding = components["schemas"]["DocumentCheckFinding"];

/**
 * The workspace Errors report; the filters (the list's shared set) narrow which files'
 * errors are reported — references still resolve against the whole workspace.
 */
export async function getCatalogErrors(filters: CatalogFileFilterValues = {}): Promise<ErrorsReport> {
  const params = buildQuery(filterParams(filters));
  return jsonRequest<ErrorsReport>(`/api/v1/files/errors${params ? `?${params}` : ""}`);
}

export type CatalogGraph =
  paths["/api/v1/files/graph"]["get"]["responses"]["200"]["content"]["application/json"];
export type GraphNode = components["schemas"]["GraphNode"];

/**
 * The rendered-together graph; the filters (the list's shared set) narrow which files'
 * references are expanded — targets still resolve against the whole workspace.
 */
export async function getCatalogGraph(filters: CatalogFileFilterValues = {}): Promise<CatalogGraph> {
  const params = buildQuery(filterParams(filters));
  return jsonRequest<CatalogGraph>(`/api/v1/files/graph${params ? `?${params}` : ""}`);
}

/** The editor's live check of one (possibly unsaved) document against the store. */
export async function checkCatalogFile(req: CatalogFileRequest): Promise<DocumentCheckReport> {
  return jsonRequest<DocumentCheckReport>("/api/v1/files/check", {
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

export type ImportResult =
  paths["/api/v1/files/import"]["post"]["responses"]["200"]["content"]["application/json"];
export type ImportFileResult = components["schemas"]["ImportFileResult"];


/**
 * Report & skip: one result row per document, 200 even when every document failed.
 * `sourceUrl` (the fetch-from-URL flow) makes every stored row carry the reference and
 * start synced — an import from a repo URL IS a sync.
 */
export async function importCatalogFiles(
  files: CatalogFileRequest[],
  sourceUrl?: string,
): Promise<ImportResult> {
  return jsonRequest<ImportResult>("/api/v1/files/import", {
    method: "POST",
    body: JSON.stringify({ files, sourceUrl }),
  });
}

/** The import's dry-run: the same per-row report, predicted — nothing is stored. */
export async function checkImportCatalogFiles(files: CatalogFileRequest[]): Promise<ImportResult> {
  return jsonRequest<ImportResult>("/api/v1/files/import/check", {
    method: "POST",
    body: JSON.stringify({ files }),
  });
}

export type FetchUrlResult =
  paths["/api/v1/files/fetch"]["post"]["responses"]["200"]["content"]["application/json"];

/** Server-side fetch of a catalog-info.yaml URL (SSRF-guarded); returns the raw text. */
export async function fetchCatalogUrl(url: string): Promise<FetchUrlResult> {
  return jsonRequest<FetchUrlResult>("/api/v1/files/fetch", {
    method: "POST",
    body: JSON.stringify({ url }),
  });
}

export type SyncState =
  paths["/api/v1/files/{id}/sync"]["get"]["responses"]["200"]["content"]["application/json"];

/** The file's sync state: source URL, last-sync stamp, and the baseline document. */
export async function getSyncState(id: number): Promise<SyncState> {
  return jsonRequest<SyncState>(`/api/v1/files/${id}/sync`);
}

/**
 * The repo→DB sync: overwrites the DB copy with `document` (the parsed repo copy) and
 * stamps the sync state. Soft findings are always waived server-side (the import posture).
 */
export async function syncCatalogFile(id: number, document: CatalogFileRequest): Promise<void> {
  await voidRequest(`/api/v1/files/${id}/sync`, {
    method: "POST",
    body: JSON.stringify({ document }),
  });
}
