import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import type { CatalogFileRequest } from "../api/catalogFiles";
import { batchCause, CatalogBatchError, runCatalogFileBatches } from "./catalogBatches";
import { jsonResponse } from "../test/http";

const file = (name: string): CatalogFileRequest => ({ kind: "Component", metadata: { name }, spec: {} });

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
    const rows = await runCatalogFileBatches("import", files);

    expect(fetchMock).toHaveBeenCalledTimes(2);
    const sizes = fetchMock.mock.calls.map(([, init]) => (JSON.parse((init as RequestInit).body as string) as { files: unknown[] }).files.length);
    expect(sizes).toEqual([200, 50]);
    expect(fetchMock.mock.calls.every(([url]) => url === "/api/v1/files/import")).toBe(true);
    expect(JSON.parse((fetchMock.mock.calls[0][1] as RequestInit).body as string).replaceExisting).toBe(true);
    expect(rows).toHaveLength(250);
    expect(rows[200]).toMatchObject({ index: 200, name: "f200" });
    expect(rows[249]).toMatchObject({ index: 249, name: "f249" });
  });

  test("check mode uses the dry-run endpoint", async () => {
    await runCatalogFileBatches("check", [file("a")]);
    expect(fetchMock.mock.calls[0][0]).toBe("/api/v1/files/import/check");
  });

  test("a failing second chunk throws a CatalogBatchError carrying the stored rows and how far it got", async () => {
    fetchMock.mockImplementationOnce(async (_url: string, init?: RequestInit) => {
      const body = JSON.parse(init!.body as string) as { files: CatalogFileRequest[] };
      return jsonResponse(200, { results: body.files.map((f, index) => ({ index, kind: "Component", namespace: "default", name: f.metadata.name, status: "CREATED" })) });
    });
    fetchMock.mockResolvedValueOnce(jsonResponse(500, { title: "x", status: 500 }));
    const error = await runCatalogFileBatches("import", Array.from({ length: 201 }, (_, i) => file(`f${i}`))).catch((err: unknown) => err);

    expect(error).toBeInstanceOf(CatalogBatchError);
    const batch = error as CatalogBatchError;
    expect(batch.rows).toHaveLength(200);
    expect(batch.filesSent).toBe(200);
    expect(batch.filesTotal).toBe(201);
    expect(batchCause(batch)).toBeInstanceOf(Error);
    expect(batchCause(new Error("plain"))).toEqual(new Error("plain"));
  });

  test("an empty input sends nothing", async () => {
    expect(await runCatalogFileBatches("check", [])).toEqual([]);
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
