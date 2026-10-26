import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import type { CatalogFileRequest } from "../api/catalogFiles";
import type { EntityDocumentRow } from "../api/conversionMappings";
import { ImportPhaseError } from "./conversionErrors";
import { convertCatalogFiles, expectedEntityStates, toOntologyDocuments } from "./conversionBatches";
import { jsonResponse } from "../test/http";

const file = (name: string): CatalogFileRequest => ({ kind: "Component", metadata: { name }, spec: {} });

describe("convertCatalogFiles", () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    localStorage.setItem("toadie.auth.token", "fake-token");
    fetchMock = vi.fn(async (_url: string, init?: RequestInit) => {
      const body = JSON.parse(init!.body as string) as { files: CatalogFileRequest[] };
      return jsonResponse(200, {
        documents: body.files.map((f) => ({ blueprint: "svc", identifier: f.metadata.name })),
        rows: body.files.map((f, index) => ({ index, status: "CONVERTED", kind: "Component", namespace: "default", name: f.metadata.name, findings: [], unmapped: [] })),
      });
    });
    vi.stubGlobal("fetch", fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("converts in chunks of 200 and re-bases row indexes onto the full input", async () => {
    const files = Array.from({ length: 205 }, (_, i) => file(`f${i}`));
    const { documents, rows } = await convertCatalogFiles(5, files, false);

    const sent = fetchMock.mock.calls.map(([, init]) => JSON.parse((init as RequestInit).body as string) as { files: unknown[]; merge: boolean });
    expect(sent.map((s) => s.files.length)).toEqual([200, 5]);
    expect(sent.every((s) => s.merge === false)).toBe(true);
    expect(fetchMock.mock.calls[0][0]).toBe("/api/v1/conversion-mappings/5/entity-documents");
    expect(documents).toHaveLength(205);
    expect(rows[204]).toMatchObject({ index: 204, name: "f204" });
  });

  test("toOntologyDocuments wraps converted bodies for the ontology import orchestrator", () => {
    const docs = toOntologyDocuments([{ blueprint: "svc", identifier: "a" }, { title: "odd" }], "Backstage", [5, 0]);
    expect(docs[0]).toMatchObject({ index: 0, kind: "entity", blueprint: "svc", identifier: "a", source: "Backstage", stripped: [], expectedUpdatedAt: 5 });
    expect(docs[1]).toMatchObject({ index: 1, identifier: undefined, blueprint: undefined, expectedUpdatedAt: 0 });
  });
});

const row = (index: number, status: EntityDocumentRow["status"], existingUpdatedAt?: number): EntityDocumentRow => ({
  index,
  status,
  kind: "Component",
  namespace: "default",
  name: `n${index}`,
  findings: [],
  unmapped: [],
  ...(existingUpdatedAt === undefined ? {} : { existingId: 10 + index, existingUpdatedAt }),
});

describe("expectedEntityStates", () => {
  test("pairs the token of each document-bearing row in row order, 0 for a row with no counterpart", () => {
    const rows = [row(0, "CONVERTED", 1700), row(1, "SKIPPED"), row(2, "CONVERTED_WITH_FINDINGS"), row(3, "INVALID"), row(4, "CONVERTED", 42)];
    expect(expectedEntityStates(rows, 3)).toEqual([1700, 0, 42]);
  });

  test("a document count that does not pair with the rows throws a convert-phase error", () => {
    const error = (() => {
      try {
        expectedEntityStates([row(0, "CONVERTED", 1700)], 2);
      } catch (err) {
        return err;
      }
      return null;
    })();
    expect(error).toBeInstanceOf(ImportPhaseError);
    expect((error as ImportPhaseError).phase).toBe("convert");
  });
});
