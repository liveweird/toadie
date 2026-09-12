// Shared typed fixture builders for hand-rolled API response literals in tests. Types come
// from the generated ./schema.ts via the aliases the api modules already export — never
// re-derive a schema path here. Each builder returns a fully-populated object with sensible
// defaults; pass `overrides` to change only what a test cares about.

import type { CatalogFileListItem, CatalogFileResponse } from "../api/catalogFiles";

const EPOCH = 1_700_000_000_000;

/** The `{items, page, pageSize, total}` list envelope every paged endpoint answers.
 *  `total` defaults to `items.length` — pass it explicitly to simulate a narrower page. */
export function pageOf<T>(
  items: T[],
  overrides: Partial<{ page: number; pageSize: number; total: number }> = {},
): { items: T[]; page: number; pageSize: number; total: number } {
  return {
    items,
    page: overrides.page ?? 1,
    pageSize: overrides.pageSize ?? 20,
    total: overrides.total ?? items.length,
  };
}

/** A `CatalogFileListItem` row — the Files list/graph/drawer/history-header shape. */
export function catalogFileListItem(
  overrides: Partial<CatalogFileListItem> = {},
): CatalogFileListItem {
  return {
    id: 1,
    kind: "Component",
    name: "payments-svc",
    namespace: "default",
    title: null,
    type: "service",
    lifecycle: "production",
    owner: null,
    tags: [],
    creatorName: "Alice Creator",
    creatorDeleted: false,
    updatedAt: EPOCH,
    sourceUrl: null,
    lastSyncedAt: 0,
    ...overrides,
  };
}

/** A full `CatalogFileResponse` — the editor's `GET /files/{id}` shape. `metadata`/`spec`
 *  overrides merge shallowly onto the defaults rather than replacing the whole sub-object. */
export function catalogFileResponse(
  overrides: Partial<CatalogFileResponse> = {},
): CatalogFileResponse {
  const { metadata, spec, ...rest } = overrides;
  return {
    id: 1,
    kind: "Component",
    metadata: { name: "payments-svc", namespace: "default", ...metadata },
    spec: { type: "service", lifecycle: "production", owner: null, ...spec },
    createdBy: 1,
    creatorName: "Alice Creator",
    creatorDeleted: false,
    createdAt: EPOCH,
    updatedAt: EPOCH,
    sourceUrl: null,
    lastSyncedAt: 0,
    ...rest,
  };
}
