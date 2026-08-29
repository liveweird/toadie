// Annotation-key registry API — the ADMIN-curated allowed metadata.annotations KEYS, each
// with the entity kinds it applies to (values stay free strings — the labels registry's
// sibling with the value dimension dropped). Catalog writes are validated against it
// server-side (strict); the editor consumes the list as its annotation key picker's source.
// Thin endpoint wrappers: transport (authedFetch/ApiError) in ./http, types from ./schema.

import { jsonRequest, voidRequest } from "./http";
import type { paths } from "./schema";

export type AnnotationKey =
  paths["/api/v1/annotation-keys"]["get"]["responses"]["200"]["content"]["application/json"]["items"][number];
export type AnnotationKeyBody =
  paths["/api/v1/annotation-keys"]["post"]["requestBody"]["content"]["application/json"];
type AnnotationKeyList = paths["/api/v1/annotation-keys"]["get"]["responses"]["200"]["content"]["application/json"];

export async function getAnnotationKeys(): Promise<AnnotationKey[]> {
  return (await jsonRequest<AnnotationKeyList>("/api/v1/annotation-keys")).items;
}

export async function createAnnotationKey(body: AnnotationKeyBody): Promise<AnnotationKey> {
  return jsonRequest<AnnotationKey>("/api/v1/annotation-keys", { method: "POST", body: JSON.stringify(body) });
}

export async function updateAnnotationKey(id: number, body: AnnotationKeyBody): Promise<void> {
  await voidRequest(`/api/v1/annotation-keys/${id}`, { method: "PUT", body: JSON.stringify(body) });
}

export async function deleteAnnotationKey(id: number): Promise<void> {
  await voidRequest(`/api/v1/annotation-keys/${id}`, { method: "DELETE" });
}
