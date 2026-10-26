import type {
  ConversionFinding,
  ConversionRow,
  EntityDocumentRow,
} from "../api/conversionMappings";
import { editCatalogFilePath } from "./catalogFileLinks";
import { editEntityPath } from "./entityLinks";

type RowStatus = ConversionRow["status"];

/**
 * One display row of the conversion report, direction-neutral: the export reports Port entities
 * becoming Backstage files, the import the reverse, and `ConversionRowsTable` renders both from
 * this shape. `to` links to the stored counterpart (`existingPath`) the merge read, when there is one.
 */
export type ConversionDisplayRow = {
  key: string;
  status: RowStatus;
  from: string;
  to: string | null;
  existingPath: string | null;
  findings: readonly ConversionFinding[];
  unmapped: readonly string[];
};

/** The app-wide colour vocabulary: teal = clean, orange = converted with a caution, gray = not
 *  applicable, red = nothing usable came out. */
export const ROW_STATUS_COLOR: Record<RowStatus, string> = {
  CONVERTED: "teal",
  CONVERTED_WITH_FINDINGS: "orange",
  SKIPPED: "gray",
  INVALID: "red",
};

const portLabel = (blueprint: string | undefined, identifier: string | undefined) =>
  blueprint == null ? null : `${blueprint} / ${identifier ?? ""}`;

/** `kind:namespace/name`, the Backstage entity reference grammar. */
const backstageLabel = (kind: string, namespace: string, name: string) => `${kind}:${namespace}/${name}`;

export function exportDisplayRows(rows: readonly ConversionRow[]): ConversionDisplayRow[] {
  return rows.map((row) => ({
    key: `${row.index}`,
    status: row.status,
    from: portLabel(row.blueprint, row.identifier) ?? row.identifier,
    to: row.kind != null && row.namespace != null && row.name != null ? backstageLabel(row.kind, row.namespace, row.name) : null,
    existingPath: row.existingId != null ? editCatalogFilePath(row.existingId) : null,
    findings: row.findings,
    unmapped: row.unmapped,
  }));
}

export function importDisplayRows(rows: readonly EntityDocumentRow[]): ConversionDisplayRow[] {
  return rows.map((row) => ({
    key: `${row.index}`,
    status: row.status,
    from: backstageLabel(row.kind, row.namespace, row.name),
    to: portLabel(row.blueprint, row.identifier),
    existingPath: row.existingId != null ? editEntityPath(row.existingId) : null,
    findings: row.findings,
    unmapped: row.unmapped,
  }));
}

export type RowCounts = { total: number; converted: number; withFindings: number; skipped: number; invalid: number };

export function countRows(rows: readonly { status: RowStatus }[]): RowCounts {
  const counts: RowCounts = { total: rows.length, converted: 0, withFindings: 0, skipped: 0, invalid: 0 };
  for (const row of rows) {
    if (row.status === "CONVERTED") counts.converted += 1;
    else if (row.status === "CONVERTED_WITH_FINDINGS") counts.withFindings += 1;
    else if (row.status === "SKIPPED") counts.skipped += 1;
    else counts.invalid += 1;
  }
  return counts;
}
