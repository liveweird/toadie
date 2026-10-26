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

/** Wraps converted entity documents as the ontology import's own document type, so the existing
 *  `runImportBatch` (chunking, row matching, the 403 handling) carries them unchanged. */
export function toOntologyDocuments(documents: readonly Record<string, unknown>[], source: string): OntologyDocument[] {
  return documents.map((body, index) => ({
    index,
    kind: "entity",
    identifier: typeof body.identifier === "string" ? body.identifier : undefined,
    blueprint: typeof body.blueprint === "string" ? body.blueprint : undefined,
    body,
    stripped: [],
    strippedComputed: [],
    source,
  }));
}

/**
 * One convert-then-write step of the Backstage import: converts FRESH through `convert` (the
 * caller binds mapping, files and merge), then sends the documents to `POST /entities/import`
 * (`import`) or its dry run (`check`) with `replaceExisting: true`. Failures are tagged with the
 * phase that raised them ([ImportPhaseError]); an import-phase one also carries the rows of the
 * chunks that completed and the chunk counts, so a half-stored batch is reported as such.
 */
export async function convertThenImport({
  convert,
  mode,
  sourceLabel,
  onProgress,
  onRows,
}: {
  convert: () => Promise<ConvertedBatch>;
  mode: "check" | "import";
  sourceLabel: string;
  onProgress?: (sent: number, total: number) => void;
  onRows?: (rows: OntologyResultRow[]) => void;
}): Promise<{ converted: ConvertedBatch; rows: OntologyResultRow[] }> {
  let converted: ConvertedBatch;
  try {
    converted = await convert();
  } catch (err) {
    throw new ImportPhaseError("convert", err);
  }
  let latest: OntologyResultRow[] = [];
  let sent = 0;
  try {
    const rows = await runImportBatch({
      documents: toOntologyDocuments(converted.documents, sourceLabel),
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
