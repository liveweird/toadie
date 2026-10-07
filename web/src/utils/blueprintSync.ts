// Pure machinery behind blueprint source-sync (2.10.0) — the `entities/` counterpart one level
// up: canonical JSON rendering for a stable side-by-side comparison, and picking the ONE
// document out of a fetched/pasted source that matches a given blueprint, reusing the
// ontology-import sanitizing machinery so a source document round-trips the same way an
// ontology import batch does. See `.claude/docs/persistence.md` "Blueprint source references
// (V38)" and `web/CLAUDE.md` "Blueprint source & sync".

import type { Blueprint } from "../api/blueprints";
import { canonicalDocumentJson } from "./canonicalJson";
import { sanitizeDocument, unwrapEnvelope } from "./ontologyImport";

/** The blueprint a source document is being compared/synced against. */
export type BlueprintSyncTarget = {
  id: number;
  identifier: string;
  sourceUrl: string | null;
  updatedAt: number;
  lastSyncedAt: number;
  system: boolean;
};

/** The `SyncBlueprintModal` target, straight off a list row or a loaded detail — the
 *  `utils/entitySync.ts#toSyncTarget` construction idiom, one level over. */
export function toBlueprintSyncTarget(blueprint: Blueprint): BlueprintSyncTarget {
  return {
    id: blueprint.id,
    identifier: blueprint.identifier,
    sourceUrl: blueprint.sourceUrl ?? null,
    updatedAt: blueprint.updatedAt,
    lastSyncedAt: blueprint.lastSyncedAt,
    system: blueprint.system,
  };
}

/** `utils/ontologyExport.ts#blueprintExportDocument`'s own field order — the export and the
 *  sync comparison render the SAME document the SAME way. */
const BLUEPRINT_TOP_LEVEL_KEY_ORDER = [
  "identifier",
  "title",
  "description",
  "icon",
  "schema",
  "relations",
  "mirrorProperties",
  "calculationProperties",
  "aggregationProperties",
  "ownership",
  "hierarchyRelations",
  "tiers",
] as const;

/** Renders a blueprint document with a stable key order for comparison — see
 *  `utils/canonicalJson.ts#canonicalDocumentJson` for the shared mechanism. */
export function canonicalBlueprintDocumentJson(doc: Record<string, unknown>): string {
  return canonicalDocumentJson(doc, BLUEPRINT_TOP_LEVEL_KEY_ORDER);
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/**
 * Optional TOP-LEVEL blueprint fields whose "unset" spelling is an explicit `null` on the wire
 * rather than absence — the `utils/entitySync.ts#NULLABLE_TOP_LEVEL_KEYS` idiom, over
 * `BlueprintRequest`'s own two nullable identity fields.
 */
const NULLABLE_TOP_LEVEL_KEYS = ["description", "icon"] as const;

/** Drops the keys above when their value is `null` — applied once, right after sanitizing a
 *  picked source document and before it is compared (canonicalized) or sent back on sync. */
function dropNullTopLevelKeys(doc: Record<string, unknown>): Record<string, unknown> {
  let result = doc;
  for (const key of NULLABLE_TOP_LEVEL_KEYS) {
    if (result[key] === null) {
      if (result === doc) result = { ...doc };
      delete result[key];
    }
  }
  return result;
}

function matchesIdentifier(body: Record<string, unknown>, identifier: string): boolean {
  const candidateIdentifier = typeof body.identifier === "string" ? body.identifier : undefined;
  return candidateIdentifier?.toLowerCase() === identifier.toLowerCase();
}

function hasNonEmptyHierarchyRelations(body: Record<string, unknown>): boolean {
  const map = body.hierarchyRelations;
  return isPlainObject(map) && Object.keys(map).length > 0;
}

type StoredTiers = {
  blueprint?: number;
  properties?: Record<string, number>;
  relations?: Record<string, number>;
};

function hasNonEmptyTiers(body: Record<string, unknown>): boolean {
  const tiers = body.tiers;
  if (!isPlainObject(tiers)) return false;
  return ["blueprint", "properties", "relations"].some((member) => {
    const value = tiers[member];
    return isPlainObject(value) ? Object.keys(value).length > 0 : value != null;
  });
}

/**
 * Puts a picked document's `tiers` in the server's stored shape before it is compared or sent: the
 * missing `properties`/`relations` maps become `{}` (the wire always carries all three members,
 * so a partial remote `{"properties":{"a":4}}` is in sync once stored), and an all-empty object
 * is dropped (the server folds it to "no tiers"). A non-object value is left for the server to refuse.
 */
function normalizeTiers(doc: Record<string, unknown>): Record<string, unknown> {
  const tiers = doc.tiers;
  if (!isPlainObject(tiers)) return doc;
  const properties = isPlainObject(tiers.properties) ? tiers.properties : {};
  const relations = isPlainObject(tiers.relations) ? tiers.relations : {};
  const rest = { ...doc };
  delete rest.tiers;
  if (tiers.blueprint == null && Object.keys(properties).length === 0 && Object.keys(relations).length === 0) {
    return rest;
  }
  return { ...rest, tiers: { ...tiers, properties, relations } };
}

function pickKeys(map: Record<string, number> | undefined, source: unknown): Record<string, number> {
  const keys = isPlainObject(source) ? new Set(Object.keys(source)) : new Set<string>();
  return Object.fromEntries(Object.entries(map ?? {}).filter(([key]) => keys.has(key)));
}

/** The stored tiers pruned to the picked document's own property/relation keys — the server's
 *  `BlueprintTiers.prunedTo`, mirrored so the preview shows what a sync would actually keep.
 *  `undefined` when nothing is left. */
function prunedStoredTiers(stored: StoredTiers | undefined, body: Record<string, unknown>): StoredTiers | undefined {
  if (!stored) return undefined;
  const schema = isPlainObject(body.schema) ? body.schema : {};
  const properties = pickKeys(stored.properties, schema.properties);
  const relations = pickKeys(stored.relations, body.relations);
  if (stored.blueprint == null && Object.keys(properties).length === 0 && Object.keys(relations).length === 0) {
    return undefined;
  }
  return { blueprint: stored.blueprint, properties, relations };
}

type PickedSourceDocumentError = "parse" | "noMatch";

export type PickedSourceBlueprintDocument = {
  document: Record<string, unknown> | null;
  error: PickedSourceDocumentError | null;
  /**
   * True when the picked document carried no non-empty `hierarchyRelations` map and the
   * blueprint's CURRENTLY STORED map was copied onto it before comparison/sync — mirrors the
   * server's own keep-when-absent merge (`syncFromSource`, `.claude/docs/persistence.md`
   * "Blueprint source references (V38)") so the diff shows the map unchanged rather than
   * "removed", and the confirm never submits a document that would silently clear it.
   */
  hierarchyKept: boolean;
  /**
   * The `tiers` twin of [hierarchyKept] (2.18.0): the picked document carried no tiers, so the
   * stored ones — PRUNED to the picked document's own property/relation keys, never refused —
   * were copied onto it, exactly as the server's `syncFromSource` does.
   */
  tiersKept: boolean;
};

function failure(error: PickedSourceDocumentError): PickedSourceBlueprintDocument {
  return { document: null, error, hierarchyKept: false, tiersKept: false };
}

/**
 * Parses a source's JSON text and picks the ONE document that matches `target` — a single
 * document is taken as-is (permitting a source-side identifier rename, the
 * `pickSourceEntityDocument` idiom), several documents are matched by identifier
 * (case-insensitively; `noMatch` otherwise, zero candidates included). The picked document is
 * then run through the SAME sanitize step an ontology import batch uses
 * (`utils/ontologyImport.ts#sanitizeDocument`), so a Port API export or a Toadie export
 * round-trips identically whether it arrives through `/ontology/import` or through this sync
 * flow — blueprints have no computed properties to strip, unlike the entity twin.
 *
 * `hierarchyRelations` is a Toadie-only extension most blueprint exports never carry at all:
 * when the picked document has no non-empty map, `stored.hierarchyRelations` (the CURRENTLY
 * SAVED one) is copied onto it before the document is returned, so a sync can never silently
 * clear it. `tiers` (2.18.0) follows the same keep-when-absent rule, except that the carried-over
 * stored tiers are pruned to the picked document's own property/relation keys.
 */
export function pickSourceBlueprintDocument(
  text: string,
  target: { identifier: string },
  stored: { hierarchyRelations?: Record<string, string>; tiers?: StoredTiers },
): PickedSourceBlueprintDocument {
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    return failure("parse");
  }

  const candidates = unwrapEnvelope(parsed).filter(isPlainObject);
  if (candidates.length === 0) return failure("noMatch");

  let picked: Record<string, unknown>;
  if (candidates.length === 1) {
    picked = sanitizeDocument(candidates[0], "blueprint").body;
  } else {
    const sanitized = candidates.map((candidate) => sanitizeDocument(candidate, "blueprint").body);
    const match = sanitized.find((body) => matchesIdentifier(body, target.identifier));
    if (!match) return failure("noMatch");
    picked = match;
  }

  const dropped = normalizeTiers(dropNullTopLevelKeys(picked));
  const storedHierarchyRelations = stored.hierarchyRelations ?? {};
  const hierarchyKept = !hasNonEmptyHierarchyRelations(dropped) && Object.keys(storedHierarchyRelations).length > 0;
  const withHierarchy = hierarchyKept ? { ...dropped, hierarchyRelations: storedHierarchyRelations } : dropped;
  const keptTiers = hasNonEmptyTiers(withHierarchy) ? undefined : prunedStoredTiers(stored.tiers, withHierarchy);
  const document = keptTiers ? { ...withHierarchy, tiers: keptTiers } : withHierarchy;

  return { document, error: null, hierarchyKept, tiersKept: keptTiers != null };
}
