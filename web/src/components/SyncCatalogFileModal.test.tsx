import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor } from "@testing-library/react";
import SyncCatalogFileModal from "./SyncCatalogFileModal";
import { pickRepoDocument } from "../utils/catalogImport";
import type { CatalogFileListItem, CatalogFileRequest } from "../api/catalogFiles";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const TOKEN_KEY = "toadie.auth.token";

type FetchMock = ReturnType<typeof vi.fn>;

const SOURCE_URL = "https://raw.githubusercontent.com/acme/svc/main/catalog-info.yaml";

const FILE: CatalogFileListItem = {
  id: 1,
  kind: "Component",
  name: "svc",
  namespace: "default",
  title: "Old title",
  type: "service",
  lifecycle: "production",
  owner: "group:default/platform",
  tags: [],
  creatorName: "Alice",
  creatorDeleted: false,
  updatedAt: 2000,
  sourceUrl: SOURCE_URL,
  lastSyncedAt: 1000,
};

const SPEC = { type: "service", lifecycle: "production", owner: "group:default/platform" };

const DETAIL = {
  id: 1,
  kind: "Component",
  metadata: { name: "svc", namespace: "default", title: "Old title" },
  spec: SPEC,
  createdBy: 1,
  creatorName: "Alice",
  creatorDeleted: false,
  createdAt: 500,
  updatedAt: 2000,
  sourceUrl: SOURCE_URL,
  lastSyncedAt: 1000,
};

const SYNC_STATE = {
  sourceUrl: SOURCE_URL,
  lastSyncedAt: 1000,
  syncedDocument: {
    kind: "Component",
    metadata: { name: "svc", namespace: "default", title: "Base title" },
    spec: SPEC,
  },
};

const repoYaml = (title: string) =>
  [
    "apiVersion: backstage.io/v1alpha1",
    "kind: Component",
    "metadata:",
    "  name: svc",
    `  title: ${title}`,
    "spec:",
    "  type: service",
    "  lifecycle: production",
    "  owner: group:default/platform",
    "",
  ].join("\n");

function mockRoutes(
  mockFetch: FetchMock,
  overrides: Partial<Record<"fetch" | "check" | "state" | "detail" | "sync", Response>> = {},
) {
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    if (url === "/api/v1/files/fetch" && method === "POST") {
      return Promise.resolve(overrides.fetch ?? jsonResponse(200, { content: repoYaml("New title") }));
    }
    if (url === "/api/v1/files/check" && method === "POST") {
      return Promise.resolve(overrides.check ?? jsonResponse(200, { findings: [] }));
    }
    if (url === "/api/v1/files/1/sync" && method === "GET") {
      return Promise.resolve(overrides.state ?? jsonResponse(200, SYNC_STATE));
    }
    if (url === "/api/v1/files/1/sync" && method === "POST") {
      return Promise.resolve(overrides.sync ?? new Response(null, { status: 204 }));
    }
    if (url === "/api/v1/files/1" && method === "GET") {
      return Promise.resolve(overrides.detail ?? jsonResponse(200, DETAIL));
    }
    return Promise.resolve(jsonResponse(404, {}));
  });
}

describe("SyncCatalogFileModal", () => {
  let mockFetch: FetchMock;
  const onClose = vi.fn();

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem(TOKEN_KEY, "fake-token");
    onClose.mockReset();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  function renderModal(file: CatalogFileListItem | null = FILE) {
    return renderWithProviders(<SyncCatalogFileModal file={file} onClose={onClose} />);
  }

  test("shows both changed-side badges, the diff, and syncs on confirm", async () => {
    mockRoutes(mockFetch);
    const user = userEvent.setup();
    renderModal();

    // The baseline differs from the repo copy (repo changed) AND updatedAt > lastSyncedAt
    // (DB changed) — both sides light up.
    expect(await screen.findByText("Changed in repo")).toBeInTheDocument();
    expect(screen.getByText("Changed in Toadie")).toBeInTheDocument();
    expect(screen.getByText(/Last synced/)).toBeInTheDocument();
    // getByText's default normalizer collapses whitespace — match the collapsed form.
    expect(screen.getByText("- title: Old title")).toBeInTheDocument();
    expect(screen.getByText("+ title: New title")).toBeInTheDocument();
    // The diff pane is a named, keyboard-scrollable region (see YamlDiffView).
    const diffRegion = screen.getByRole("group", {
      name: "Changes between the stored copy and the repo copy",
    });
    expect(diffRegion).toHaveAttribute("tabindex", "0");

    const confirm = screen.getByRole("button", { name: "Overwrite stored copy" });
    expect(confirm).toBeEnabled();
    await user.click(confirm);

    await waitFor(() => expect(onClose).toHaveBeenCalled());
    const syncCall = mockFetch.mock.calls.find(
      ([url, init]) => url === "/api/v1/files/1/sync" && (init as RequestInit)?.method === "POST",
    );
    expect(syncCall).toBeDefined();
    const body = JSON.parse((syncCall![1] as RequestInit).body as string) as {
      document: CatalogFileRequest;
    };
    expect(body.document.metadata.title).toBe("New title");
    expect(body.document.metadata.name).toBe("svc");
  });

  test("identical DB and repo copies read as in sync and disable the overwrite", async () => {
    mockRoutes(mockFetch, { fetch: jsonResponse(200, { content: repoYaml("Old title") }) });
    renderModal();

    expect(await screen.findByText("Toadie and the repo are in sync")).toBeInTheDocument();
    expect(screen.queryByText("Changed in Toadie")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Overwrite stored copy" })).toBeDisabled();
  });

  test("repo findings raise the warning but never block the sync", async () => {
    mockRoutes(mockFetch, {
      check: jsonResponse(200, {
        findings: [{ field: "spec.owner", reference: "group:gone", status: "MISSING" }],
      }),
    });
    renderModal();

    expect(await screen.findByText(/carries 1 finding/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Overwrite stored copy" })).toBeEnabled();
  });

  test("a refused fetch shows the public-https message and disables the overwrite", async () => {
    mockRoutes(mockFetch, {
      fetch: jsonResponse(400, { title: "Bad Request", status: 400, detail: "blocked" }),
    });
    renderModal();

    expect(
      await screen.findByText(/The URL must be a public https address/),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Overwrite stored copy" })).toBeDisabled();
  });

  test("an unparsable repo file reads as parseFailed", async () => {
    mockRoutes(mockFetch, { fetch: jsonResponse(200, { content: "kind: Component\n  broken: [" }) });
    renderModal();

    expect(
      await screen.findByText("The repo file is not a valid catalog-info.yaml."),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Overwrite stored copy" })).toBeDisabled();
  });

  test("a failed sync shows the error inline and Cancel closes", async () => {
    mockRoutes(mockFetch, {
      sync: jsonResponse(409, { title: "Conflict", status: 409, detail: "identity taken" }),
    });
    const user = userEvent.setup();
    renderModal();

    await user.click(await screen.findByRole("button", { name: "Overwrite stored copy" }));
    expect(await screen.findByText("Sync failed")).toBeInTheDocument();
    expect(onClose).not.toHaveBeenCalled();

    await user.click(screen.getByRole("button", { name: "Cancel" }));
    expect(onClose).toHaveBeenCalled();
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
      if (url === "/api/v1/files/1/sync" && init?.method === "POST") {
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

  test("stays closed without a file", () => {
    renderModal(null);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(mockFetch).not.toHaveBeenCalled();
  });
});

describe("pickRepoDocument", () => {
  const doc = (name: string, namespace?: string): CatalogFileRequest => ({
    kind: "Component",
    metadata: namespace === undefined ? { name } : { name, namespace },
    spec: {},
  });

  test("a single-document file is taken as-is, renames included", () => {
    expect(pickRepoDocument([doc("renamed")], FILE)).toEqual(doc("renamed"));
  });

  test("a multi-document file must contain the row's identity (case-insensitive)", () => {
    const docs = [doc("other"), doc("SVC")];
    expect(pickRepoDocument(docs, FILE)).toEqual(doc("SVC"));
  });

  test("an absent repo namespace counts as default", () => {
    expect(pickRepoDocument([doc("other"), doc("svc")], FILE)).toEqual(doc("svc"));
    expect(pickRepoDocument([doc("other"), doc("svc", "team-a")], FILE)).toBeNull();
  });

  test("no matching identity yields null", () => {
    expect(pickRepoDocument([doc("a"), doc("b")], FILE)).toBeNull();
  });
});
