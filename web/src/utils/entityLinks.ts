// The entity link builders — the ONE place the /entities route family is spelled out (the
// catalogFileLinks/blueprintLinks rule). Every surface that links to these screens goes
// through these instead of hand-assembling URLs.

export const entitiesBasePath = "/entities";

/** The list, optionally scoped to a blueprint (`?blueprint=<encoded identifier>`). */
export function entitiesPath(blueprint?: string): string {
  const trimmed = blueprint?.trim();
  return trimmed ? `${entitiesBasePath}?blueprint=${encodeURIComponent(trimmed)}` : entitiesBasePath;
}

export function newEntityPath(blueprint: string): string {
  return `${entitiesBasePath}/new?blueprint=${encodeURIComponent(blueprint.trim())}`;
}

export function editEntityPath(id: number): string {
  return `${entitiesBasePath}/${id}/edit`;
}

// The Entity graph/hierarchy pages (Port migration phase 3, v1.25.0) — leaves after Entities.
export const entityGraphPath = "/entity-graph";
export const entityHierarchyPath = "/entity-hierarchy";

/** The bulk-change page (2.38.0): one blueprint's entities, a field changed across a selection.
 *  Carries the entry surface's blueprint / search / team scope as `?blueprint=&q=&team=`. */
const entityBulkChangeBasePath = `${entitiesBasePath}/bulk-change`;

export function entityBulkChangePath(scope: { blueprint?: string | null; q?: string | null; team?: string | null } = {}): string {
  const params = new URLSearchParams();
  const blueprint = scope.blueprint?.trim();
  const q = scope.q?.trim();
  const team = scope.team?.trim();
  if (blueprint) params.set("blueprint", blueprint);
  if (q) params.set("q", q);
  if (team) params.set("team", team);
  const query = params.toString();
  return query ? `${entityBulkChangeBasePath}?${query}` : entityBulkChangeBasePath;
}
