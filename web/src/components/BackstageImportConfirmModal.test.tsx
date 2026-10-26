import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, within } from "@testing-library/react";
import { mockFetch, type MockRoute } from "../test/http";
import { renderWithProviders } from "../test/render";
import type { ConvertedBatch } from "../utils/conversionBatches";
import { convertCatalogFiles } from "../utils/conversionBatches";
import BackstageImportConfirmModal from "./BackstageImportConfirmModal";

const FILES = [
  { kind: "Component" as const, metadata: { name: "checkout" }, spec: { type: "service", lifecycle: "production", owner: "group:default/platform" } },
  { kind: "Component" as const, metadata: { name: "billing" }, spec: { type: "service", lifecycle: "production", owner: "group:default/platform" } },
];

const documentOf = (identifier: string) => ({ blueprint: "service", identifier, title: identifier, properties: {}, relations: {} });

const rowOf = (index: number, name: string, existing?: { id: number; updatedAt: number }) => ({
  index,
  status: "CONVERTED",
  kind: "Component",
  namespace: "default",
  name,
  blueprint: "service",
  identifier: name,
  findings: [],
  unmapped: [],
  ...(existing ? { existingId: existing.id, existingUpdatedAt: existing.updatedAt } : {}),
});

// checkout matches a stored entity (token 1700); billing matches none (token 0 on the wire).
const CONVERTED = {
  documents: [documentOf("checkout"), documentOf("billing")],
  rows: [rowOf(0, "checkout", { id: 12, updatedAt: 1700 }), rowOf(1, "billing")],
};

const entityRow = (index: number, identifier: string, status: string, id?: number) => ({
  index,
  blueprint: "service",
  identifier,
  status,
  ...(id === undefined ? {} : { id }),
});

const callsTo = (fetchStub: ReturnType<typeof mockFetch>, path: string) =>
  fetchStub.mock.calls.filter(([url, init]) => url === path && init?.method === "POST");

function mount(routes: MockRoute[]) {
  const fetchStub = mockFetch([
    ...routes,
    { method: "POST", match: "/api/v1/conversion-mappings/3/entity-documents", respond: { status: 200, body: CONVERTED } },
  ]);
  vi.stubGlobal("fetch", fetchStub);
  const convert = (): Promise<ConvertedBatch> => convertCatalogFiles(3, FILES, true);
  renderWithProviders(<BackstageImportConfirmModal convert={convert} merge sourceLabel="Backstage" onClose={() => undefined} />);
  return fetchStub;
}

describe("BackstageImportConfirmModal", () => {
  beforeEach(() => {
    localStorage.setItem("toadie.auth.token", "fake-token");
    localStorage.setItem("toadie.auth.roles", JSON.stringify(["ADMIN"]));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("converts exactly once across open and Confirm, and the import body carries the previewed tokens", async () => {
    const fetchStub = mount([
      { method: "POST", match: "/api/v1/entities/import/check", respond: { status: 200, body: { results: [entityRow(0, "checkout", "UPDATED", 12), entityRow(1, "billing", "CREATED")] } } },
      { method: "POST", match: "/api/v1/entities/import", respond: { status: 200, body: { results: [entityRow(0, "checkout", "UPDATED", 12), entityRow(1, "billing", "CREATED", 40)] } } },
    ]);
    const user = userEvent.setup();

    const dialog = await screen.findByRole("dialog", { name: "Import into Port" });
    expect(await within(dialog).findByText("Would be updated")).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Confirm import" }));
    expect(await within(dialog).findByText("Updated")).toBeInTheDocument();

    expect(callsTo(fetchStub, "/api/v1/conversion-mappings/3/entity-documents")).toHaveLength(1);
    const expected = [{ index: 0, updatedAt: 1700 }, { index: 1, updatedAt: 0 }];
    const checkBody = JSON.parse((callsTo(fetchStub, "/api/v1/entities/import/check")[0][1] as RequestInit).body as string);
    const importBody = JSON.parse((callsTo(fetchStub, "/api/v1/entities/import")[0][1] as RequestInit).body as string);
    expect(checkBody.expected).toEqual(expected);
    expect(importBody).toMatchObject({ replaceExisting: true, expected });
    expect(importBody.documents).toEqual(CONVERTED.documents);
  });

  test("a STALE row renders red as Changed meanwhile and the rest of the batch still stores", async () => {
    mount([
      { method: "POST", match: "/api/v1/entities/import/check", respond: { status: 200, body: { results: [entityRow(0, "checkout", "UPDATED", 12), entityRow(1, "billing", "CREATED")] } } },
      {
        method: "POST",
        match: "/api/v1/entities/import",
        respond: { status: 200, body: { results: [{ ...entityRow(0, "checkout", "STALE", 12), message: "Changed since it was converted" }, entityRow(1, "billing", "CREATED", 40)] } },
      },
    ]);
    const user = userEvent.setup();

    const dialog = await screen.findByRole("dialog", { name: "Import into Port" });
    await within(dialog).findByText("Would be updated");
    await user.click(within(dialog).getByRole("button", { name: "Confirm import" }));

    const badge = await within(dialog).findByText("Changed meanwhile");
    expect(badge.closest("[class*='Badge-root']")!.getAttribute("style")).toContain("red");
    expect(within(dialog).getByText("Created")).toBeInTheDocument();
  });
});
