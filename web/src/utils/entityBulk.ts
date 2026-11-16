import type { TFunction } from "i18next";
import type { Blueprint } from "../api/blueprints";
import type {
  EntityBody,
  EntityBulkChangeRequest,
  EntityBulkOperation,
  EntityBulkResult,
  EntityBulkStatus,
  EntityGraphNode,
} from "../api/entities";
import { ApiError } from "../api/http";
import type { HierarchyNode } from "./hierarchy";
import { saveErrorMessage } from "./saveError";

/**
 * Pure helpers of the bulk-change page and modal (2.38.0, "Bulk change" in `web/CLAUDE.md`):
 * which blueprints supply the tree's context rows, the tree pruning and its tri-state
 * selection arithmetic, the changeable fields and their operations, and the request builder.
 * Nothing here touches React or the network.
 */

// -- The tree: target blueprint's entities plus their ancestors as context rows -----------

/**
 * The blueprints above [target] along the SELECTED hierarchy: [target]'s `hierarchyRelations`
 * entry names one of its single relations, whose `target` blueprint is the next level up —
 * and so on until a blueprint has no entry for the hierarchy, names a relation it no longer
 * declares, or the chain loops. [target] itself is never part of the result, even for a
 * self-referencing relation (it is requested as the target anyway). An empty [hierarchyId]
 * (an empty hierarchies dictionary) has no ancestors.
 */
export function ancestorBlueprints(
  target: string,
  blueprints: readonly Pick<Blueprint, "identifier" | "relations" | "hierarchyRelations">[],
  hierarchyId: string,
): string[] {
  if (!hierarchyId) return [];
  const byIdentifier = new Map(blueprints.map((blueprint) => [blueprint.identifier, blueprint]));
  const seen = new Set([target]);
  const ancestors: string[] = [];
  let current = byIdentifier.get(target);
  while (current) {
    const relation = current.hierarchyRelations?.[hierarchyId];
    const next = relation === undefined ? undefined : current.relations[relation]?.target;
    if (next === undefined || seen.has(next)) break;
    seen.add(next);
    ancestors.push(next);
    current = byIdentifier.get(next);
  }
  return ancestors;
}

/**
 * Keeps the branches of [roots] that contain at least one entity of [blueprint]: a node of
 * that blueprint (a TARGET) always stays, any other node (a context row) only while something
 * beneath it is a target. Each kept node carries its kept children only.
 */
export function pruneToBlueprint(
  roots: readonly HierarchyNode<EntityGraphNode>[],
  blueprint: string,
): HierarchyNode<EntityGraphNode>[] {
  const kept: HierarchyNode<EntityGraphNode>[] = [];
  for (const root of roots) {
    const children = pruneToBlueprint(root.children, blueprint);
    if (root.node.blueprint === blueprint || children.length > 0) kept.push({ node: root.node, children });
  }
  return kept;
}

/** The target entities (blueprint [blueprint]) in and under [items], depth-first in tree order. */
export function targetsUnder(
  items: readonly HierarchyNode<EntityGraphNode>[],
  blueprint: string,
): EntityGraphNode[] {
  const found: EntityGraphNode[] = [];
  const stack = [...items].reverse();
  for (let item = stack.pop(); item !== undefined; item = stack.pop()) {
    if (item.node.blueprint === blueprint) found.push(item.node);
    for (let i = item.children.length - 1; i >= 0; i -= 1) stack.push(item.children[i]);
  }
  return found;
}

export type CheckState = "none" | "some" | "all";

/** A checkbox's tri-state over [ids] (the targets it stands for) against the [selected] set. */
export function checkState(ids: readonly number[], selected: ReadonlySet<number>): CheckState {
  let count = 0;
  for (const id of ids) if (selected.has(id)) count += 1;
  if (count === 0) return "none";
  return count === ids.length ? "all" : "some";
}

/** [selected] with every one of [ids] added (`on`) or removed — a new Set, the input untouched. */
export function withSelection(selected: ReadonlySet<number>, ids: readonly number[], on: boolean): Set<number> {
  const next = new Set(selected);
  for (const id of ids) {
    if (on) next.add(id);
    else next.delete(id);
  }
  return next;
}

/**
 * The ONE blueprint a canvas shows, for the bulk-change entry link: the pills' visible set
 * (`narrowed`, the filter state's `blueprints` — absent while nothing is hidden, i.e. every
 * [active] blueprint is shown) when it has exactly one member, otherwise `undefined` (the
 * destination then asks for a pick).
 */
export function singleVisibleBlueprint(
  narrowed: readonly string[] | undefined,
  active: readonly string[],
): string | undefined {
  const visible = narrowed ?? active;
  return visible.length === 1 ? visible[0] : undefined;
}

// -- Fields and operations ---------------------------------------------------------------------

type BulkFieldKind = "property" | "relation" | "team";

/** One changeable field of a blueprint, in the findings path grammar the server takes. */
export type BulkField = {
  /** `properties.<id>`, `relations.<id>` or `team`. */
  path: string;
  kind: BulkFieldKind;
  /** The property/relation id (`team` for the ownership field). */
  id: string;
  label: string;
  /** The field's position in the BLANK entity form's `properties`/`relations` draft list
   *  (`blankEntityForm` keeps schema order); -1 for `team`. */
  index: number;
  /** A list field accepts ADD/REMOVE: an `array` property, a `many` relation, `team`. */
  listLike: boolean;
  /** A required field cannot be cleared (the server would refuse every row anyway). */
  required: boolean;
};

/**
 * The fields a bulk change can address: the blueprint's schema properties (computed
 * mirror/calculation/aggregation properties live in other maps and are never offered), its
 * relations, and `team` unless ownership is Inherited (computed at read time, never writable).
 * `identifier`, `title`, `icon` and `sourceUrl` are deliberately not changeable here.
 */
export function bulkFields(blueprint: Blueprint, teamLabel: string): BulkField[] {
  const required = new Set(blueprint.schema.required);
  const properties = Object.entries(blueprint.schema.properties).map(
    ([id, definition], index): BulkField => ({
      path: `properties.${id}`,
      kind: "property",
      id,
      label: definition.title ?? id,
      index,
      listLike: definition.type === "array",
      required: required.has(id),
    }),
  );
  const relations = Object.entries(blueprint.relations).map(
    ([id, definition], index): BulkField => ({
      path: `relations.${id}`,
      kind: "relation",
      id,
      label: definition.title,
      index,
      listLike: definition.many,
      required: definition.required,
    }),
  );
  const team: BulkField[] =
    blueprint.ownership?.type === "Inherited"
      ? []
      : [{ path: "team", kind: "team", id: "team", label: teamLabel, index: -1, listLike: true, required: false }];
  return [...properties, ...relations, ...team];
}

export type BulkOperationOption = { operation: EntityBulkOperation; enabled: boolean };

/** The operations a field offers, in display order: Clear is disabled for a required field,
 *  Add/Remove exist only on list fields. */
export function operationsFor(field: BulkField): BulkOperationOption[] {
  const options: BulkOperationOption[] = [
    { operation: "SET", enabled: true },
    { operation: "CLEAR", enabled: !field.required },
  ];
  if (field.listLike) {
    options.push({ operation: "ADD", enabled: true }, { operation: "REMOVE", enabled: true });
  }
  return options;
}

/** Every Mantine form path that holds the field's value draft — the paths Preview validates
 *  (`entityFieldError`), so only the picked field is checked (the throwaway form's other fields
 *  are blank on purpose). */
export function valuePaths(field: BulkField): string[] {
  switch (field.kind) {
    case "property":
      return ["text", "bool", "list", "json"].map((slot) => `properties.${field.index}.${slot}`);
    case "relation":
      return ["single", "many"].map((slot) => `relations.${field.index}.${slot}`);
    default:
      return ["team"];
  }
}

/** The picked field's wire value out of a request built by `toEntityRequest` over the
 *  throwaway form (`undefined` = the draft is blank — blank is never an implicit clear). */
export function bulkValueOf(field: BulkField, request: EntityBody): unknown {
  switch (field.kind) {
    case "property":
      return (request.properties as Record<string, unknown> | undefined)?.[field.id];
    case "relation":
      return (request.relations as Record<string, unknown> | undefined)?.[field.id];
    default:
      return request.team;
  }
}

/** Whether [value] satisfies the operation's contract: CLEAR takes none, SET any, ADD/REMOVE a
 *  non-empty array of items. */
export function hasBulkValue(operation: EntityBulkOperation, value: unknown): boolean {
  if (operation === "CLEAR") return true;
  if (operation === "SET") return value !== undefined && value !== null;
  return Array.isArray(value) && value.length > 0;
}

/** The request body. `entities` carry the write-version stamp when one is known (the preview's
 *  answer), so a row edited since is reported STALE instead of overwritten. */
export function toBulkRequest(args: {
  blueprint: string;
  field: BulkField;
  operation: EntityBulkOperation;
  value: unknown;
  entities: readonly { id: number; updatedAt?: number }[];
}): EntityBulkChangeRequest {
  return {
    blueprint: args.blueprint,
    field: args.field.path,
    operation: args.operation,
    value: args.operation === "CLEAR" ? undefined : args.value,
    entities: args.entities.map(({ id, updatedAt }) => (updatedAt === undefined ? { id } : { id, updatedAt })),
  };
}

// -- Results -------------------------------------------------------------------------------------

export type BulkCounts = Record<EntityBulkStatus, number>;

export function bulkCounts(results: readonly Pick<EntityBulkResult, "status">[]): BulkCounts {
  const counts: BulkCounts = { UPDATED: 0, UNCHANGED: 0, INVALID: 0, STALE: 0, ERROR: 0 };
  for (const result of results) counts[result.status] += 1;
  return counts;
}

/** A before/after value as one line of text; `null` = the field had no value (shown as a dash). */
export function formatBulkValue(value: unknown): string | null {
  if (value === undefined || value === null) return null;
  if (typeof value === "string") return value;
  if (Array.isArray(value)) return value.map((item) => formatBulkValue(item) ?? "").join(", ");
  if (typeof value === "object") return JSON.stringify(value);
  return String(value);
}

/**
 * Whether a failed REAL run may still have written some rows: the writes are per-row
 * transactions, so a timeout, a network failure or a 5xx leaves the outcome unknown, while any
 * 4xx answer (400 rule, 413 size, 429 read budget, 401) was refused before the first write.
 */
export function isOutcomeUnknown(err: unknown): boolean {
  return !(err instanceof ApiError && err.status >= 400 && err.status < 500);
}

/** The request-level failure vocabulary (fixed strings — never the server's message). */
export function bulkErrorMessage(err: unknown, t: TFunction, phase: "check" | "apply"): string {
  if (phase === "apply" && isOutcomeUnknown(err)) return t("entityBulk.error.applyUnknown");
  return saveErrorMessage(err, t, {
    invalid: "entityBulk.error.invalid",
    tooManyRequests: "entityBulk.error.busy",
    failedStatus: "common.error.actionFailedStatus",
    failed: "common.error.actionFailed",
  });
}
