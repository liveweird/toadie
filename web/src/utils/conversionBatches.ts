import type { CatalogFileRequest } from "../api/catalogFiles";
import { importBlueprints } from "../api/blueprints";
import { checkEntityImport, importEntities } from "../api/entities";
import { isAdmin } from "../api/session";
import {
  convertToEntityDocuments,
  type EntityDocumentRow,
} from "../api/conversionMappings";
import { ImportPhaseError } from "./conversionErrors";
import { chunked, IMPORT_CHUNK_SIZE, runImportBatch, type OntologyDocument, type OntologyResultRow } from "./ontologyImport";

export type ConvertedBatch = {
  /** The converted entity documents, in request order — exactly what `POST /entities/import` takes. */
  documents: Record<string, unknown>[];
  /** One row per submitted file, re-indexed against the FULL input. */
  rows: EntityDocumentRow[];
};

/** A converted batch paired with the write-version each document was converted over: `expected[i]`
 *  belongs to `documents[i]` — the stored counterpart's `updatedAt`, or 0 for "expected none". */
export type PairedBatch = ConvertedBatch & { expected: number[] };

/**
 * Backstage -> Port conversion over any number of files, in chunks of the server's 200-document
 * cap. Each chunk is converted independently, so a reference from a file in one chunk to a file
 * in another resolves only against what is already stored — the documented chunking limitation
 * the entity import shares. Row `index` is chunk-local on the wire; it is re-based here.
 */
export async function convertCatalogFiles(
  mappingId: number,
  files: readonly CatalogFileRequest[],
  merge: boolean,
): Promise<ConvertedBatch> {
  const documents: Record<string, unknown>[] = [];
  const rows: EntityDocumentRow[] = [];
  let offset = 0;
  for (const chunk of chunked(files, IMPORT_CHUNK_SIZE)) {
    const response = await convertToEntityDocuments(mappingId, { files: chunk, merge });
    documents.push(...response.documents);
    rows.push(...response.rows.map((row) => ({ ...row, index: row.index + offset })));
    offset += chunk.length;
  }
  return { documents, rows };
}

/**
 * The write-version token of each document in `documents`: the `existingUpdatedAt` of every row
 * that HAS a document (`CONVERTED` / `CONVERTED_WITH_FINDINGS`, in row order — the server's own
 * pairing contract), 0 when the row matched no stored entity. A length that disagrees with the
 * document count means the pairing broke, so it throws rather than guard the wrong documents.
 */
export function expectedEntityStates(rows: readonly EntityDocumentRow[], documentCount: number): number[] {
  const expected = rows
    .filter((row) => row.status === "CONVERTED" || row.status === "CONVERTED_WITH_FINDINGS")
    .map((row) => row.existingUpdatedAt ?? 0);
  if (expected.length !== documentCount) {
    throw new ImportPhaseError("convert", new Error("conversion rows do not pair with the converted documents"));
  }
  return expected;
}

/** Wraps converted entity documents as the ontology import's own document type, so the existing
 *  `runImportBatch` (chunking, row matching, the 403 handling) carries them unchanged. */
export function toOntologyDocuments(
  documents: readonly Record<string, unknown>[],
  source: string,
  expected: readonly number[],
): OntologyDocument[] {
  return documents.map((body, index) => ({
    index,
    kind: "entity",
    identifier: typeof body.identifier === "string" ? body.identifier : undefined,
    blueprint: typeof body.blueprint === "string" ? body.blueprint : undefined,
    body,
    stripped: [],
    strippedComputed: [],
    source,
    expectedUpdatedAt: expected[index],
  }));
}

/**
 * The convert half of the Backstage import: runs `convert` (the caller binds mapping, files and
 * merge) and pairs every document with the write-version it was converted over. A failure is
 * tagged `ImportPhaseError("convert", …)`. The result is what the dialog PREVIEWS and, on Confirm,
 * imports unchanged ([importConverted]) — nothing is converted twice.
 */
export async function convertForImport(convert: () => Promise<ConvertedBatch>): Promise<PairedBatch> {
  try {
    const converted = await convert();
    return { ...converted, expected: expectedEntityStates(converted.rows, converted.documents.length) };
  } catch (err) {
    throw err instanceof ImportPhaseError ? err : new ImportPhaseError("convert", err);
  }
}

/**
 * The import half: sends an already-converted batch to `POST /entities/import` (`import`) or its
 * dry run (`check`) with `replaceExisting: true`, each document carrying the write-version it was
 * converted over, so one whose counterpart changed since comes back `STALE` instead of being
 * overwritten. Failures are tagged [ImportPhaseError] "import" and carry the rows of the chunks
 * that completed and the chunk counts, so a half-stored batch is reported as such.
 */
export async function importConverted(
  converted: PairedBatch,
  mode: "check" | "import",
  {
    sourceLabel,
    onProgress,
    onRows,
  }: {
    sourceLabel: string;
    onProgress?: (sent: number, total: number) => void;
    onRows?: (rows: OntologyResultRow[]) => void;
  },
): Promise<{ converted: PairedBatch; rows: OntologyResultRow[] }> {
  let latest: OntologyResultRow[] = [];
  let sent = 0;
  try {
    const rows = await runImportBatch({
      documents: toOntologyDocuments(converted.documents, sourceLabel, converted.expected),
      replaceExisting: true,
      admin: isAdmin(),
      // No blueprint documents are ever produced, so this caller is never invoked.
      importBlueprintsChunk: importBlueprints,
      importEntitiesChunk: mode === "import" ? importEntities : checkEntityImport,
      onProgress: (done, total) => {
        sent = done;
        onProgress?.(done, total);
      },
      onRows: (partial) => {
        latest = partial;
        onRows?.(partial);
      },
    });
    return { converted, rows };
  } catch (err) {
    throw new ImportPhaseError("import", err, {
      rows: latest,
      chunksSent: sent,
      chunksTotal: Math.ceil(converted.documents.length / IMPORT_CHUNK_SIZE),
    });
  }
}
