// The Blueprints/Entities pages' client-side JSON export — the round-trip counterpart of
// `utils/ontologyImport.ts`: the shape produced here is exactly what `parseOntologySources`
// accepts back unchanged (zero stripped keys), by construction — every field it emits is one
// `sanitizeDocument`/`stripComputedProperties` would otherwise have dropped from a Port export.
// Since 2.8.0 the Entities page exports ONE entity at a time (its row's Operations menu) as a
// BARE document — exactly Port's `POST /v1/blueprints/{id}/entities` create-entity body plus
// `blueprint` — rather than the whole-blueprint `{entities: [...]}` envelope this module used
// to emit; a bare object is one of `utils/ontologyImport.ts#unwrapEnvelope`'s accepted shapes.

import type { Blueprint } from "../api/blueprints";
import type { Entity } from "../api/entities";
import { computedPropertyIds } from "./computedProperties";
import { downloadTextFile } from "./download";

/** One blueprint as an importable document: identity + the Port document fields, in the
 *  order `sample-data/port/commerce-payments/blueprints/*.json` uses. `undefined` members are omitted by
 *  `JSON.stringify` — never written as an explicit `null`. */
export function blueprintExportDocument(bp: Blueprint): Record<string, unknown> {
  const out: Record<string, unknown> = { identifier: bp.identifier, title: bp.title };
  if (bp.description != null) out.description = bp.description;
  if (bp.icon != null) out.icon = bp.icon;
  out.schema = bp.schema;
  out.relations = bp.relations;
  out.mirrorProperties = bp.mirrorProperties;
  out.calculationProperties = bp.calculationProperties;
  out.aggregationProperties = bp.aggregationProperties;
  if (bp.ownership !== undefined) out.ownership = bp.ownership;
  if (bp.hierarchyRelations && Object.keys(bp.hierarchyRelations).length > 0) {
    out.hierarchyRelations = bp.hierarchyRelations;
  }
  return out;
}

/** One entity as an importable document: `team` is omitted for an Inherited-ownership
 *  blueprint (the server rejects it there, `TEAM_NOT_ALLOWED`) and every computed
 *  (mirror/calculation/aggregation) property id is dropped from `properties` — an entity GET
 *  response carries their EVALUATED values, which the server refuses to accept back. */
export function entityExportDocument(entity: Entity, blueprint: Blueprint): Record<string, unknown> {
  const out: Record<string, unknown> = {
    blueprint: entity.blueprint,
    identifier: entity.identifier,
    title: entity.title,
  };
  if (entity.icon !== undefined) out.icon = entity.icon;
  if (blueprint.ownership?.type !== "Inherited") out.team = entity.team;

  const computedIds = computedPropertyIds(blueprint);
  const properties: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(entity.properties)) {
    if (!computedIds.has(key)) properties[key] = value;
  }
  out.properties = properties;
  out.relations = entity.relations;
  return out;
}

export function blueprintsExportJson(blueprints: readonly Blueprint[]): string {
  return JSON.stringify({ blueprints: blueprints.map(blueprintExportDocument) }, null, 2);
}

/** ONE entity as a bare importable document — Port's single create-entity body, no envelope. */
export function entityExportJson(entity: Entity, blueprint: Blueprint): string {
  return JSON.stringify(entityExportDocument(entity, blueprint), null, 2);
}

/** `toadie-entity-<blueprint>-<identifier>.json`, with every character outside the safe
 *  filename charset (`[A-Za-z0-9._-]`) replaced by `_` in BOTH halves — the blueprint
 *  identifier grammar also allows `@ : / =`, the entity identifier grammar `@ + : \ / = '`. */
export function entityExportFileName(entity: Entity): string {
  return `toadie-entity-${fileNameSafe(entity.blueprint)}-${fileNameSafe(entity.identifier)}.json`;
}

function fileNameSafe(value: string): string {
  return value.replace(/[^A-Za-z0-9._-]/g, "_");
}

export function downloadJson(text: string, filename: string) {
  downloadTextFile(text, filename, "application/json");
}
