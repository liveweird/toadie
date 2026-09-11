// The Import ontology page's pure machinery (`pages/ImportOntology.tsx`) — parsing pasted/
// uploaded JSON into a batch, sanitizing Port-export/Toadie-export noise out of it, and
// orchestrating the two chunked import endpoints. Kept out of the page for sonarjs (the
// complexity backstops) and so the parsing/sanitizing rules carry their own focused tests.
//
// The server endpoints (`POST /api/v1/blueprints/import(/check)` and the entity twins) are
// called through INJECTED functions (`RunImportBatchDeps`) rather than imported directly —
// this module has no dependency on the generated schema types those wrappers use.

import { ApiError } from "../api/http";
import type { Blueprint } from "../api/blueprints";
import type { EntityFinding } from "../api/entities";
import { computedPropertyIds } from "./computedProperties";

/** The server's per-request document cap (`MAX_IMPORT_DOCUMENTS` — `.claude/docs/port-data-model.md`
 *  "Import and export"), applied independently to the blueprint half and the entity half of a
 *  batch: a 201-document mix of both kinds still fits in one request each. */
export const IMPORT_CHUNK_SIZE = 200;

type OntologyDocumentKind = "blueprint" | "entity";

export type ImportSource = { label: string; text: string };

type ParseError = { source: string; index?: number; message: string };

/** One document out of the parsed batch, already stripped of read-only/computed noise. */
export type OntologyDocument = {
  /** Position across the WHOLE batch (every source, in source-then-in-source-order) — the id
   *  result rows are matched back to after chunked, reordered server calls. */
  index: number;
  kind: OntologyDocumentKind;
  identifier?: string;
  /** The entity's owning blueprint identifier (entity documents only). */
  blueprint?: string;
  body: Record<string, unknown>;
  /** Read-only keys dropped by `sanitizeDocument` (envelope/response-only fields). */
  stripped: string[];
  /** Computed (mirror/calculation/aggregation) property ids dropped from `properties`. */
  strippedComputed: string[];
  /** The source's label — the file name, or the pasted-text placeholder. */
  source: string;
};

type ParseOntologyResult = {
  documents: OntologyDocument[];
  errors: ParseError[];
};

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/**
 * Flattens one parsed JSON value into a list of candidate documents: a bare object is itself
 * one document, a bare array is its items, and the Port/Toadie export envelope shapes
 * (`{blueprints, entities}`, `{ok, blueprint}`, `{ok, blueprints}`, …) unwrap to their payload
 * — `ok` is never inspected, just ignored. Anything else (a bare scalar) passes through
 * unchanged so the caller can report it as "not an object" at its position.
 *
 * The `blueprint`/`blueprints`/`entities` envelope keys are checked by SHAPE, not mere
 * presence: an entity DOCUMENT itself always carries a top-level string `blueprint` member
 * (naming its owning blueprint) — that must never be mistaken for the single-envelope
 * `{ok, blueprint: {...}}` shape, whose `blueprint` is a NESTED OBJECT.
 */
export function unwrapEnvelope(value: unknown): unknown[] {
  if (Array.isArray(value)) return value;
  if (!isPlainObject(value)) return [value];
  if (Array.isArray(value.blueprints) || Array.isArray(value.entities)) {
    const blueprints = Array.isArray(value.blueprints) ? value.blueprints : [];
    const entities = Array.isArray(value.entities) ? value.entities : [];
    return [...blueprints, ...entities];
  }
  if (isPlainObject(value.blueprint)) return [value.blueprint];
  if (isPlainObject(value.entity)) return [value.entity];
  return [value];
}

/** A document naming its own owning blueprint (a string `blueprint` member) is an entity;
 *  everything else is treated as a blueprint document. */
export function detectKind(obj: Record<string, unknown>): OntologyDocumentKind {
  return typeof obj.blueprint === "string" ? "entity" : "blueprint";
}

const COMMON_STRIP_KEYS = [
  "id",
  "organization",
  "createdAt",
  "createdBy",
  "updatedAt",
  "updatedBy",
  "system",
  "creatorName",
  "creatorDeleted",
  "findings",
  "blueprintId",
  "scorecards",
] as const;

/** Port's own definition-export additionally carries these two blueprint-only keys. */
const BLUEPRINT_ONLY_STRIP_KEYS = ["teamInheritance", "changelogDestination"] as const;

/** Drops response-only/read-only keys a Port API export or a Toadie export never needs on the
 *  way back in: the common envelope fields, every `_`-prefixed key (Port's own convention for
 *  internal metadata), and — blueprints only — the two Port-specific team/changelog settings
 *  Toadie does not model. */
export function sanitizeDocument(
  obj: Record<string, unknown>,
  kind: OntologyDocumentKind,
): { body: Record<string, unknown>; stripped: string[] } {
  const dropKeys = new Set<string>(COMMON_STRIP_KEYS);
  if (kind === "blueprint") {
    for (const key of BLUEPRINT_ONLY_STRIP_KEYS) dropKeys.add(key);
  }
  const body: Record<string, unknown> = {};
  const stripped: string[] = [];
  for (const [key, value] of Object.entries(obj)) {
    if (dropKeys.has(key) || key.startsWith("_")) {
      stripped.push(key);
      continue;
    }
    body[key] = value;
  }
  return { body, stripped };
}

/** Drops mirror/calculation/aggregation property VALUES from an entity body's `properties` —
 *  the server rejects a create/update carrying one back (`COMPUTED_PROPERTY`), so importing an
 *  export that round-tripped them would otherwise always fail. */
export function stripComputedProperties(
  entityBody: Record<string, unknown>,
  computedIdsByBlueprint: Record<string, ReadonlySet<string>>,
): { body: Record<string, unknown>; strippedComputed: string[] } {
  const blueprintId = typeof entityBody.blueprint === "string" ? entityBody.blueprint : undefined;
  const computedIds = blueprintId ? computedIdsByBlueprint[blueprintId] : undefined;
  const properties = entityBody.properties;
  if (!computedIds || computedIds.size === 0 || !isPlainObject(properties)) {
    return { body: entityBody, strippedComputed: [] };
  }
  const nextProperties: Record<string, unknown> = {};
  const strippedComputed: string[] = [];
  for (const [key, value] of Object.entries(properties)) {
    if (computedIds.has(key)) {
      strippedComputed.push(key);
      continue;
    }
    nextProperties[key] = value;
  }
  if (strippedComputed.length === 0) return { body: entityBody, strippedComputed: [] };
  return { body: { ...entityBody, properties: nextProperties }, strippedComputed };
}

function rawDefinitionIds(body: Record<string, unknown>): Set<string> {
  const ids = new Set<string>();
  for (const key of ["mirrorProperties", "calculationProperties", "aggregationProperties"] as const) {
    const map = body[key];
    if (isPlainObject(map)) {
      for (const id of Object.keys(map)) ids.add(id);
    }
  }
  return ids;
}

/** The computed-property id set an entity document's `blueprint` might target: read from the
 *  SAME batch's own blueprint documents (a raw reader over the three definition maps — the
 *  batch document is not a typed `Blueprint`, just parsed JSON) merged with the already-stored
 *  registry blueprints (`computedPropertyIds`) for identifiers the batch doesn't redefine. */
export function buildComputedIdsByBlueprint(
  documents: readonly OntologyDocument[],
  registryBlueprints: readonly Blueprint[],
): Record<string, Set<string>> {
  const result: Record<string, Set<string>> = {};
  for (const doc of documents) {
    if (doc.kind === "blueprint" && doc.identifier) {
      result[doc.identifier] = rawDefinitionIds(doc.body);
    }
  }
  for (const blueprint of registryBlueprints) {
    const registryIds = computedPropertyIds(blueprint);
    const existing = result[blueprint.identifier];
    result[blueprint.identifier] = existing ? new Set([...existing, ...registryIds]) : registryIds;
  }
  return result;
}

function documentIdentity(kind: OntologyDocumentKind, body: Record<string, unknown>) {
  const identifier = typeof body.identifier === "string" ? body.identifier : undefined;
  const blueprint = kind === "entity" && typeof body.blueprint === "string" ? body.blueprint : undefined;
  return { identifier, blueprint };
}

/**
 * Parses every source's JSON, unwraps its envelope, and sanitizes each resulting document — a
 * JSON syntax error is ONE error naming the source; a non-object item names its position within
 * that source. `registryBlueprints` (optional — pass `[]`, or omit, before the registry has
 * loaded) extends the computed-property strip beyond blueprints the batch redefines itself.
 */
export function parseOntologySources(
  sources: readonly ImportSource[],
  registryBlueprints: readonly Blueprint[] = [],
): ParseOntologyResult {
  const errors: ParseError[] = [];
  const partial: Array<Omit<OntologyDocument, "index">> = [];

  for (const src of sources) {
    if (src.text.trim() === "") continue;
    let parsed: unknown;
    try {
      parsed = JSON.parse(src.text);
    } catch (err) {
      errors.push({ source: src.label, message: `Invalid JSON: ${(err as Error).message}` });
      continue;
    }
    unwrapEnvelope(parsed).forEach((item, itemIndex) => {
      if (!isPlainObject(item)) {
        errors.push({ source: src.label, index: itemIndex, message: `Document ${itemIndex + 1} is not an object` });
        return;
      }
      const kind = detectKind(item);
      const { body, stripped } = sanitizeDocument(item, kind);
      partial.push({ kind, ...documentIdentity(kind, body), body, stripped, strippedComputed: [], source: src.label });
    });
  }

  const withIndex: OntologyDocument[] = partial.map((doc, index) => ({ ...doc, index }));
  const computedIds = buildComputedIdsByBlueprint(withIndex, registryBlueprints);
  const documents = withIndex.map((doc) => {
    if (doc.kind !== "entity") return doc;
    const { body, strippedComputed } = stripComputedProperties(doc.body, computedIds);
    return strippedComputed.length > 0 ? { ...doc, body, strippedComputed } : doc;
  });

  return { documents, errors };
}

/** Splits an array into fixed-size chunks (the last one possibly shorter) — the batch's own
 *  200-document request cap, applied independently to the blueprint half and the entity half. */
export function chunked<T>(items: readonly T[], size: number): T[][] {
  const chunks: T[][] = [];
  for (let i = 0; i < items.length; i += size) {
    chunks.push(items.slice(i, i + size));
  }
  return chunks;
}

type StrippedSummary = {
  strippedCount: number;
  strippedKeys: string[];
  strippedComputedCount: number;
  strippedComputedKeys: string[];
};

/** Aggregates `parseOntologySources`' per-document strip lists into the note the page shows
 *  ("Ignored read-only keys in N documents: …" / "Computed property values removed from N
 *  entities: …") — distinct key names, sorted for a stable rendering. */
export function summarizeStripped(documents: readonly OntologyDocument[]): StrippedSummary {
  const strippedKeys = new Set<string>();
  const strippedComputedKeys = new Set<string>();
  let strippedCount = 0;
  let strippedComputedCount = 0;
  for (const doc of documents) {
    if (doc.stripped.length > 0) {
      strippedCount += 1;
      for (const key of doc.stripped) strippedKeys.add(key);
    }
    if (doc.strippedComputed.length > 0) {
      strippedComputedCount += 1;
      for (const key of doc.strippedComputed) strippedComputedKeys.add(key);
    }
  }
  return {
    strippedCount,
    strippedKeys: [...strippedKeys].sort(),
    strippedComputedCount,
    strippedComputedKeys: [...strippedComputedKeys].sort(),
  };
}

// -- The orchestrator ---------------------------------------------------------------------

/** The row statuses the server reports, plus the one CLIENT-only status: a blueprint document
 *  skipped because the caller isn't (or is no longer) an admin — never sent to the server at
 *  all, so it carries no server message. */
export type OntologyRowStatus = "CREATED" | "UPDATED" | "EXISTS" | "INVALID" | "CONFLICT" | "ERROR" | "FORBIDDEN";

export type OntologyResultRow = {
  index: number;
  kind: OntologyDocumentKind;
  identifier?: string;
  blueprint?: string;
  source: string;
  status: OntologyRowStatus;
  id?: number;
  message?: string;
  findings?: EntityFinding[];
};

type ServerRowStatus = Exclude<OntologyRowStatus, "FORBIDDEN">;

type BlueprintRowResult = {
  index: number;
  identifier?: string;
  status: ServerRowStatus;
  id?: number;
  message?: string;
};

type EntityRowResult = {
  index: number;
  blueprint?: string;
  identifier?: string;
  status: ServerRowStatus;
  id?: number;
  message?: string;
  findings?: EntityFinding[];
};

/** Injected caller for one chunk (≤200 documents) of either import endpoint — `check` and
 *  `import` share this shape, since both `.../import` and `.../import/check` take the same
 *  request body and answer the same row shape. */
type BlueprintImportCaller = (
  documents: Record<string, unknown>[],
  replaceExisting: boolean,
) => Promise<{ results: BlueprintRowResult[] }>;

type EntityImportCaller = (
  documents: Record<string, unknown>[],
  replaceExisting: boolean,
) => Promise<{ results: EntityRowResult[] }>;

type RunImportBatchDeps = {
  documents: readonly OntologyDocument[];
  replaceExisting: boolean;
  /** Whether the CALLER believes it is an admin (`isAdmin()`) — blueprint documents are
   *  skipped client-side (never sent) when false, so a non-admin session imports its
   *  entities without a gratuitous 403/`authz.denied` per blueprint chunk. */
  admin: boolean;
  importBlueprintsChunk: BlueprintImportCaller;
  importEntitiesChunk: EntityImportCaller;
  onProgress?: (sentChunks: number, totalChunks: number) => void;
  /** Called with the FULL accumulated row set after every completed chunk, so the page can
   *  show partial results even if a LATER chunk's call rejects (the final promise then
   *  rejects too, but the caller's last-seen rows via this callback survive). */
  onRows?: (rows: OntologyResultRow[]) => void;
};

function forbiddenRow(doc: OntologyDocument): OntologyResultRow {
  return { index: doc.index, kind: doc.kind, identifier: doc.identifier, blueprint: doc.blueprint, source: doc.source, status: "FORBIDDEN" };
}

function blueprintRow(doc: OntologyDocument, result: BlueprintRowResult): OntologyResultRow {
  return {
    index: doc.index,
    kind: "blueprint",
    identifier: result.identifier ?? doc.identifier,
    source: doc.source,
    status: result.status,
    id: result.id,
    message: result.message,
  };
}

function entityRow(doc: OntologyDocument, result: EntityRowResult): OntologyResultRow {
  return {
    index: doc.index,
    kind: "entity",
    identifier: result.identifier ?? doc.identifier,
    blueprint: result.blueprint ?? doc.blueprint,
    source: doc.source,
    status: result.status,
    id: result.id,
    message: result.message,
    findings: result.findings,
  };
}

function isForbidden(err: unknown): boolean {
  return err instanceof ApiError && err.status === 403;
}

async function runOneChunk<TResult extends { index: number }>(
  chunk: OntologyDocument[],
  caller: (documents: Record<string, unknown>[], replaceExisting: boolean) => Promise<{ results: TResult[] }>,
  replaceExisting: boolean,
  mapRow: (doc: OntologyDocument, result: TResult) => OntologyResultRow,
): Promise<OntologyResultRow[]> {
  try {
    const response = await caller(
      chunk.map((doc) => doc.body),
      replaceExisting,
    );
    return response.results.map((result) => mapRow(chunk[result.index], result));
  } catch (err) {
    // A role change mid-session (or a client that mis-detected admin status) surfaces as a
    // real 403 here — maps onto the same client-only FORBIDDEN rows the pre-flight skip uses.
    if (!isForbidden(err)) throw err;
    return chunk.map(forbiddenRow);
  }
}

function sortedByIndex(rows: readonly OntologyResultRow[]): OntologyResultRow[] {
  return [...rows].sort((a, b) => a.index - b.index);
}

/**
 * Runs the whole batch: blueprint chunks (skipped client-side when not admin) first, then
 * entity chunks — the server-side ordering the plan's dependency rule assumes (an entity's
 * `blueprint` reference is far more likely to land in the SAME batch than a blueprint's own
 * relation cycle, but either half tolerates the other running second). Kept out of
 * `pages/ImportOntology.tsx` so the page stays under the sonarjs complexity backstops.
 */
export async function runImportBatch(deps: RunImportBatchDeps): Promise<OntologyResultRow[]> {
  const blueprintDocs = deps.documents.filter((doc) => doc.kind === "blueprint");
  const entityDocs = deps.documents.filter((doc) => doc.kind === "entity");
  const totalChunks = chunked(blueprintDocs, IMPORT_CHUNK_SIZE).length + chunked(entityDocs, IMPORT_CHUNK_SIZE).length;
  let sentChunks = 0;
  const rows: OntologyResultRow[] = [];

  const reportChunk = (newRows: OntologyResultRow[]) => {
    rows.push(...newRows);
    sentChunks += 1;
    deps.onProgress?.(sentChunks, totalChunks);
    deps.onRows?.(sortedByIndex(rows));
  };

  for (const chunk of chunked(blueprintDocs, IMPORT_CHUNK_SIZE)) {
    if (!deps.admin) {
      reportChunk(chunk.map(forbiddenRow));
    } else {
      reportChunk(await runOneChunk(chunk, deps.importBlueprintsChunk, deps.replaceExisting, blueprintRow));
    }
  }

  for (const chunk of chunked(entityDocs, IMPORT_CHUNK_SIZE)) {
    reportChunk(await runOneChunk(chunk, deps.importEntitiesChunk, deps.replaceExisting, entityRow));
  }

  return sortedByIndex(rows);
}
