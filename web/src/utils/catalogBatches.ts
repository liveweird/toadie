import {
  checkImportCatalogFiles,
  importCatalogFiles,
  type CatalogFileRequest,
  type ImportFileResult,
} from "../api/catalogFiles";
import { chunked, IMPORT_CHUNK_SIZE } from "./ontologyImport";

/**
 * A chunked import that stopped part-way: carries the rows of the chunks that DID complete (in
 * import mode those files are stored), how many of the input files were sent before the failing
 * chunk, and the underlying failure as `cause`. The modal renders the stored rows and a
 * "stopped after N of M files" notice instead of pretending nothing happened.
 */
export class CatalogBatchError extends Error {
  readonly rows: ImportFileResult[];
  readonly filesSent: number;
  readonly filesTotal: number;

  constructor(rows: ImportFileResult[], filesSent: number, filesTotal: number, cause: unknown) {
    super("catalog batch stopped", { cause });
    this.rows = rows;
    this.filesSent = filesSent;
    this.filesTotal = filesTotal;
  }
}

/** The failure behind a batch error (the batch error itself carries no status). */
export const batchCause = (err: unknown): unknown => (err instanceof CatalogBatchError ? err.cause : err);

/**
 * Sends converted catalog documents through `POST /files/import(/check)` in chunks of the
 * server's 200-document cap, always with `replaceExisting` (a conversion's whole point is to
 * update the files it merged over). The server's per-chunk `index` is chunk-local, so each row is
 * re-indexed to its position in the FULL input — the report then lines up with the caller's list.
 * A failing chunk throws a [CatalogBatchError] holding the rows of the chunks before it (stored,
 * in import mode); callers invalidate the catalog queries in `finally` either way.
 */
export async function runCatalogFileBatches(
  mode: "check" | "import",
  files: readonly CatalogFileRequest[],
): Promise<ImportFileResult[]> {
  const send = mode === "import" ? importCatalogFiles : checkImportCatalogFiles;
  const rows: ImportFileResult[] = [];
  let offset = 0;
  for (const chunk of chunked(files, IMPORT_CHUNK_SIZE)) {
    try {
      const response = await send(chunk, undefined, true);
      rows.push(...response.results.map((row) => ({ ...row, index: row.index + offset })));
    } catch (err) {
      throw new CatalogBatchError(rows, offset, files.length, err);
    }
    offset += chunk.length;
  }
  return rows;
}
