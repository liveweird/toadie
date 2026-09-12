// The Blueprints/Entities pages' client-side JSON export — the round-trip counterpart of
// `utils/ontologyImport.ts`: the shape produced here is exactly what `parseOntologySources`
// accepts back unchanged (zero stripped keys), by construction — every field it emits is one
// `sanitizeDocument`/`stripComputedProperties` would otherwise have dropped from a Port export.

import type { Blueprint } from "../api/blueprints";
import type { Entity } from "../api/entities";
import { computedPropertyIds } from "./computedProperties";
import { downloadTextFile } from "./download";

/** One blueprint as an importable document: identity + the Port document fields, in the
 *  order `sample-data/blueprints/*.json` uses. `undefined` members are omitted by
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

export function entitiesExportJson(entities: readonly Entity[], blueprint: Blueprint): string {
  return JSON.stringify({ entities: entities.map((entity) => entityExportDocument(entity, blueprint)) }, null, 2);
}

export function downloadJson(text: string, filename: string) {
  downloadTextFile(text, filename, "application/json");
}
