// Backstage <-> Port conversion mappings (conversion phases 1, 2 and 4 — the registry plus the
// two pure conversion endpoints; rules in `.claude/docs/conversion-mapping.md`). ADMIN-only
// writes, any-authenticated reads. Thin endpoint wrappers: transport in ./http, types from ./schema.

import { ApiError, buildQuery, jsonRequest, voidRequest } from "./http";
import type { paths } from "./schema";

type MappingsPath = paths["/api/v1/conversion-mappings"];
export type ConversionMapping = MappingsPath["get"]["responses"]["200"]["content"]["application/json"]["items"][number];
export type ConversionMappingBody = MappingsPath["post"]["requestBody"]["content"]["application/json"];
export type MappingFinding = ConversionMapping["findings"][number];
export type MappingDrift = ConversionMapping["drift"][number];
type MappingList = MappingsPath["get"]["responses"]["200"]["content"]["application/json"];

export async function listConversionMappings(): Promise<ConversionMapping[]> {
  return (await jsonRequest<MappingList>("/api/v1/conversion-mappings")).items;
}

export async function getConversionMapping(id: number): Promise<ConversionMapping> {
  return jsonRequest<ConversionMapping>(`/api/v1/conversion-mappings/${id}`);
}

export async function createConversionMapping(body: ConversionMappingBody): Promise<ConversionMapping> {
  return jsonRequest<ConversionMapping>("/api/v1/conversion-mappings", { method: "POST", body: JSON.stringify(body) });
}

export async function replaceConversionMapping(id: number, body: ConversionMappingBody): Promise<void> {
  await voidRequest(`/api/v1/conversion-mappings/${id}`, { method: "PUT", body: JSON.stringify(body) });
}

export async function deleteConversionMapping(id: number): Promise<void> {
  await voidRequest(`/api/v1/conversion-mappings/${id}`, { method: "DELETE" });
}

/**
 * The positioned `findings` of a refused create/replace (`ConversionMappingInvalid` 400), read
 * defensively off the error body (the `entitySaveFindings` idiom): anything that is not a 400
 * carrying a `findings` array — a size cap, a malformed body, a network error — answers an
 * empty list and the caller falls back to its generic message.
 */
export function mappingSaveFindings(err: unknown): MappingFinding[] {
  if (!(err instanceof ApiError) || err.status !== 400) return [];
  const body = err.body as { findings?: unknown } | null;
  return Array.isArray(body?.findings) ? (body.findings as MappingFinding[]) : [];
}

// -- Port -> Backstage (phase 2) ---------------------------------------------------------

type CatalogDocumentsPath = paths["/api/v1/conversion-mappings/{id}/catalog-documents"];
export type CatalogDocumentsResponse = CatalogDocumentsPath["get"]["responses"]["200"]["content"]["application/json"];
export type ConversionRow = CatalogDocumentsResponse["rows"][number];
export type ConversionFinding = ConversionRow["findings"][number];

/** The entity selection — the Entity graph's filter set plus `entityId`; every member optional. */
export type CatalogDocumentsSelection = {
  blueprints?: readonly string[];
  q?: string;
  team?: string;
  query?: string;
  entityId?: number;
  merge: boolean;
};

/** Convert the selected stored entities into catalog documents (a PURE read: nothing is stored). */
export async function getCatalogDocuments(
  mappingId: number,
  selection: CatalogDocumentsSelection,
): Promise<CatalogDocumentsResponse> {
  const query = buildQuery({
    blueprint: selection.blueprints,
    q: selection.q,
    team: selection.team,
    query: selection.query,
    entityId: selection.entityId,
    merge: selection.merge,
  });
  return jsonRequest<CatalogDocumentsResponse>(`/api/v1/conversion-mappings/${mappingId}/catalog-documents?${query}`);
}

// -- Backstage -> Port (phase 4) ---------------------------------------------------------

type EntityDocumentsPath = paths["/api/v1/conversion-mappings/{id}/entity-documents"];
export type EntityDocumentsResponse = EntityDocumentsPath["post"]["responses"]["200"]["content"]["application/json"];
export type EntityDocumentRow = EntityDocumentsResponse["rows"][number];
export type EntityDocumentsBody = EntityDocumentsPath["post"]["requestBody"]["content"]["application/json"];

/** Convert up to 200 Backstage documents into Port entity documents (a pure computation). */
export async function convertToEntityDocuments(
  mappingId: number,
  body: EntityDocumentsBody,
): Promise<EntityDocumentsResponse> {
  return jsonRequest<EntityDocumentsResponse>(`/api/v1/conversion-mappings/${mappingId}/entity-documents`, {
    method: "POST",
    body: JSON.stringify(body),
  });
}
