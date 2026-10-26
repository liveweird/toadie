import {
  checkImportCatalogFiles,
  importCatalogFiles,
  type CatalogFileRequest,
  type ExpectedFileState,
  type ImportFileResult,
} from "../api/catalogFiles";
import type { ConversionRow } from "../api/conversionMappings";
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

/** A converted batch paired with the write-version each file was converted over: `expected[i]`
 *  belongs to `files[i]` — the stored counterpart's `revision`, or 0 for "expected none". */
export type CatalogBatch = {
  files: readonly CatalogFileRequest[];
  expected: readonly number[];
};

/**
 * The write-version token of each file in `files`: the `existingRevision` of every row that HAS a
 * document (`CONVERTED` / `CONVERTED_WITH_FINDINGS`, in row order — the server's pairing
 * contract), 0 when the row matched no stored file. A length that disagrees with the file count
 * means the pairing broke, so it throws rather than guard the wrong files.
 */
export function expectedFileStates(rows: readonly ConversionRow[], fileCount: number): number[] {
  const expected = rows
    .filter((row) => row.status === "CONVERTED" || row.status === "CONVERTED_WITH_FINDINGS")
    .map((row) => row.existingRevision ?? 0);
  if (expected.length !== fileCount) throw new Error("conversion rows do not pair with the converted files");
  return expected;
}

/**
 * Sends converted catalog documents through `POST /files/import(/check)` in chunks of the
 * server's 200-document cap, always with `replaceExisting` (a conversion's whole point is to
 * update the files it merged over). Each file carries the revision it was converted over
 * (`expected`, rebased to its position INSIDE the chunk, as the server reads it), so one whose
 * counterpart changed since comes back `STALE` rather than overwritten. The server's per-chunk
 * `index` is chunk-local, so each row is re-indexed to its position in the FULL input — the
 * report then lines up with the caller's list. A failing chunk throws a [CatalogBatchError]
 * holding the rows of the chunks before it (stored, in import mode); callers invalidate the
 * catalog queries in `finally` either way.
 */
export async function runCatalogFileBatches(
  mode: "check" | "import",
  { files, expected }: CatalogBatch,
): Promise<ImportFileResult[]> {
  const send = mode === "import" ? importCatalogFiles : checkImportCatalogFiles;
  const rows: ImportFileResult[] = [];
  let offset = 0;
  for (const chunk of chunked(files, IMPORT_CHUNK_SIZE)) {
    const start = offset;
    const chunkExpected: ExpectedFileState[] = chunk.flatMap((_, index) =>
      expected[start + index] === undefined ? [] : [{ index, revision: expected[start + index] }],
    );
    try {
      const response = await send(chunk, undefined, true, chunkExpected);
      rows.push(...response.results.map((row) => ({ ...row, index: row.index + start })));
    } catch (err) {
      throw new CatalogBatchError(rows, start, files.length, err);
    }
    offset += chunk.length;
  }
  return rows;
}
