import type { ErrorsReport } from "../api/catalogFiles";
import { classOfStatus, ERROR_CLASSES, type ErrorClass } from "./errorClasses";

export type ErrorFinding = ErrorsReport["findings"][number];

export type FileFindings = {
  fileId: number;
  fileName: string;
  fileKind: string;
  fileNamespace: string;
  findings: ErrorFinding[];
};

/**
 * The Errors report grouped BY FILE (v1.20.0): one row per file carrying every finding it
 * has, in the report's own order (first-seen file order, findings in wire order). Pure, so
 * the page component stays a renderer.
 */
export function groupFindingsByFile(findings: ReadonlyArray<ErrorFinding>): FileFindings[] {
  const byFile = new Map<number, FileFindings>();
  for (const finding of findings) {
    let group = byFile.get(finding.fileId);
    if (!group) {
      group = {
        fileId: finding.fileId,
        fileName: finding.fileName,
        fileKind: finding.fileKind,
        fileNamespace: finding.fileNamespace,
        findings: [],
      };
      byFile.set(finding.fileId, group);
    }
    group.findings.push(finding);
  }
  return [...byFile.values()];
}

/** Findings per error class over the UNFILTERED report — the counts on the class chips. */
export function countByClass(findings: ReadonlyArray<ErrorFinding>): Record<ErrorClass, number> {
  const counts = Object.fromEntries(ERROR_CLASSES.map((c) => [c, 0])) as Record<ErrorClass, number>;
  for (const finding of findings) counts[classOfStatus(finding.status)] += 1;
  return counts;
}
