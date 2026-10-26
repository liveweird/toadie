import type { TFunction } from "i18next";
import { batchCause } from "./catalogBatches";
import type { OntologyResultRow } from "./ontologyImport";
import { saveErrorMessage } from "./saveError";

/**
 * The conversion calls' failure vocabulary: the mapping went stale (409), is gone (404), the read
 * memory budget is contended (429) or the request is refused (400 — for the export, a refused
 * entity query or a too-large selection, for the import a malformed or over-cap document batch).
 */
export function conversionErrorMessage(err: unknown, t: TFunction, direction: "export" | "import"): string {
  return saveErrorMessage(batchCause(err), t, {
    notFound: "conversion.error.mappingGone",
    conflict: "conversion.error.mappingStale",
    tooManyRequests: "conversion.error.busy",
    invalid: direction === "export" ? "conversion.error.refusedExport" : "conversion.error.refusedImport",
    failedStatus: "conversion.error.failedStatus",
    failed: "conversion.error.failedNetwork",
  });
}

/** The entity import's own failure vocabulary (the Ontology import page's), for the WRITE phase. */
function importErrorMessage(err: unknown, t: TFunction): string {
  return saveErrorMessage(err, t, {
    failedStatus: "ontology.import.failedStatus",
    failed: "ontology.import.failedNetwork",
  });
}

/**
 * Which half of a convert-then-import step failed — the conversion call or the entity import. An
 * import-phase failure also carries what DID happen: the rows reported by the chunks that
 * completed (in import mode those entities are stored) and how many chunks were sent.
 */
export class ImportPhaseError extends Error {
  readonly phase: "convert" | "import";
  readonly rows: OntologyResultRow[];
  readonly chunksSent: number;
  readonly chunksTotal: number;

  constructor(
    phase: "convert" | "import",
    cause: unknown,
    progress: { rows: OntologyResultRow[]; chunksSent: number; chunksTotal: number } = { rows: [], chunksSent: 0, chunksTotal: 0 },
  ) {
    super(`import ${phase} phase failed`, { cause });
    this.phase = phase;
    this.rows = progress.rows;
    this.chunksSent = progress.chunksSent;
    this.chunksTotal = progress.chunksTotal;
  }
}

/** Title + message for a failure of the Backstage import flow, by the phase that raised it. */
export function importFlowError(err: unknown, t: TFunction): { title: string; message: string } {
  if (err instanceof ImportPhaseError && err.phase === "import") {
    return { title: t("ontology.import.failedTitle"), message: importErrorMessage(err.cause, t) };
  }
  const cause = err instanceof ImportPhaseError ? err.cause : err;
  return { title: t("conversion.import.convertFailedTitle"), message: conversionErrorMessage(cause, t, "import") };
}
