import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
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

function renderWithCachedRegistry(cachedBlueprints: unknown[]) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  queryClient.setQueryData(["blueprints"], cachedBlueprints);
  return renderWithProviders(
    <QueryClientProvider client={queryClient}>
      <SyncEntityModal target={TARGET} onClose={() => {}} onCompleted={() => {}} />
    </QueryClientProvider>,
  );
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
    await waitFor(() => expect(confirm).toBeEnabled());
    await user.click(confirm);

    await waitFor(() => expect(onClose).toHaveBeenCalled());
    const syncCall = mockFetch.mock.calls.find(
      ([url, init]) => url === "/api/v1/entities/1/sync" && (init as RequestInit)?.method === "POST",
    );
    expect(syncCall).toBeDefined();
    const body = JSON.parse((syncCall![1] as RequestInit).body as string) as {
      document: Record<string, unknown>;
      expectedSourceUrl: string;
    };
    // The whole picked document — properties and relations included, not just identity fields.
    expect(body.document).toEqual(remoteDoc("New title"));
    expect(body.expectedSourceUrl).toBe(TARGET.sourceUrl);
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

  test("refreshes a cached blueprint before deciding which remote properties are computed", async () => {
    const cachedV1 = [
      { ...BLUEPRINTS[0], mirrorProperties: { foo: { title: "Foo", path: "owner.foo" } } },
    ];
    const freshV2 = [
      {
        ...BLUEPRINTS[0],
        schema: {
          properties: {
            ...BLUEPRINTS[0].schema.properties,
            foo: { type: "string", title: "Foo" },
          },
          required: [],
        },
        mirrorProperties: {},
      },
    ];
    const remote = { ...remoteDoc("New title"), properties: { language: "java", foo: "keep me" } };
    mockRoutes(mockFetch, {
      blueprints: jsonResponse(200, { items: freshV2 }),
      fetch: jsonResponse(200, { content: JSON.stringify(remote) }),
    });
    const user = userEvent.setup();
    renderWithCachedRegistry(cachedV1);

    const confirm = await screen.findByRole("button", { name: "Overwrite stored copy" });
    await waitFor(() => expect(confirm).toBeEnabled());
    expect(screen.queryByText(/Computed property values removed/)).not.toBeInTheDocument();
    await user.click(confirm);

    const syncCall = mockFetch.mock.calls.find(
      ([url, init]) => url === "/api/v1/entities/1/sync" && (init as RequestInit)?.method === "POST",
    );
    const body = JSON.parse((syncCall![1] as RequestInit).body as string) as {
      document: { properties: Record<string, unknown> };
    };
    expect(body.document.properties.foo).toBe("keep me");
  });

  test("a fresh registry missing the current blueprint shows a safe error without a perpetual loader", async () => {
    mockRoutes(mockFetch, { blueprints: jsonResponse(200, { items: [] }) });
    renderWithCachedRegistry(BLUEPRINTS);

    expect(await screen.findByText("The blueprint of this entity no longer exists.")).toBeInTheDocument();
    await waitFor(() => {
      expect(screen.queryByRole("status", { name: "Loading the source copy" })).not.toBeInTheDocument();
    });
    expect(screen.getByRole("button", { name: "Overwrite stored copy" })).toBeDisabled();
  });

  test("a pending registry refresh blocks picking and overwrite until the fresh definition arrives", async () => {
    mockRoutes(mockFetch);
    let releaseBlueprints: (response: Response) => void = () => {};
    const blueprintPromise = new Promise<Response>((resolve) => {
      releaseBlueprints = resolve;
    });
    const base = mockFetch.getMockImplementation() as (url: string, init?: RequestInit) => Promise<Response>;
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url === "/api/v1/blueprints" && (init?.method ?? "GET") === "GET") return blueprintPromise;
      return base(url, init);
    });
    renderWithCachedRegistry(BLUEPRINTS);

    expect(await screen.findByRole("status", { name: "Loading the source copy" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Overwrite stored copy" })).toBeDisabled();
    expect(mockFetch.mock.calls.some(([url]) => url === "/api/v1/entities/import/check")).toBe(false);

    releaseBlueprints(jsonResponse(200, { items: BLUEPRINTS }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Overwrite stored copy" })).toBeEnabled());
  });

  test("a failed registry refresh blocks overwrite and shows a safe load error", async () => {
    mockRoutes(mockFetch, {
      blueprints: jsonResponse(503, { title: "Unavailable", status: 503 }),
    });
    renderWithCachedRegistry(BLUEPRINTS);

    expect(await screen.findByText("Load failed (503)")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Overwrite stored copy" })).toBeDisabled();
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

    const confirm = await screen.findByRole("button", { name: "Overwrite stored copy" });
    await waitFor(() => expect(confirm).toBeEnabled());
    await user.click(confirm);

    expect(await screen.findByText("Sync failed")).toBeInTheDocument();
    expect(screen.getByText("properties.language: Required")).toBeInTheDocument();
    expect(onClose).not.toHaveBeenCalled();
  });

  test("a typed source conflict asks the reader to reload instead of reporting an identity clash", async () => {
    mockRoutes(mockFetch, {
      sync: jsonResponse(409, {
        type: "urn:toadie:source-reference-conflict",
        title: "Conflict",
        status: 409,
      }),
    });
    const user = userEvent.setup();
    renderModal();

    const confirm = await screen.findByRole("button", { name: "Overwrite stored copy" });
    await waitFor(() => expect(confirm).toBeEnabled());
    await user.click(confirm);

    expect(await screen.findByText(/source reference changed before the sync was saved/i)).toBeInTheDocument();
    expect(screen.queryByText("An entity with this identifier already exists in this blueprint.")).not.toBeInTheDocument();
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
    await waitFor(() => expect(screen.getByRole("button", { name: "Overwrite stored copy" })).toBeEnabled());
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

  test("keeps overwrite disabled until pre-flight for the picked document completes", async () => {
    mockRoutes(mockFetch);
    let releaseCheck: (response: Response) => void = () => {};
    const base = mockFetch.getMockImplementation() as (url: string, init?: RequestInit) => Promise<Response>;
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url === "/api/v1/entities/import/check" && init?.method === "POST") {
        return new Promise<Response>((resolve) => {
          releaseCheck = resolve;
        });
      }
      return base(url, init);
    });
    renderModal();

    expect(await screen.findByText("Changed at source")).toBeInTheDocument();
    const confirm = screen.getByRole("button", { name: "Overwrite stored copy" });
    expect(confirm).toBeDisabled();
    const checkCall = mockFetch.mock.calls.find(
      ([url, init]) => url === "/api/v1/entities/import/check" && (init as RequestInit)?.method === "POST",
    );
    const checkBody = JSON.parse((checkCall![1] as RequestInit).body as string) as {
      documents: Record<string, unknown>[];
    };
    expect(checkBody.documents).toEqual([remoteDoc("New title")]);

    releaseCheck(jsonResponse(200, {
      results: [{ index: 0, blueprint: "service", identifier: "checkout", status: "UPDATED", id: 1 }],
    }));
    await waitFor(() => expect(confirm).toBeEnabled());
  });

  test("shows a safe error and blocks overwrite when pre-flight fails", async () => {
    mockRoutes(mockFetch, { check: jsonResponse(503, { title: "Unavailable", status: 503 }) });
    renderModal();

    expect(await screen.findByText("Load failed (503)")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Overwrite stored copy" })).toBeDisabled();
  });

  test("blocks overwrite when pre-flight returns no classification", async () => {
    mockRoutes(mockFetch, { check: jsonResponse(200, { results: [] }) });
    renderModal();

    expect(await screen.findByText("The source copy could not be validated.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Overwrite stored copy" })).toBeDisabled();
  });

  test("does not let an old candidate's pending pre-flight authorize a new target", async () => {
    const secondSource = "https://raw.githubusercontent.com/acme/svc/main/inventory.json";
    const secondTarget: EntitySyncTarget = { ...TARGET, id: 2, identifier: "inventory", sourceUrl: secondSource };
    const secondDetail = { ...DETAIL, id: 2, identifier: "inventory", sourceUrl: secondSource };
    const secondDocument = { ...remoteDoc("Inventory"), identifier: "inventory" };
    let releaseFirstCheck: (response: Response) => void = () => {};
    const firstCheck = new Promise<Response>((resolve) => {
      releaseFirstCheck = resolve;
    });
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      const method = init?.method ?? "GET";
      if (url === "/api/v1/blueprints" && method === "GET") {
        return Promise.resolve(jsonResponse(200, { items: BLUEPRINTS }));
      }
      if (url === "/api/v1/entities/1" && method === "GET") return Promise.resolve(jsonResponse(200, DETAIL));
      if (url === "/api/v1/entities/2" && method === "GET") return Promise.resolve(jsonResponse(200, secondDetail));
      if (url === "/api/v1/entities/1/sync" && method === "GET") return Promise.resolve(jsonResponse(200, SYNC_STATE));
      if (url === "/api/v1/entities/2/sync" && method === "GET") {
        return Promise.resolve(jsonResponse(200, { ...SYNC_STATE, sourceUrl: secondSource }));
      }
      if (url === "/api/v1/entities/fetch" && method === "POST") {
        const body = JSON.parse(init?.body as string) as { url: string };
        const document = body.url === secondSource ? secondDocument : remoteDoc("New title");
        return Promise.resolve(jsonResponse(200, { content: JSON.stringify(document) }));
      }
      if (url === "/api/v1/entities/import/check" && method === "POST") {
        const body = JSON.parse(init?.body as string) as { documents: Array<{ identifier: string }> };
        if (body.documents[0]?.identifier === "checkout") return firstCheck;
        return Promise.resolve(jsonResponse(200, {
          results: [{ index: 0, blueprint: "service", identifier: "inventory", status: "UPDATED", id: 2 }],
        }));
      }
      if (url === "/api/v1/entities/2/sync" && method === "POST") return Promise.resolve(new Response(null, { status: 204 }));
      return Promise.resolve(jsonResponse(404, {}));
    });
    const rendered = renderModal();

    expect(await screen.findByText("Changed at source")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Overwrite stored copy" })).toBeDisabled();

    rendered.rerender(
      <SyncEntityModal target={secondTarget} onClose={onClose} onCompleted={onCompleted} />,
    );
    const confirm = await screen.findByRole("button", { name: "Overwrite stored copy" });
    await waitFor(() => expect(confirm).toBeEnabled());

    releaseFirstCheck(jsonResponse(200, {
      results: [{ index: 0, blueprint: "service", identifier: "checkout", status: "INVALID" }],
    }));
    await waitFor(() => expect(confirm).toBeEnabled());
  });

  test("uses the fresh detail source and identity instead of the opening row snapshot", async () => {
    const freshSource = "https://github.com/acme/svc/blob/main/renamed.json";
    const normalizedSource = "https://raw.githubusercontent.com/acme/svc/main/renamed.json";
    const freshDocument = { ...remoteDoc("Fresh"), identifier: "renamed" };
    mockRoutes(mockFetch, {
      detail: jsonResponse(200, { ...DETAIL, identifier: "renamed", sourceUrl: freshSource }),
      state: jsonResponse(200, { ...SYNC_STATE, sourceUrl: freshSource }),
      fetch: jsonResponse(200, {
        content: JSON.stringify({ entities: [remoteDoc("Stale row"), freshDocument] }),
      }),
    });
    const user = userEvent.setup();
    renderModal();

    const confirm = await screen.findByRole("button", { name: "Overwrite stored copy" });
    await waitFor(() => expect(confirm).toBeEnabled());
    const fetchCall = mockFetch.mock.calls.find(
      ([url, init]) => url === "/api/v1/entities/fetch" && (init as RequestInit)?.method === "POST",
    );
    expect(JSON.parse((fetchCall![1] as RequestInit).body as string)).toEqual({ url: normalizedSource });
    await user.click(confirm);

    const syncCall = mockFetch.mock.calls.find(
      ([url, init]) => url === "/api/v1/entities/1/sync" && (init as RequestInit)?.method === "POST",
    );
    const body = JSON.parse((syncCall![1] as RequestInit).body as string);
    expect(body).toEqual({ document: freshDocument, expectedSourceUrl: freshSource });
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
      if (url === "/api/v1/entities/1/sync" && init?.method === "POST") {
        return new Promise<Response>((resolve) => {
          releaseSync = resolve;
        });
      }
      return base(url, init);
    });
    const user = userEvent.setup();
    renderModal();

    const confirm = await screen.findByRole("button", { name: "Overwrite stored copy" });
    await waitFor(() => expect(confirm).toBeEnabled());
    await user.click(confirm);
    await user.keyboard("{Escape}");
    expect(onClose).not.toHaveBeenCalled();

    releaseSync(new Response(null, { status: 204 }));
    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });

  test("does not fetch or enable overwrite while the current detail is pending", async () => {
    mockRoutes(mockFetch);
    let releaseDetail: (response: Response) => void = () => {};
    const detailPromise = new Promise<Response>((resolve) => {
      releaseDetail = resolve;
    });
    const base = mockFetch.getMockImplementation() as (url: string, init?: RequestInit) => Promise<Response>;
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url === "/api/v1/entities/1" && (init?.method ?? "GET") === "GET") return detailPromise;
      return base(url, init);
    });
    renderModal();

    expect(await screen.findByRole("status", { name: "Loading the source copy" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Overwrite stored copy" })).toBeDisabled();
    expect(mockFetch.mock.calls.some(([url]) => url === "/api/v1/entities/fetch")).toBe(false);

    releaseDetail(jsonResponse(200, DETAIL));
    await waitFor(() => expect(screen.getByRole("button", { name: "Overwrite stored copy" })).toBeEnabled());
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
      if (url === "/api/v1/entities/1/sync" && method === "GET") return statePromise;
      return base(url, init);
    });
    renderModal();

    expect(
      await screen.findByRole("status", { name: "Loading the source copy" }),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Overwrite stored copy" })).toBeDisabled();

    releaseState(jsonResponse(200, SYNC_STATE));
    await waitFor(() => expect(screen.queryByRole("status")).not.toBeInTheDocument());
  });

  test("stays closed without a target", () => {
    renderModal(null);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(mockFetch).not.toHaveBeenCalled();
  });
});
