// Tag-category API — the ADMIN-curated internal categories grouping the allowed
// metadata.tags values, each with the entity kinds its tags apply to. Catalog writes are
// validated against it server-side (strict); the editor consumes the list as its grouped
// tag picker's source. Thin endpoint wrappers: transport in ./http, types from ./schema.

import { jsonRequest, voidRequest } from "./http";
import type { paths } from "./schema";

export type TagCategory =
  paths["/api/v1/tag-categories"]["get"]["responses"]["200"]["content"]["application/json"]["items"][number];
export type TagCategoryBody =
  paths["/api/v1/tag-categories"]["post"]["requestBody"]["content"]["application/json"];
type TagCategoryList = paths["/api/v1/tag-categories"]["get"]["responses"]["200"]["content"]["application/json"];

export async function getTagCategories(): Promise<TagCategory[]> {
  return (await jsonRequest<TagCategoryList>("/api/v1/tag-categories")).items;
}

export async function createTagCategory(body: TagCategoryBody): Promise<TagCategory> {
  return jsonRequest<TagCategory>("/api/v1/tag-categories", { method: "POST", body: JSON.stringify(body) });
}

export async function updateTagCategory(id: number, body: TagCategoryBody): Promise<void> {
  await voidRequest(`/api/v1/tag-categories/${id}`, { method: "PUT", body: JSON.stringify(body) });
}

export async function deleteTagCategory(id: number): Promise<void> {
  await voidRequest(`/api/v1/tag-categories/${id}`, { method: "DELETE" });
}
