import type { Blueprint } from "../api/blueprints";
import type { Entity } from "../api/entities";

/** The three Port property families the server evaluates at entity read time (v1.27.0, phase
 *  5 of the Port data-model move — `.claude/docs/port-data-model.md` "Computed properties"). */
export type ComputedKind = "mirror" | "calculation" | "aggregation";

/** One computed property declaration, flattened out of a blueprint's three definition maps
 *  into a shape the read-only Computed section and the preview list columns share. `type`,
 *  `colorized` and `colors` are only ever set for calculation properties (mirror/aggregation
 *  output types are not statically known from the definition — the server decides at
 *  evaluation time). */
export type ComputedDefinition = {
  id: string;
  kind: ComputedKind;
  title: string;
  type?: "string" | "number" | "boolean" | "array" | "object";
  colorized?: boolean;
  colors?: Record<string, string>;
};

/** Every computed property a blueprint declares, in mirror -> calculation -> aggregation
 *  order (Port's own document order, and the order `EntityComputedFieldset` renders rows in). */
export function computedDefinitions(blueprint: Blueprint): ComputedDefinition[] {
  const mirrors: ComputedDefinition[] = Object.entries(blueprint.mirrorProperties ?? {}).map(([id, def]) => ({
    id,
    kind: "mirror",
    title: def.title,
  }));
  const calculations: ComputedDefinition[] = Object.entries(blueprint.calculationProperties ?? {}).map(
    ([id, def]) => ({
      id,
      kind: "calculation",
      title: def.title,
      type: def.type,
      colorized: def.colorized,
      colors: def.colors,
    }),
  );
  const aggregations: ComputedDefinition[] = Object.entries(blueprint.aggregationProperties ?? {}).map(
    ([id, def]) => ({
      id,
      kind: "aggregation",
      title: def.title,
    }),
  );
  return [...mirrors, ...calculations, ...aggregations];
}

/** The set of ids `computedDefinitions` would return — a mirror/calculation/aggregation id is
 *  never a valid form draft (the server's `COMPUTED_PROPERTY` 400) and never rendered as an
 *  editable field, even when a stale stored document happens to carry the key. */
export function computedPropertyIds(blueprint: Blueprint): Set<string> {
  return new Set(computedDefinitions(blueprint).map((definition) => definition.id));
}

/** The computed values an entity's response carries for its blueprint — one entry per declared
 *  computed id, `undefined` when the server evaluated it to ABSENT (never omitted from the
 *  record: `EntityComputedFieldset` renders one row per DEFINITION, not per present key). */
export function computedValuesOf(entity: Entity, blueprint: Blueprint): Record<string, unknown> {
  const properties = (entity.properties ?? {}) as Record<string, unknown>;
  const values: Record<string, unknown> = {};
  for (const id of computedPropertyIds(blueprint)) {
    values[id] = properties[id];
  }
  return values;
}

/** The entities list's extra preview columns, beyond the schema string/number/boolean ones:
 *  every mirror/aggregation property (their output type isn't statically known, so the compact
 *  preview cell renders whatever comes back) plus only the string/number/boolean-typed
 *  calculations — an array/object calculation would need a wider cell shape the fixed-width
 *  preview column doesn't offer. Callers cap the combined column list themselves
 *  (`MAX_COLUMN_PROPERTIES` in `pages/Entities.tsx`). */
export function previewComputedColumns(blueprint: Blueprint): ComputedDefinition[] {
  return computedDefinitions(blueprint).filter(
    (definition) =>
      definition.kind !== "calculation" ||
      definition.type === "string" ||
      definition.type === "number" ||
      definition.type === "boolean",
  );
}
