// Pure machinery behind entity source-sync (the `entities/` counterpart of the catalog's
// repo-sync — `.claude/docs/testing.md`/`web/CLAUDE.md` "Source references & repo sync"):
// canonical JSON rendering for a stable side-by-side comparison, and picking the ONE document
// out of a fetched/pasted source that matches a given entity, reusing the ontology-import
// sanitizing/computed-property machinery so a source document round-trips the same way an
// ontology import batch does.

import type { Blueprint } from "../api/blueprints";
import type { Entity } from "../api/entities";
import type { SyncStateSource } from "../components/SyncStateText";
import {
  buildComputedIdsByBlueprint,
  sanitizeDocument,
  stripComputedProperties,
  unwrapEnvelope,
} from "./ontologyImport";

/** The entity a source document is being compared/synced against. */
export type EntitySyncTarget = {
  id: number;
  blueprint: string;
  identifier: string;
  sourceUrl: string | null;
  updatedAt: number;
  lastSyncedAt: number;
};

/** The `SyncEntityModal` target, straight off a list row or a loaded detail — the
 *  `SyncCatalogFileModal.tsx` `SyncTarget` construction idiom, one level over. */
export function toSyncTarget(entity: Entity): EntitySyncTarget {
  return {
    id: entity.id,
    blueprint: entity.blueprint,
    identifier: entity.identifier,
    sourceUrl: entity.sourceUrl ?? null,
    updatedAt: entity.updatedAt,
    lastSyncedAt: entity.lastSyncedAt,
  };
}

/** Feeds the shared `SyncStateText`/`entitySync` idiom the same way a catalog file does — the
 *  ONE narrowing both the Entities list's Last-sync column and the editor's header use. */
export function entitySyncSource(entity: {
  sourceUrl?: string;
  lastSyncedAt: number;
  updatedAt: number;
}): SyncStateSource {
  return { sourceUrl: entity.sourceUrl ?? null, lastSyncedAt: entity.lastSyncedAt, updatedAt: entity.updatedAt };
}

const TOP_LEVEL_KEY_ORDER = [
  "blueprint",
  "identifier",
  "title",
  "icon",
  "team",
  "properties",
  "relations",
] as const;

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/** Recursively sorts object keys (alphabetically); arrays keep their element order, but each
 *  element is itself sorted the same way. */
function sortKeysDeep(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(sortKeysDeep);
  if (isPlainObject(value)) {
    const sorted: Record<string, unknown> = {};
    for (const key of Object.keys(value).sort()) {
      sorted[key] = sortKeysDeep(value[key]);
    }
    return sorted;
  }
  return value;
}

/**
 * Renders an entity document with a stable key order for comparison: the top-level identity
 * fields in a fixed, human-meaningful order (only those present), any other top-level key
 * alphabetically after them, and every nested object's keys sorted recursively (arrays keep
 * their element order). Mirrors `utils/catalogYaml.ts`'s canonical-rendering role one level
 * down, in JSON rather than YAML — entities have no YAML form.
 */
export function canonicalEntityDocumentJson(doc: Record<string, unknown>): string {
  const ordered: Record<string, unknown> = {};
  for (const key of TOP_LEVEL_KEY_ORDER) {
    if (key in doc) ordered[key] = sortKeysDeep(doc[key]);
  }
  const orderedKeys = new Set<string>(TOP_LEVEL_KEY_ORDER);
  for (const key of Object.keys(doc).filter((k) => !orderedKeys.has(k)).sort()) {
    ordered[key] = sortKeysDeep(doc[key]);
  }
  return JSON.stringify(ordered, null, 2);
}

export type PickedSourceDocumentError = "parse" | "noMatch" | "blueprintMismatch";

export type PickedSourceDocument = {
  document: Record<string, unknown> | null;
  error: PickedSourceDocumentError | null;
  /** Computed (mirror/calculation/aggregation) property ids stripped from the picked
   *  document's `properties` — empty when nothing was stripped. */
  strippedComputed: string[];
};

function failure(error: PickedSourceDocumentError): PickedSourceDocument {
  return { document: null, error, strippedComputed: [] };
}

function matchesTarget(
  body: Record<string, unknown>,
  target: { blueprint: string; identifier: string },
): boolean {
  const identifier = typeof body.identifier === "string" ? body.identifier : undefined;
  if (identifier?.toLowerCase() !== target.identifier.toLowerCase()) return false;
  const blueprint = typeof body.blueprint === "string" ? body.blueprint : undefined;
  return blueprint === undefined || blueprint === target.blueprint;
}

/**
 * Parses a source's JSON text and picks the ONE document that matches `target` — a single
 * document is taken as-is (permitting a source-side identifier rename; an explicit but
 * DIFFERENT `blueprint` is refused as `blueprintMismatch`, an absent one is filled in from
 * `target`), several documents are matched by identifier (case-insensitively) with an absent
 * or equal `blueprint` (`noMatch` otherwise, zero candidates included). The picked document is
 * then run through the SAME sanitize + computed-property strip an ontology import batch uses
 * (`utils/ontologyImport.ts`), so a Port API export or a Toadie export round-trips identically
 * whether it arrives through `/ontology/import` or through this sync flow.
 */
export function pickSourceEntityDocument(
  text: string,
  target: { blueprint: string; identifier: string },
  registryBlueprints: readonly Blueprint[],
): PickedSourceDocument {
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
    const { body } = sanitizeDocument(candidates[0], "entity");
    const blueprint = typeof body.blueprint === "string" ? body.blueprint : undefined;
    if (blueprint !== undefined && blueprint !== target.blueprint) return failure("blueprintMismatch");
    picked = blueprint === undefined ? { ...body, blueprint: target.blueprint } : body;
  } else {
    const sanitized = candidates.map((candidate) => sanitizeDocument(candidate, "entity").body);
    const match = sanitized.find((body) => matchesTarget(body, target));
    if (!match) return failure("noMatch");
    picked = match.blueprint === undefined ? { ...match, blueprint: target.blueprint } : match;
  }

  const computedIdsByBlueprint = buildComputedIdsByBlueprint([], registryBlueprints);
  const { body, strippedComputed } = stripComputedProperties(picked, computedIdsByBlueprint);
  return { document: body, error: null, strippedComputed };
}
