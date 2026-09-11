import { describe, expect, test, vi } from "vitest";
import { ApiError } from "../api/http";
import type { Blueprint } from "../api/blueprints";
import {
  IMPORT_CHUNK_SIZE,
  buildComputedIdsByBlueprint,
  chunked,
  detectKind,
  parseOntologySources,
  runImportBatch,
  sanitizeDocument,
  stripComputedProperties,
  summarizeStripped,
  unwrapEnvelope,
  type OntologyDocument,
} from "./ontologyImport";

function blueprintDoc(overrides: Partial<Record<string, unknown>> = {}) {
  return { identifier: "service", title: "Service", schema: { properties: {}, required: [] }, ...overrides };
}

function entityDoc(overrides: Partial<Record<string, unknown>> = {}) {
  return { blueprint: "service", identifier: "checkout", title: "Checkout", properties: {}, ...overrides };
}

describe("unwrapEnvelope", () => {
  test("a bare object is itself one document", () => {
    expect(unwrapEnvelope({ a: 1 })).toEqual([{ a: 1 }]);
  });

  test("a bare array is its items", () => {
    expect(unwrapEnvelope([{ a: 1 }, { a: 2 }])).toEqual([{ a: 1 }, { a: 2 }]);
  });

  test("{blueprints, entities} unwraps blueprints then entities", () => {
    expect(unwrapEnvelope({ blueprints: [{ a: 1 }], entities: [{ b: 2 }] })).toEqual([{ a: 1 }, { b: 2 }]);
  });

  test("{blueprints}-only and {entities}-only unwrap to their own items", () => {
    expect(unwrapEnvelope({ blueprints: [{ a: 1 }] })).toEqual([{ a: 1 }]);
    expect(unwrapEnvelope({ entities: [{ b: 2 }] })).toEqual([{ b: 2 }]);
  });

  test("{ok, blueprint} and {ok, entity} unwrap to one item, ok ignored", () => {
    expect(unwrapEnvelope({ ok: true, blueprint: { a: 1 } })).toEqual([{ a: 1 }]);
    expect(unwrapEnvelope({ ok: true, entity: { b: 2 } })).toEqual([{ b: 2 }]);
  });

  test("{ok, blueprints}/{ok, entities} unwrap to their items, ok ignored", () => {
    expect(unwrapEnvelope({ ok: true, blueprints: [{ a: 1 }, { a: 2 }] })).toEqual([{ a: 1 }, { a: 2 }]);
    expect(unwrapEnvelope({ ok: false, entities: [{ b: 1 }] })).toEqual([{ b: 1 }]);
  });

  test("a bare scalar top-level value passes through unchanged (a non-object item)", () => {
    expect(unwrapEnvelope("not an object")).toEqual(["not an object"]);
  });
});

describe("detectKind", () => {
  test("a string blueprint member marks an entity", () => {
    expect(detectKind({ blueprint: "service", identifier: "x" })).toBe("entity");
  });

  test("anything else is a blueprint", () => {
    expect(detectKind({ identifier: "service" })).toBe("blueprint");
    expect(detectKind({ blueprint: 123 })).toBe("blueprint");
  });
});

describe("sanitizeDocument", () => {
  test("drops the full common strip list plus underscore-prefixed keys", () => {
    const { body, stripped } = sanitizeDocument(
      {
        identifier: "service",
        id: 1,
        organization: "org",
        createdAt: 1,
        createdBy: 1,
        updatedAt: 1,
        updatedBy: 1,
        system: false,
        creatorName: "Alice",
        creatorDeleted: false,
        findings: [],
        blueprintId: 1,
        scorecards: [],
        _meta: {},
      },
      "blueprint",
    );
    expect(body).toEqual({ identifier: "service" });
    expect(stripped.sort()).toEqual(
      [
        "id",
        "organization",
        "createdAt",
        "createdBy",
        "updatedAt",
        "updatedBy",
        "system",
        "creatorName",
        "creatorDeleted",
        "findings",
        "blueprintId",
        "scorecards",
        "_meta",
      ].sort(),
    );
  });

  test("blueprint documents additionally drop teamInheritance and changelogDestination", () => {
    const { body, stripped } = sanitizeDocument(
      { identifier: "x", teamInheritance: {}, changelogDestination: {} },
      "blueprint",
    );
    expect(body).toEqual({ identifier: "x" });
    expect(stripped).toEqual(expect.arrayContaining(["teamInheritance", "changelogDestination"]));
  });

  test("entity documents keep teamInheritance/changelogDestination (blueprint-only keys)", () => {
    const { body, stripped } = sanitizeDocument({ blueprint: "b", teamInheritance: {} }, "entity");
    expect(body).toEqual({ blueprint: "b", teamInheritance: {} });
    expect(stripped).toEqual([]);
  });
});

describe("stripComputedProperties", () => {
  test("drops declared computed ids from properties and reports them", () => {
    const body = { blueprint: "service", properties: { language: "kotlin", teamName: "Platform" } };
    const { body: next, strippedComputed } = stripComputedProperties(body, { service: new Set(["teamName"]) });
    expect(next.properties).toEqual({ language: "kotlin" });
    expect(strippedComputed).toEqual(["teamName"]);
  });

  test("no-ops when the blueprint has no computed ids, or properties is absent", () => {
    const body = { blueprint: "service", properties: { language: "kotlin" } };
    expect(stripComputedProperties(body, {})).toEqual({ body, strippedComputed: [] });
    const noProps = { blueprint: "service" };
    expect(stripComputedProperties(noProps, { service: new Set(["x"]) })).toEqual({
      body: noProps,
      strippedComputed: [],
    });
  });
});

describe("buildComputedIdsByBlueprint", () => {
  test("reads batch blueprint bodies' three definition maps", () => {
    const docs: OntologyDocument[] = [
      {
        index: 0,
        kind: "blueprint",
        identifier: "service",
        body: { mirrorProperties: { a: {} }, calculationProperties: { b: {} }, aggregationProperties: { c: {} } },
        stripped: [],
        strippedComputed: [],
        source: "s",
      },
    ];
    const map = buildComputedIdsByBlueprint(docs, []);
    expect(map.service).toEqual(new Set(["a", "b", "c"]));
  });

  test("merges in registry blueprints for identifiers the batch doesn't redefine", () => {
    const registryBlueprint = {
      identifier: "team",
      mirrorProperties: {},
      calculationProperties: { size: { title: "Size", type: "number" } },
      aggregationProperties: {},
    } as unknown as Blueprint;
    const map = buildComputedIdsByBlueprint([], [registryBlueprint]);
    expect(map.team).toEqual(new Set(["size"]));
  });
});

describe("parseOntologySources", () => {
  test("a JSON syntax error is ONE error naming the source", () => {
    const { documents, errors } = parseOntologySources([{ label: "bad.json", text: "{not json" }]);
    expect(documents).toEqual([]);
    expect(errors).toHaveLength(1);
    expect(errors[0].source).toBe("bad.json");
  });

  test("a non-object item names its position", () => {
    const { errors } = parseOntologySources([{ label: "s", text: JSON.stringify([{ identifier: "ok" }, "oops"]) }]);
    expect(errors).toEqual([{ source: "s", index: 1, message: expect.stringContaining("Document 2") }]);
  });

  test("assigns global sequential indexes across sources", () => {
    const { documents } = parseOntologySources([
      { label: "a", text: JSON.stringify([blueprintDoc({ identifier: "b1" }), blueprintDoc({ identifier: "b2" })]) },
      { label: "b", text: JSON.stringify(blueprintDoc({ identifier: "b3" })) },
    ]);
    expect(documents.map((d) => d.index)).toEqual([0, 1, 2]);
    expect(documents.map((d) => d.identifier)).toEqual(["b1", "b2", "b3"]);
  });

  test("blank sources are skipped", () => {
    const { documents, errors } = parseOntologySources([{ label: "empty", text: "   " }]);
    expect(documents).toEqual([]);
    expect(errors).toEqual([]);
  });

  test("strips computed properties from an entity targeting a batch blueprint", () => {
    const { documents } = parseOntologySources([
      {
        label: "s",
        text: JSON.stringify({
          blueprints: [blueprintDoc({ mirrorProperties: { teamName: { title: "Team", path: "x" } } })],
          entities: [entityDoc({ properties: { language: "kotlin", teamName: "Platform" } })],
        }),
      },
    ]);
    const entity = documents.find((d) => d.kind === "entity")!;
    expect(entity.body.properties).toEqual({ language: "kotlin" });
    expect(entity.strippedComputed).toEqual(["teamName"]);
  });

  test("strips computed properties from a REGISTRY blueprint the batch doesn't redefine", () => {
    const registryBlueprint = {
      identifier: "service",
      mirrorProperties: { teamName: { title: "Team", path: "x" } },
      calculationProperties: {},
      aggregationProperties: {},
    } as unknown as Blueprint;
    const { documents } = parseOntologySources(
      [{ label: "s", text: JSON.stringify(entityDoc({ properties: { language: "kotlin", teamName: "Platform" } })) }],
      [registryBlueprint],
    );
    expect(documents[0].body.properties).toEqual({ language: "kotlin" });
    expect(documents[0].strippedComputed).toEqual(["teamName"]);
  });
});

describe("chunked", () => {
  test("splits at the exact boundary — 200 stays one chunk, 201 makes two", () => {
    expect(chunked(Array.from({ length: IMPORT_CHUNK_SIZE }), IMPORT_CHUNK_SIZE)).toHaveLength(1);
    const chunks = chunked(Array.from({ length: IMPORT_CHUNK_SIZE + 1 }), IMPORT_CHUNK_SIZE);
    expect(chunks).toHaveLength(2);
    expect(chunks[0]).toHaveLength(IMPORT_CHUNK_SIZE);
    expect(chunks[1]).toHaveLength(1);
  });

  test("an empty array yields no chunks", () => {
    expect(chunked([], IMPORT_CHUNK_SIZE)).toEqual([]);
  });
});

describe("summarizeStripped", () => {
  test("counts documents and collects distinct sorted key names", () => {
    const docs: OntologyDocument[] = [
      { index: 0, kind: "blueprint", body: {}, stripped: ["id", "system"], strippedComputed: [], source: "a" },
      { index: 1, kind: "entity", body: {}, stripped: ["id"], strippedComputed: ["teamName"], source: "a" },
      { index: 2, kind: "entity", body: {}, stripped: [], strippedComputed: [], source: "a" },
    ];
    expect(summarizeStripped(docs)).toEqual({
      strippedCount: 2,
      strippedKeys: ["id", "system"],
      strippedComputedCount: 1,
      strippedComputedKeys: ["teamName"],
    });
  });

  test("an empty batch summarizes to all zeros", () => {
    expect(summarizeStripped([])).toEqual({
      strippedCount: 0,
      strippedKeys: [],
      strippedComputedCount: 0,
      strippedComputedKeys: [],
    });
  });
});

function makeDoc(kind: "blueprint" | "entity", index: number, identifier: string): OntologyDocument {
  return { index, kind, identifier, blueprint: kind === "entity" ? "service" : undefined, body: { identifier }, stripped: [], strippedComputed: [], source: "s" };
}

describe("runImportBatch", () => {
  test("sends blueprint chunks then entity chunks, mapping chunk-local result indexes back to global ones", async () => {
    const documents = [makeDoc("blueprint", 0, "b1"), makeDoc("entity", 1, "e1")];
    const importBlueprintsChunk = vi.fn().mockResolvedValue({ results: [{ index: 0, identifier: "b1", status: "CREATED", id: 10 }] });
    const importEntitiesChunk = vi.fn().mockResolvedValue({ results: [{ index: 0, blueprint: "service", identifier: "e1", status: "CREATED", id: 20 }] });

    const rows = await runImportBatch({ documents, replaceExisting: false, admin: true, importBlueprintsChunk, importEntitiesChunk });

    expect(rows).toEqual([
      { index: 0, kind: "blueprint", identifier: "b1", source: "s", status: "CREATED", id: 10, message: undefined },
      { index: 1, kind: "entity", identifier: "e1", blueprint: "service", source: "s", status: "CREATED", id: 20, message: undefined, findings: undefined },
    ]);
    expect(importBlueprintsChunk).toHaveBeenCalledWith([{ identifier: "b1" }], false);
    expect(importEntitiesChunk).toHaveBeenCalledWith([{ identifier: "e1" }], false);
  });

  test("a non-admin caller marks every blueprint document FORBIDDEN client-side and never calls the blueprint endpoint; entities still import", async () => {
    const documents = [makeDoc("blueprint", 0, "b1"), makeDoc("entity", 1, "e1")];
    const importBlueprintsChunk = vi.fn();
    const importEntitiesChunk = vi.fn().mockResolvedValue({ results: [{ index: 0, identifier: "e1", status: "CREATED" }] });

    const rows = await runImportBatch({ documents, replaceExisting: false, admin: false, importBlueprintsChunk, importEntitiesChunk });

    expect(importBlueprintsChunk).not.toHaveBeenCalled();
    expect(rows[0]).toEqual({ index: 0, kind: "blueprint", identifier: "b1", blueprint: undefined, source: "s", status: "FORBIDDEN" });
    expect(rows[1].status).toBe("CREATED");
  });

  test("a real 403 mid-call maps that chunk's documents onto the same FORBIDDEN rows", async () => {
    const documents = [makeDoc("blueprint", 0, "b1")];
    const importBlueprintsChunk = vi.fn().mockRejectedValue(new ApiError(403, { title: "Forbidden" }));
    const importEntitiesChunk = vi.fn().mockResolvedValue({ results: [] });

    const rows = await runImportBatch({ documents, replaceExisting: false, admin: true, importBlueprintsChunk, importEntitiesChunk });

    expect(rows).toEqual([{ index: 0, kind: "blueprint", identifier: "b1", blueprint: undefined, source: "s", status: "FORBIDDEN" }]);
  });

  test("a non-403 failure rethrows, but rows reported so far via onRows survive", async () => {
    const documents = [makeDoc("blueprint", 0, "b1"), makeDoc("entity", 1, "e1")];
    const importBlueprintsChunk = vi.fn().mockResolvedValue({ results: [{ index: 0, identifier: "b1", status: "CREATED" }] });
    const importEntitiesChunk = vi.fn().mockRejectedValue(new ApiError(500, { title: "Server error" }));
    const seen: unknown[] = [];

    await expect(
      runImportBatch({
        documents,
        replaceExisting: false,
        admin: true,
        importBlueprintsChunk,
        importEntitiesChunk,
        onRows: (rows) => seen.push(rows),
      }),
    ).rejects.toBeInstanceOf(ApiError);

    expect(seen).toHaveLength(1);
    expect((seen[0] as { status: string }[])[0].status).toBe("CREATED");
  });

  test("reports progress once per chunk and sorts the final rows by global index", async () => {
    const documents = [makeDoc("entity", 0, "e1"), makeDoc("blueprint", 1, "b1")];
    const importBlueprintsChunk = vi.fn().mockResolvedValue({ results: [{ index: 0, identifier: "b1", status: "CREATED" }] });
    const importEntitiesChunk = vi.fn().mockResolvedValue({ results: [{ index: 0, identifier: "e1", status: "CREATED" }] });
    const onProgress = vi.fn();

    const rows = await runImportBatch({ documents, replaceExisting: true, admin: true, importBlueprintsChunk, importEntitiesChunk, onProgress });

    expect(onProgress).toHaveBeenCalledWith(1, 2);
    expect(onProgress).toHaveBeenCalledWith(2, 2);
    expect(rows.map((r) => r.index)).toEqual([0, 1]);
    expect(importBlueprintsChunk).toHaveBeenCalledWith(expect.anything(), true);
  });

  test("an empty batch resolves to no rows without calling either endpoint", async () => {
    const importBlueprintsChunk = vi.fn();
    const importEntitiesChunk = vi.fn();
    const rows = await runImportBatch({ documents: [], replaceExisting: false, admin: true, importBlueprintsChunk, importEntitiesChunk });
    expect(rows).toEqual([]);
    expect(importBlueprintsChunk).not.toHaveBeenCalled();
    expect(importEntitiesChunk).not.toHaveBeenCalled();
  });

  test("201 blueprint documents invoke the blueprint caller twice (200 + 1) with rows carrying global indexes 0..200 in order", async () => {
    const documents = Array.from({ length: 201 }, (_, i) => makeDoc("blueprint", i, `bp${i}`));
    let callCount = 0;
    const importBlueprintsChunk = vi.fn((chunk: unknown[]) => {
      callCount += 1;
      // First call: 200 documents, second call: 1 document
      return Promise.resolve({
        results: (chunk as Record<string, string>[]).map((doc, index) => ({
          index,
          identifier: doc.identifier,
          status: "CREATED" as const,
          id: callCount === 1 ? 10 + index : 210,
        })),
      });
    });
    const importEntitiesChunk = vi.fn().mockResolvedValue({ results: [] });

    const rows = await runImportBatch({ documents, replaceExisting: false, admin: true, importBlueprintsChunk, importEntitiesChunk });

    expect(importBlueprintsChunk).toHaveBeenCalledTimes(2);
    expect(rows).toHaveLength(201);
    expect(rows.map((r) => r.index)).toEqual(Array.from({ length: 201 }, (_, i) => i));
    expect(rows.map((r) => r.status)).toEqual(Array(201).fill("CREATED"));
  });
});
