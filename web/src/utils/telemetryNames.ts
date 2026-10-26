import type { ParseKeys, TFunction } from "i18next";
import { formatCount } from "./telemetryFormat";

// The names the server writes on the workspace figures (table groups and counted tables, `StorageCollectors.kt`) in words. Literal
// key maps, never a template: `locales/unusedKeys.test.ts` sees only keys named in the source. A name a newer server adds that is
// not listed here reads as itself, so a figure is never blanked out.

const GROUP_KEYS: Record<string, ParseKeys> = {
  catalog: "telemetry.workspace.group.catalog",
  ontology: "telemetry.workspace.group.ontology",
  registries: "telemetry.workspace.group.registries",
  views: "telemetry.workspace.group.views",
  accounts: "telemetry.workspace.group.accounts",
  telemetry: "telemetry.workspace.group.telemetry",
};

const TABLE_KEYS: Record<string, ParseKeys> = {
  catalog_files: "telemetry.workspace.table.catalog_files",
  catalog_file_events: "telemetry.workspace.table.catalog_file_events",
  blueprints: "telemetry.workspace.table.blueprints",
  entities: "telemetry.workspace.table.entities",
  conversion_mappings: "telemetry.workspace.table.conversion_mappings",
  lenses: "telemetry.workspace.table.lenses",
  entity_queries: "telemetry.workspace.table.entity_queries",
};

/** A table group ("catalog", "ontology", …) in words. */
export function workspaceGroupName(name: string, t: TFunction): string {
  const key = GROUP_KEYS[name];
  return key === undefined ? name : t(key);
}

/** A counted table ("catalog_files", …) in words. */
export function workspaceTableName(name: string, t: TFunction): string {
  const key = TABLE_KEYS[name];
  return key === undefined ? name : t(key);
}

/** "n of N" for a bounded thing (`format` renders both numbers); the bare dash when the figure was not measured. */
export function usageOf(
  t: TFunction,
  used: number | null | undefined,
  limit: number,
  format: (value: number | null | undefined) => string = formatCount,
): string {
  return used == null ? format(used) : t("telemetry.of", { used: format(used), limit: format(limit) });
}
