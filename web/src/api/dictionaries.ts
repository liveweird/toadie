// Dictionaries API — admin-curated ordered value lists (namespaces is the only one today).
// Thin endpoint wrappers: transport (authedFetch/ApiError) in ./http, types from ./schema.

import { jsonRequest, voidRequest } from "./http";
import type { paths } from "./schema";

export type DictionaryEntry =
  paths["/api/v1/dictionaries/{dictionary}"]["get"]["responses"]["200"]["content"]["application/json"]["items"][number];
export type DictionaryUpdateBody =
  paths["/api/v1/dictionaries/{dictionary}"]["put"]["requestBody"]["content"]["application/json"];
type DictionaryEntryList =
  paths["/api/v1/dictionaries/{dictionary}"]["get"]["responses"]["200"]["content"]["application/json"];

/** The dictionary URL slugs — grows with the server's `Dictionary` enum. */
export type DictionarySlug = "namespaces";

export async function getDictionary(slug: DictionarySlug): Promise<DictionaryEntry[]> {
  return (await jsonRequest<DictionaryEntryList>(`/api/v1/dictionaries/${slug}`)).items;
}

export async function updateDictionary(slug: DictionarySlug, body: DictionaryUpdateBody): Promise<void> {
  await voidRequest(`/api/v1/dictionaries/${slug}`, { method: "PUT", body: JSON.stringify(body) });
}
