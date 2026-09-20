import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor } from "@testing-library/react";
import { QueryClient } from "@tanstack/react-query";
import SyncEntityModal from "./SyncEntityModal";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";
import type { EntitySyncTarget } from "../utils/entitySync";

type FetchMock = ReturnType<typeof vi.fn>;

const TARGET: EntitySyncTarget = {
  id: 1,
  blueprint: "service",
  identifier: "checkout",
  sourceUrl: "https://raw.githubusercontent.com/acme/svc/main/checkout.json",
  updatedAt: 2000,
  lastSyncedAt: 1000,
};

const BLUEPRINTS = [
  {
    id: 1,
    identifier: "service",
    title: "Service",
    schema: { properties: { language: { type: "string", title: "Language" } }, required: [] },
    relations: {},
    mirrorProperties: {},
    calculationProperties: {},
    aggregationProperties: {},
  },
];

const DETAIL = {
  id: 1,
  blueprint: "service",
  blueprintId: 1,
  identifier: "checkout",
  title: "Old title",
  properties: { language: "kotlin" },
  relations: {},
  findings: [],
  createdBy: 1,
  creatorName: "Alice",
  creatorDeleted: false,
  createdAt: 500,
  updatedAt: 2000,
  sourceUrl: TARGET.sourceUrl,
  lastSyncedAt: 1000,
};

const SYNC_STATE = {
  sourceUrl: TARGET.sourceUrl,
  lastSyncedAt: 1000,
  syncedDocument: {
    blueprint: "service",
    identifier: "checkout",
    title: "Base title",
    properties: { language: "kotlin" },
    relations: {},
  },
};

function remoteDoc(title: string) {
  return { blueprint: "service", identifier: "checkout", title, properties: { language: "java" }, relations: {} };
}

function mockRoutes(
  mockFetch: FetchMock,
  overrides: Partial<Record<"fetch" | "check" | "state" | "detail" | "sync" | "blueprints", Response>> = {},
) {
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    if (url === "/api/v1/blueprints" && method === "GET")
      return Promise.resolve(overrides.blueprints ?? jsonResponse(200, { items: BLUEPRINTS }));
    if (url === "/api/v1/entities/fetch" && method === "POST") {
      return Promise.resolve(overrides.fetch ?? jsonResponse(200, { content: JSON.stringify(remoteDoc("New title")) }));
    }
    if (url === "/api/v1/entities/import/check" && method === "POST") {
      return Promise.resolve(
        overrides.check ??
          jsonResponse(200, {
            results: [{ index: 0, blueprint: "service", identifier: "checkout", status: "UPDATED", id: 1 }],
          }),
      );
    }
    if (url === "/api/v1/entities/1/sync" && method === "GET") {
      return Promise.resolve(overrides.state ?? jsonResponse(200, SYNC_STATE));
    }
    if (url === "/api/v1/entities/1/sync" && method === "POST") {
      return Promise.resolve(overrides.sync ?? new Response(null, { status: 204 }));
    }
    if (url === "/api/v1/entities/1" && method === "GET") {
      return Promise.resolve(overrides.detail ?? jsonResponse(200, DETAIL));
    }
    return Promise.resolve(jsonResponse(404, {}));
  });
}

describe("SyncEntityModal", () => {
  let mockFetch: FetchMock;
  const onClose = vi.fn();
  const onCompleted = vi.fn();

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("toadie.auth.token", "fake-token");
    onClose.mockReset();
    onCompleted.mockReset();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  function renderModal(target: EntitySyncTarget | null = TARGET) {
    return renderWithProviders(<SyncEntityModal target={target} onClose={onClose} onCompleted={onCompleted} />);
  }

  test("shows both changed-side badges, the diff, and syncs the picked document on confirm", async () => {
    const invalidateQueriesSpy = vi.spyOn(QueryClient.prototype, "invalidateQueries");
    mockRoutes(mockFetch);
    const user = userEvent.setup();
    renderModal();

    // The baseline differs from the source copy (changed at source) AND updatedAt >
    // lastSyncedAt (changed in Toadie) — both sides light up.
    expect(await screen.findByText("Changed at source")).toBeInTheDocument();
    expect(screen.getByText("Changed in Toadie")).toBeInTheDocument();

    const confirm = screen.getByRole("button", { name: "Overwrite stored copy" });
    expect(confirm).toBeEnabled();
    await user.click(confirm);

    await waitFor(() => expect(onClose).toHaveBeenCalled());
    const syncCall = mockFetch.mock.calls.find(
      ([url, init]) => url === "/api/v1/entities/1/sync" && (init as RequestInit)?.method === "POST",
    );
    expect(syncCall).toBeDefined();
    const body = JSON.parse((syncCall![1] as RequestInit).body as string) as { document: Record<string, unknown> };
    // The whole picked document — properties and relations included, not just identity fields.
    expect(body.document).toEqual(remoteDoc("New title"));
    await waitFor(() => expect(invalidateQueriesSpy).toHaveBeenCalledWith({ queryKey: ["entities"] }));
    expect(onCompleted).toHaveBeenCalled();

    invalidateQueriesSpy.mockRestore();
  });

  test("computed values in the remote document are stripped and the note renders", async () => {
    const blueprintsWithMirror = [
      { ...BLUEPRINTS[0], mirrorProperties: { region: { title: "Region", path: "owner.region" } } },
    ];
    mockRoutes(mockFetch, {
      blueprints: jsonResponse(200, { items: blueprintsWithMirror }),
      fetch: jsonResponse(200, {
        content: JSON.stringify({ ...remoteDoc("New title"), properties: { language: "java", region: "eu-west" } }),
      }),
    });
    renderModal();

    expect(
      await screen.findByText("Computed property values removed from 1 entities: region"),
    ).toBeInTheDocument();
  });

  test("a post-confirm 400 with findings renders them under the Sync failed title", async () => {
    mockRoutes(mockFetch, {
      sync: jsonResponse(400, {
        title: "Bad Request",
        status: 400,
        detail: "invalid",
        findings: [{ field: "properties.language", code: "REQUIRED_MISSING", message: "Required" }],
      }),
    });
    const user = userEvent.setup();
    renderModal();

    await user.click(await screen.findByRole("button", { name: "Overwrite stored copy" }));

    expect(await screen.findByText("Sync failed")).toBeInTheDocument();
    expect(screen.getByText("properties.language: Required")).toBeInTheDocument();
    expect(onClose).not.toHaveBeenCalled();
  });

  test("identical current and source copies read as in sync and disable the overwrite", async () => {
    const identical = {
      blueprint: "service",
      identifier: "checkout",
      title: "Old title",
      properties: { language: "kotlin" },
      relations: {},
    };
    mockRoutes(mockFetch, { fetch: jsonResponse(200, { content: JSON.stringify(identical) }) });
    renderModal();

    expect(await screen.findByText("Toadie and the source are in sync")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Overwrite stored copy" })).toBeDisabled();
  });

  test("a refused fetch shows the public-https message and disables the overwrite", async () => {
    mockRoutes(mockFetch, { fetch: jsonResponse(400, { title: "Bad Request", status: 400, detail: "blocked" }) });
    renderModal();

    expect(await screen.findByText(/must be a public https address/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Overwrite stored copy" })).toBeDisabled();
  });

  test("unparsable source text reads as parseFailed", async () => {
    mockRoutes(mockFetch, { fetch: jsonResponse(200, { content: "{not json" }) });
    renderModal();

    expect(await screen.findByText("The source document is not valid JSON.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Overwrite stored copy" })).toBeDisabled();
  });

  test("several documents ({entities:[...]}) are picked by identifier", async () => {
    mockRoutes(mockFetch, {
      fetch: jsonResponse(200, {
        content: JSON.stringify({
          entities: [{ ...remoteDoc("Ignored"), identifier: "other" }, { ...remoteDoc("Picked"), identifier: "CHECKOUT" }],
        }),
      }),
    });
    renderModal();

    expect(await screen.findByText("Changed at source")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Overwrite stored copy" })).toBeEnabled();
  });

  test("a document naming a different blueprint reads as blueprintMismatch", async () => {
    mockRoutes(mockFetch, {
      fetch: jsonResponse(200, { content: JSON.stringify({ ...remoteDoc("Other"), blueprint: "other" }) }),
    });
    renderModal();

    expect(
      await screen.findByText('The source document names a different blueprint than "service".'),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Overwrite stored copy" })).toBeDisabled();
  });

  test("a no-longer-matching source document reads as noMatch", async () => {
    mockRoutes(mockFetch, {
      fetch: jsonResponse(200, {
        content: JSON.stringify({
          entities: [{ ...remoteDoc("A"), identifier: "other-a" }, { ...remoteDoc("B"), identifier: "other-b" }],
        }),
      }),
    });
    renderModal();

    expect(await screen.findByText("The source document no longer contains this entity.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Overwrite stored copy" })).toBeDisabled();
  });

  test("a pre-flight INVALID row lists its findings and disables the overwrite", async () => {
    mockRoutes(mockFetch, {
      check: jsonResponse(200, {
        results: [
          {
            index: 0,
            blueprint: "service",
            identifier: "checkout",
            status: "INVALID",
            findings: [{ code: "REQUIRED_MISSING", field: "properties.language", message: "Required" }],
          },
        ],
      }),
    });
    renderModal();

    expect(await screen.findByText("The source copy was refused")).toBeInTheDocument();
    expect(screen.getByText("properties.language: Required")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Overwrite stored copy" })).toBeDisabled();
  });

  test("stays closed without a target", () => {
    renderModal(null);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(mockFetch).not.toHaveBeenCalled();
  });
});
