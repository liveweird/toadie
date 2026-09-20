import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor } from "@testing-library/react";
import { QueryClient } from "@tanstack/react-query";
import SyncBlueprintModal from "./SyncBlueprintModal";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";
import type { BlueprintSyncTarget } from "../utils/blueprintSync";

type FetchMock = ReturnType<typeof vi.fn>;

const TARGET: BlueprintSyncTarget = {
  id: 1,
  identifier: "service",
  sourceUrl: "https://raw.githubusercontent.com/acme/ontology/main/service.json",
  updatedAt: 2000,
  lastSyncedAt: 1000,
  system: false,
};

const DETAIL = {
  id: 1,
  identifier: "service",
  title: "Old title",
  schema: { properties: { language: { type: "string", title: "Language" } }, required: [] },
  relations: {},
  mirrorProperties: {},
  calculationProperties: {},
  aggregationProperties: {},
  createdBy: 1,
  creatorName: "Alice",
  creatorDeleted: false,
  createdAt: 500,
  updatedAt: 2000,
  system: false,
  sourceUrl: TARGET.sourceUrl,
  lastSyncedAt: 1000,
};

const SYNC_STATE = {
  sourceUrl: TARGET.sourceUrl,
  lastSyncedAt: 1000,
  syncedDocument: {
    identifier: "service",
    title: "Base title",
    schema: { properties: {}, required: [] },
    relations: {},
  },
};

function remoteDoc(title: string) {
  return { identifier: "service", title, schema: { properties: {}, required: [] }, relations: {} };
}

function mockRoutes(
  mockFetch: FetchMock,
  overrides: Partial<Record<"fetch" | "check" | "state" | "detail" | "sync", Response>> = {},
) {
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    if (url === "/api/v1/blueprints/fetch" && method === "POST") {
      return Promise.resolve(overrides.fetch ?? jsonResponse(200, { content: JSON.stringify(remoteDoc("New title")) }));
    }
    if (url === "/api/v1/blueprints/import/check" && method === "POST") {
      return Promise.resolve(
        overrides.check ?? jsonResponse(200, { results: [{ index: 0, identifier: "service", status: "UPDATED", id: 1 }] }),
      );
    }
    if (url === "/api/v1/blueprints/1/sync" && method === "GET") {
      return Promise.resolve(overrides.state ?? jsonResponse(200, SYNC_STATE));
    }
    if (url === "/api/v1/blueprints/1/sync" && method === "POST") {
      return Promise.resolve(overrides.sync ?? new Response(null, { status: 204 }));
    }
    if (url === "/api/v1/blueprints/1" && method === "GET") {
      return Promise.resolve(overrides.detail ?? jsonResponse(200, DETAIL));
    }
    return Promise.resolve(jsonResponse(404, {}));
  });
}

describe("SyncBlueprintModal", () => {
  let mockFetch: FetchMock;
  const onClose = vi.fn();
  const onCompleted = vi.fn();

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("toadie.auth.token", "fake-token");
    localStorage.setItem("toadie.auth.roles", JSON.stringify(["ADMIN"]));
    onClose.mockReset();
    onCompleted.mockReset();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  function renderModal(target: BlueprintSyncTarget | null = TARGET) {
    return renderWithProviders(<SyncBlueprintModal target={target} onClose={onClose} onCompleted={onCompleted} />);
  }

  test("shows both changed-side badges, the diff, and syncs the picked document on confirm, invalidating BOTH registries", async () => {
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
      ([url, init]) => url === "/api/v1/blueprints/1/sync" && (init as RequestInit)?.method === "POST",
    );
    expect(syncCall).toBeDefined();
    const body = JSON.parse((syncCall![1] as RequestInit).body as string) as { document: Record<string, unknown> };
    expect(body.document).toEqual(remoteDoc("New title"));
    await waitFor(() => expect(invalidateQueriesSpy).toHaveBeenCalledWith({ queryKey: ["blueprints"] }));
    expect(invalidateQueriesSpy).toHaveBeenCalledWith({ queryKey: ["entities"] });
    expect(onCompleted).toHaveBeenCalled();

    invalidateQueriesSpy.mockRestore();
  });

  test("a post-confirm error renders the message under the Sync failed title", async () => {
    mockRoutes(mockFetch, {
      sync: jsonResponse(409, { title: "Conflict", status: 409, detail: "An active blueprint already holds this identifier" }),
    });
    const user = userEvent.setup();
    renderModal();

    await user.click(await screen.findByRole("button", { name: "Overwrite stored copy" }));

    expect(await screen.findByText("Sync failed")).toBeInTheDocument();
    expect(screen.getByText("A blueprint with this identifier already exists")).toBeInTheDocument();
    expect(onClose).not.toHaveBeenCalled();
  });

  test("identical current and source copies read as in sync and disable the overwrite", async () => {
    const identical = {
      identifier: "service",
      title: "Old title",
      schema: { properties: { language: { type: "string", title: "Language" } }, required: [] },
      relations: {},
      mirrorProperties: {},
      calculationProperties: {},
      aggregationProperties: {},
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

  test("several documents ({blueprints:[...]}) are picked by identifier", async () => {
    mockRoutes(mockFetch, {
      fetch: jsonResponse(200, {
        content: JSON.stringify({
          blueprints: [{ ...remoteDoc("Ignored"), identifier: "other" }, { ...remoteDoc("Picked"), identifier: "SERVICE" }],
        }),
      }),
    });
    renderModal();

    expect(await screen.findByText("Changed at source")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Overwrite stored copy" })).toBeEnabled();
  });

  test("a no-longer-matching source document reads as noMatch", async () => {
    mockRoutes(mockFetch, {
      fetch: jsonResponse(200, {
        content: JSON.stringify({
          blueprints: [{ ...remoteDoc("A"), identifier: "other-a" }, { ...remoteDoc("B"), identifier: "other-b" }],
        }),
      }),
    });
    renderModal();

    expect(await screen.findByText("The source document no longer contains this blueprint.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Overwrite stored copy" })).toBeDisabled();
  });

  test("a pre-flight INVALID row shows the message and disables the overwrite", async () => {
    mockRoutes(mockFetch, {
      check: jsonResponse(200, {
        results: [
          {
            index: 0,
            identifier: "service",
            status: "INVALID",
            message: "hierarchyRelations names an unknown hierarchy 'composition'",
          },
        ],
      }),
    });
    renderModal();

    expect(await screen.findByText("The source copy was refused")).toBeInTheDocument();
    expect(
      screen.getByText("Syncing would fail — the source copy does not satisfy the blueprint rules. Fix it at the source before syncing again."),
    ).toBeInTheDocument();
    expect(screen.getByText("hierarchyRelations names an unknown hierarchy 'composition'")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Overwrite stored copy" })).toBeDisabled();
  });

  test("a remote document without hierarchyRelations keeps the stored map and shows the hint", async () => {
    mockRoutes(mockFetch, {
      // The map lives on the LIVE definition; the sync baseline plays no part in the keep rule.
      detail: jsonResponse(200, { ...DETAIL, hierarchyRelations: { composition: "parent" } }),
    });
    const user = userEvent.setup();
    renderModal();

    expect(
      await screen.findByText(
        "Hierarchy relations are a Toadie-only extension; the source document does not carry them, so the stored map is kept.",
      ),
    ).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Overwrite stored copy" }));

    await waitFor(() => expect(onClose).toHaveBeenCalled());
    const syncCall = mockFetch.mock.calls.find(
      ([url, init]) => url === "/api/v1/blueprints/1/sync" && (init as RequestInit)?.method === "POST",
    );
    const body = JSON.parse((syncCall![1] as RequestInit).body as string) as { document: Record<string, unknown> };
    expect(body.document).toEqual({ ...remoteDoc("New title"), hierarchyRelations: { composition: "parent" } });
  });

  test("prefers the current definition over the stale baseline when deciding whether a hierarchy map is kept", async () => {
    mockRoutes(mockFetch, {
      state: jsonResponse(200, {
        ...SYNC_STATE,
        syncedDocument: { ...SYNC_STATE.syncedDocument, hierarchyRelations: { composition: "parent" } },
      }),
      detail: jsonResponse(200, DETAIL), // the cleared map is ABSENT on the wire, never `{}`
    });
    const user = userEvent.setup();
    renderModal();

    // No hint: the live definition carries no map, and the stale baseline must not stand in for it
    expect(
      screen.queryByText(
        /Hierarchy relations are a Toadie-only extension/,
      ),
    ).not.toBeInTheDocument();

    const confirmButton = await screen.findByRole("button", { name: "Overwrite stored copy" });
    await user.click(confirmButton);

    await waitFor(() => expect(onClose).toHaveBeenCalled());
    const syncCall = mockFetch.mock.calls.find(
      ([url, init]) => url === "/api/v1/blueprints/1/sync" && (init as RequestInit)?.method === "POST",
    );
    const body = JSON.parse((syncCall![1] as RequestInit).body as string) as { document: Record<string, unknown> };
    // The sent document carries no map: the server keeps or clears from its own live row
    expect(body.document).not.toHaveProperty("hierarchyRelations");
  });

  test("Esc cannot dismiss the modal mid-sync; it closes once the POST settles", async () => {
    mockRoutes(mockFetch);
    // Hold the sync POST open so the busy state is observable.
    let releaseSync: (response: Response) => void = () => {};
    const base = mockFetch.getMockImplementation() as (
      url: string,
      init?: RequestInit,
    ) => Promise<Response>;
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url === "/api/v1/blueprints/1/sync" && init?.method === "POST") {
        return new Promise<Response>((resolve) => {
          releaseSync = resolve;
        });
      }
      return base(url, init);
    });
    const user = userEvent.setup();
    renderModal();

    await user.click(await screen.findByRole("button", { name: "Overwrite stored copy" }));
    await user.keyboard("{Escape}");
    expect(onClose).not.toHaveBeenCalled();

    releaseSync(new Response(null, { status: 204 }));
    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });

  test("exposes a named loading status while the sync state is pending", async () => {
    mockRoutes(mockFetch);
    let releaseState: (response: Response) => void = () => {};
    const statePromise = new Promise<Response>((resolve) => {
      releaseState = resolve;
    });
    const base = mockFetch.getMockImplementation() as (
      url: string,
      init?: RequestInit,
    ) => Promise<Response>;
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      const method = init?.method ?? "GET";
      if (url === "/api/v1/blueprints/1/sync" && method === "GET") return statePromise;
      return base(url, init);
    });
    renderModal();

    expect(
      await screen.findByRole("status", { name: "Loading the source copy" }),
    ).toBeInTheDocument();

    releaseState(jsonResponse(200, SYNC_STATE));
    await waitFor(() => expect(screen.queryByRole("status")).not.toBeInTheDocument());
  });

  test("stays closed without a target", () => {
    renderModal(null);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(mockFetch).not.toHaveBeenCalled();
  });
});
