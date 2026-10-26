import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import type { CatalogFileRequest } from "../api/catalogFiles";
import type { ConversionRow } from "../api/conversionMappings";
import { batchCause, CatalogBatchError, expectedFileStates, runCatalogFileBatches } from "./catalogBatches";
import { jsonResponse } from "../test/http";

const file = (name: string): CatalogFileRequest => ({ kind: "Component", metadata: { name }, spec: {} });

const batchOf = (files: CatalogFileRequest[], revision = 3) => ({ files, expected: files.map(() => revision) });

const conversionRow = (index: number, status: ConversionRow["status"], existingRevision?: number): ConversionRow => ({
  index,
  status,
  blueprint: "svc",
  identifier: `e${index}`,
  entityId: index + 1,
  findings: [],
  unmapped: [],
  ...(existingRevision === undefined ? {} : { existingId: 10 + index, existingRevision }),
});

describe("expectedFileStates", () => {
  test("pairs the revision of each document-bearing row, 0 for a row with no counterpart", () => {
    const rows = [
      conversionRow(0, "CONVERTED", 7),
      conversionRow(1, "SKIPPED"),
      conversionRow(2, "CONVERTED_WITH_FINDINGS"),
      conversionRow(3, "INVALID"),
      conversionRow(4, "CONVERTED", 2),
    ];
    expect(expectedFileStates(rows, 3)).toEqual([7, 0, 2]);
  });

  test("a row count that does not pair with the files throws", () => {
    expect(() => expectedFileStates([conversionRow(0, "CONVERTED", 7)], 2)).toThrow(/do not pair/);
  });
});

describe("runCatalogFileBatches", () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    localStorage.setItem("toadie.auth.token", "fake-token");
    fetchMock = vi.fn(async (_url: string, init?: RequestInit) => {
      const body = JSON.parse(init!.body as string) as { files: CatalogFileRequest[] };
      return jsonResponse(200, {
        results: body.files.map((f, index) => ({ index, kind: "Component", namespace: "default", name: f.metadata.name, status: "CREATED" })),
      });
    });
    vi.stubGlobal("fetch", fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("splits at 200 documents, sends replaceExisting and re-indexes rows against the full input", async () => {
    const files = Array.from({ length: 250 }, (_, i) => file(`f${i}`));
    const rows = await runCatalogFileBatches("import", batchOf(files));

    expect(fetchMock).toHaveBeenCalledTimes(2);
    const sizes = fetchMock.mock.calls.map(([, init]) => (JSON.parse((init as RequestInit).body as string) as { files: unknown[] }).files.length);
    expect(sizes).toEqual([200, 50]);
    expect(fetchMock.mock.calls.every(([url]) => url === "/api/v1/files/import")).toBe(true);
    expect(JSON.parse((fetchMock.mock.calls[0][1] as RequestInit).body as string).replaceExisting).toBe(true);
    expect(rows).toHaveLength(250);
    expect(rows[200]).toMatchObject({ index: 200, name: "f200" });
    expect(rows[249]).toMatchObject({ index: 249, name: "f249" });
  });

  test("sends each file's expected revision rebased to its position inside the chunk", async () => {
    const files = Array.from({ length: 202 }, (_, i) => file(`f${i}`));
    await runCatalogFileBatches("import", { files, expected: files.map((_, i) => i + 1) });

    const bodies = fetchMock.mock.calls.map(([, init]) => JSON.parse((init as RequestInit).body as string) as { expected: { index: number; revision: number }[] });
    expect(bodies[0].expected).toHaveLength(200);
    expect(bodies[0].expected[199]).toEqual({ index: 199, revision: 200 });
    // File 200 of the full input is index 0 of the second chunk; its revision stays its own.
    expect(bodies[1].expected).toEqual([
      { index: 0, revision: 201 },
      { index: 1, revision: 202 },
    ]);
  });

  test("a file expecting no counterpart carries revision 0 on the wire", async () => {
    await runCatalogFileBatches("check", { files: [file("a")], expected: [0] });
    expect(JSON.parse((fetchMock.mock.calls[0][1] as RequestInit).body as string).expected).toEqual([{ index: 0, revision: 0 }]);
  });

  test("check mode uses the dry-run endpoint", async () => {
    await runCatalogFileBatches("check", batchOf([file("a")]));
    expect(fetchMock.mock.calls[0][0]).toBe("/api/v1/files/import/check");
  });

  test("a failing second chunk throws a CatalogBatchError carrying the stored rows and how far it got", async () => {
    fetchMock.mockImplementationOnce(async (_url: string, init?: RequestInit) => {
      const body = JSON.parse(init!.body as string) as { files: CatalogFileRequest[] };
      return jsonResponse(200, { results: body.files.map((f, index) => ({ index, kind: "Component", namespace: "default", name: f.metadata.name, status: "CREATED" })) });
    });
    fetchMock.mockResolvedValueOnce(jsonResponse(500, { title: "x", status: 500 }));
    const error = await runCatalogFileBatches("import", batchOf(Array.from({ length: 201 }, (_, i) => file(`f${i}`)))).catch((err: unknown) => err);

    expect(error).toBeInstanceOf(CatalogBatchError);
    const batch = error as CatalogBatchError;
    expect(batch.rows).toHaveLength(200);
    expect(batch.filesSent).toBe(200);
    expect(batch.filesTotal).toBe(201);
    expect(batchCause(batch)).toBeInstanceOf(Error);
    expect(batchCause(new Error("plain"))).toEqual(new Error("plain"));
  });

  test("an empty input sends nothing", async () => {
    expect(await runCatalogFileBatches("check", batchOf([]))).toEqual([]);
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
