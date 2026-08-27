// Label registry API — the ADMIN-curated allowed metadata.labels keys, each with a closed
// value list and the entity kinds it applies to. Catalog writes are validated against it
// server-side (strict); the editor consumes the list as its label pickers' source.
// Thin endpoint wrappers: transport (authedFetch/ApiError) in ./http, types from ./schema.

import { jsonRequest, voidRequest } from "./http";
import type { paths } from "./schema";

export type Label = paths["/api/v1/labels"]["get"]["responses"]["200"]["content"]["application/json"]["items"][number];
export type LabelBody = paths["/api/v1/labels"]["post"]["requestBody"]["content"]["application/json"];
type LabelList = paths["/api/v1/labels"]["get"]["responses"]["200"]["content"]["application/json"];

export async function getLabels(): Promise<Label[]> {
  return (await jsonRequest<LabelList>("/api/v1/labels")).items;
}

export async function createLabel(body: LabelBody): Promise<Label> {
  return jsonRequest<Label>("/api/v1/labels", { method: "POST", body: JSON.stringify(body) });
}

export async function updateLabel(id: number, body: LabelBody): Promise<void> {
  await voidRequest(`/api/v1/labels/${id}`, { method: "PUT", body: JSON.stringify(body) });
}

export async function deleteLabel(id: number): Promise<void> {
  await voidRequest(`/api/v1/labels/${id}`, { method: "DELETE" });
}
