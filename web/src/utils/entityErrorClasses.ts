import type { EntityErrorsReport } from "../api/entities";
import type { components } from "../api/schema";

type EntityFindingCode = components["schemas"]["EntityFindingCode"];

/**
 * The Port-world Errors report's pill classes (v2.5.0, five since 2.9.1) — over the report's
 * `EntityFindingCode` findings (entity AND blueprint rows share the same code vocabulary) plus
 * one class for every saved-query row, which carries `QueryDiagnostic`s instead: `stale` is the
 * 19 codes every strict entity save already enforces (HARD on the entity's next save); `source`
 * (2.9.1) is the opposite kind of report-only class — an entity's missing `sourceUrl`, never a
 * save blocker, the catalog Errors report's own `source` class one level down; the remaining
 * three are report-only and soft (HARD would be misleading for something that never blocks a
 * save). See `.claude/docs/port-data-model.md` "Computed-property health" and
 * `getEntityErrors`'s own OpenAPI description for the full rule table.
 */
export const ENTITY_ERROR_CLASSES = ["stale", "ownership", "queries", "computed", "source"] as const;

export type EntityErrorClass = (typeof ENTITY_ERROR_CLASSES)[number];

const OWNERSHIP_CODES = new Set<EntityFindingCode>(["OWNERSHIP_UNRESOLVED", "OWNERSHIP_PATH_STALE"]);
const COMPUTED_CODES = new Set<EntityFindingCode>([
  "MIRROR_PATH_STALE",
  "AGGREGATION_PATH_STALE",
  "AGGREGATION_PROPERTY_STALE",
  "CALCULATION_COMPILE_FAILED",
  "CALCULATION_QUARANTINED",
]);

/** Every other `EntityFindingCode` (the 19 existing strict-save codes) is class `stale`. */
export function classOfEntityCode(code: EntityFindingCode): EntityErrorClass {
  if (OWNERSHIP_CODES.has(code)) return "ownership";
  if (COMPUTED_CODES.has(code)) return "computed";
  if (code === "SOURCE_MISSING") return "source";
  return "stale";
}

/**
 * The report's badge colour, the app-wide vocabulary restated: red = HARD on the entity's next
 * save (`stale`), orange = every other report-only class (`ownership`, `queries`, `computed`) —
 * a soft finding that never blocks a save, gray = `source` — an optional reference's absence,
 * not a defect (the catalog Errors report's own `colorOfStatus` vocabulary for `SOURCE_MISSING`).
 */
export function colorOfEntityClass(entityClass: EntityErrorClass): string {
  if (entityClass === "stale") return "red";
  if (entityClass === "source") return "gray";
  return "orange";
}

/**
 * Findings/diagnostics per class over the UNFILTERED report — the counts on the class chips.
 * Entity and blueprint findings are classified by `classOfEntityCode`; every saved-query row's
 * diagnostics count as `queries` regardless of their own `QueryDiagnostic` code (that
 * vocabulary is disjoint from `EntityFindingCode`, and every diagnostic on a reported row is a
 * broken-query defect by definition).
 */
export function countByEntityClass(report: EntityErrorsReport): Record<EntityErrorClass, number> {
  const counts = Object.fromEntries(ENTITY_ERROR_CLASSES.map((c) => [c, 0])) as Record<
    EntityErrorClass,
    number
  >;
  for (const row of report.entities) {
    for (const finding of row.findings) counts[classOfEntityCode(finding.code)] += 1;
  }
  for (const row of report.blueprints) {
    for (const finding of row.findings) counts[classOfEntityCode(finding.code)] += 1;
  }
  for (const row of report.savedQueries) counts.queries += row.diagnostics.length;
  return counts;
}
