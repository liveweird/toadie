import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor, within } from "@testing-library/react";
import { Route, Routes, useLocation } from "react-router-dom";
import EditCatalogFile from "./EditCatalogFile";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const TOKEN_KEY = "toadie.auth.token";

type FetchMock = ReturnType<typeof vi.fn>;

const STORED_FILE = {
  id: 7,
  kind: "Component",
  metadata: {
    name: "stored-svc",
    namespace: "team-a",
    title: "Stored",
    description: "desc",
    labels: { tier: "backend" },
    annotations: {},
    tags: ["java"],
    links: [],
  },
  spec: {
    type: "service",
    lifecycle: "production",
    owner: "group:platform",
    system: null,
    subcomponentOf: null,
    providesApis: [],
    consumesApis: [],
    dependsOn: [],
    dependencyOf: [],
  },
  createdBy: 1,
  creatorName: "Alice",
  creatorDeleted: false,
  createdAt: 1000,
  updatedAt: 2000,
  sourceUrl: null,
  lastSyncedAt: 0,
};

function PathProbe() {
  const location = useLocation();
  return <div data-testid="probe">{location.pathname}</div>;
}

function renderEdit(route = "/files/7/edit") {
  return renderWithProviders(
    <Routes>
      <Route path="/files/:id/edit" element={<EditCatalogFile />} />
      <Route path="/files" element={<PathProbe />} />
    </Routes>,
    { route },
  );
}

/** GET-only harness for the whole-file operations (they never submit the form). */
function mockLoadFile(mockFetch: FetchMock, file: Record<string, unknown>) {
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    if ((init?.method ?? "GET") === "GET" && url === "/api/v1/files/7") {
      return Promise.resolve(jsonResponse(200, file));
    }
    return Promise.resolve(jsonResponse(404, {}));
  });
}

function mockGetAndPut(mockFetch: FetchMock, putStatus = 204) {
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    if (method === "GET" && url === "/api/v1/files/7") {
      return Promise.resolve(jsonResponse(200, STORED_FILE));
    }
    if (method === "PUT" && url === "/api/v1/files/7") {
      return Promise.resolve(
        putStatus === 204
          ? new Response(null, { status: 204 })
          : jsonResponse(putStatus, { title: "x", status: putStatus }),
      );
    }
    return Promise.resolve(jsonResponse(404, {}));
  });
}

describe("EditCatalogFile page", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem(TOKEN_KEY, "fake-token");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("pre-fills the form, PUTs the edited document, and navigates to the list", async () => {
    mockGetAndPut(mockFetch);
    const user = userEvent.setup();
    renderEdit();

    const nameInput = (await screen.findByLabelText(/^name( \*)?$/i)) as HTMLInputElement;
    await waitFor(() => expect(nameInput.value).toBe("stored-svc"));
    expect((screen.getByLabelText("Title") as HTMLInputElement).value).toBe("Stored");

    await user.clear(screen.getByLabelText("Title"));
    await user.type(screen.getByLabelText("Title"), "Renamed");
    await user.click(screen.getByRole("button", { name: /^save$/i }));

    await waitFor(() => expect(screen.getByTestId("probe")).toHaveTextContent("/files"));
    const putCall = mockFetch.mock.calls.find(
      ([url, init]) => (init as RequestInit | undefined)?.method === "PUT" && url === "/api/v1/files/7",
    );
    expect(putCall).toBeDefined();
    expect(JSON.parse((putCall![1] as RequestInit).body as string)).toEqual({
      kind: "Component",
      metadata: {
        name: "stored-svc",
        namespace: "team-a",
        title: "Renamed",
        description: "desc",
        labels: { tier: "backend" },
        annotations: {},
        tags: ["java"],
        links: [],
      },
      spec: {
        type: "service",
        lifecycle: "production",
        owner: "group:platform",
        providesApis: [],
        consumesApis: [],
        dependsOn: [],
        dependencyOf: [],
      },
    });
  });

  test("404 on load shows the not-found alert with a back link", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(404, { title: "nf", status: 404 })));
    renderEdit();

    expect(await screen.findByText("Catalog file not found.")).toBeInTheDocument();
    // The header's back link and the load-state's back button both lead to the list.
    for (const link of screen.getAllByRole("link", { name: /back to catalog files/i })) {
      expect(link).toHaveAttribute("href", "/files");
    }
    expect(screen.queryByRole("button", { name: /^save$/i })).not.toBeInTheDocument();
  });

  test.each([
    [404, /no longer exists/i],
    [409, /already exists/i],
    [500, /save failed \(500\)/i],
  ])("a %i on save surfaces an error and stays on the editor", async (status, pattern) => {
    mockGetAndPut(mockFetch, status);
    const user = userEvent.setup();
    renderEdit();

    const nameInput = (await screen.findByLabelText(/^name( \*)?$/i)) as HTMLInputElement;
    await waitFor(() => expect(nameInput.value).toBe("stored-svc"));
    await user.click(screen.getByRole("button", { name: /^save$/i }));

    expect(await screen.findByText(pattern)).toBeInTheDocument();
    expect(screen.queryByTestId("probe")).not.toBeInTheDocument();
  });

  test("a soft-rejected save opens the Save-anyway modal; confirming retries with allowInvalid", async () => {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      const method = init?.method ?? "GET";
      if (method === "GET" && url === "/api/v1/files/7") {
        return Promise.resolve(jsonResponse(200, STORED_FILE));
      }
      if (method === "PUT" && url === "/api/v1/files/7") {
        return Promise.resolve(jsonResponse(400, { title: "Bad Request", status: 400 }));
      }
      if (method === "PUT" && url === "/api/v1/files/7?allowInvalid=true") {
        return Promise.resolve(new Response(null, { status: 204 }));
      }
      if (method === "POST" && url === "/api/v1/files/check") {
        return Promise.resolve(
          jsonResponse(200, {
            findings: [
              {
                field: "spec.subcomponentOf",
                reference: "component:team-a/stored-svc",
                status: "SELF_REFERENCE",
              },
            ],
          }),
        );
      }
      return Promise.resolve(jsonResponse(404, {}));
    });
    const user = userEvent.setup();
    renderEdit();

    const nameInput = (await screen.findByLabelText(/^name( \*)?$/i)) as HTMLInputElement;
    await waitFor(() => expect(nameInput.value).toBe("stored-svc"));
    await user.type(
      screen.getByLabelText(/subcomponent of/i, { selector: "input" }),
      "component:team-a/stored-svc",
    );
    await user.click(screen.getByRole("button", { name: /^save$/i }));

    // The strict PUT was rejected for the self reference — the modal lists it (scoped: the
    // live check panel renders the same finding text beside the form).
    const modal = await screen.findByRole("dialog");
    expect(within(modal).getByText("Save with findings?")).toBeInTheDocument();
    expect(within(modal).getByText(/an entity cannot reference itself/)).toBeInTheDocument();

    await user.click(within(modal).getByRole("button", { name: /save anyway/i }));
    await waitFor(() => expect(screen.getByTestId("probe")).toHaveTextContent("/files"));
    const waived = mockFetch.mock.calls.find(
      ([url, init]) =>
        (init as RequestInit | undefined)?.method === "PUT" &&
        url === "/api/v1/files/7?allowInvalid=true",
    );
    expect(waived).toBeDefined();
  });

  test("a non-numeric id redirects to the list without fetching the file", () => {
    mockGetAndPut(mockFetch);
    renderEdit("/files/abc/edit");
    expect(screen.getByTestId("probe")).toHaveTextContent("/files");
    // The page-level pools (identities, namespaces) may fetch — the DETAIL must not.
    expect(
      mockFetch.mock.calls.some(([url]) => /\/api\/v1\/files\/\w+$/.test(url as string)),
    ).toBe(false);
  });
});

describe("EditCatalogFile whole-file operations", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem(TOKEN_KEY, "fake-token");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("a source-less file greys out Sync from source and reads 'No source'", async () => {
    mockLoadFile(mockFetch, STORED_FILE);
    renderEdit();

    await screen.findByDisplayValue("stored-svc");
    expect(screen.getByRole("button", { name: "Sync from source" })).toBeDisabled();
    expect(screen.getByText("No source")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Export as YAML" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "Overwrite with YAML" })).toBeEnabled();
  });

  test("a sourced-but-unsynced file enables Sync and reads 'Never synced'", async () => {
    mockLoadFile(mockFetch, { ...STORED_FILE, sourceUrl: "https://example.com/catalog-info.yaml" });
    renderEdit();

    await screen.findByDisplayValue("stored-svc");
    expect(screen.getByRole("button", { name: "Sync from source" })).toBeEnabled();
    expect(screen.getByText("Never synced")).toBeInTheDocument();
  });

  test("a synced file shows how long ago, and flags a later local edit", async () => {
    mockLoadFile(mockFetch, {
      ...STORED_FILE,
      sourceUrl: "https://example.com/catalog-info.yaml",
      lastSyncedAt: Date.now() - 3 * 24 * 60 * 60 * 1000,
      updatedAt: Date.now(),
    });
    renderEdit();

    await screen.findByDisplayValue("stored-svc");
    expect(screen.getByText("3 days ago")).toBeInTheDocument();
    expect(screen.getByText("Local changes")).toBeInTheDocument();
  });

  test("Overwrite with YAML opens the modal for this file", async () => {
    mockLoadFile(mockFetch, STORED_FILE);
    const user = userEvent.setup();
    renderEdit();

    await screen.findByDisplayValue("stored-svc");
    await user.click(screen.getByRole("button", { name: "Overwrite with YAML" }));
    expect(await screen.findByText("Overwrite with YAML — stored-svc")).toBeInTheDocument();
  });

  /**
   * The v1.13.0 regression: the editor's form is seeded by Mantine's ONE-SHOT `initialize`,
   * so a whole-file operation that replaces the stored document underneath it left the fields
   * — and their dirty baseline — showing the old document. The visible symptom was a stale
   * view; the real damage was the next Save writing the OLD document back over the new one.
   * Both modals are unit-tested standalone, which is exactly why this slipped through: the
   * seam that broke only exists with the editor in the tree.
   */
  describe("after a whole-file operation replaces the document", () => {
    const OVERWRITTEN = {
      ...STORED_FILE,
      metadata: { ...STORED_FILE.metadata, title: "Overwritten" },
      updatedAt: 3000,
    };

    const yamlFor = (title: string) =>
      [
        "apiVersion: backstage.io/v1alpha1",
        "kind: Component",
        "metadata:",
        "  name: stored-svc",
        "  namespace: team-a",
        `  title: ${title}`,
        "spec:",
        "  type: service",
        "  lifecycle: production",
        "  owner: group:platform",
        "",
      ].join("\n");

    /** GET returns the stored file until the PUT lands, the overwritten one after. */
    function mockOverwrite(mockFetch: FetchMock, reReadFails = false) {
      let written = false;
      mockFetch.mockImplementation((url: string, init?: RequestInit) => {
        const method = init?.method ?? "GET";
        if (url === "/api/v1/files/check" && method === "POST") {
          return Promise.resolve(jsonResponse(200, { findings: [] }));
        }
        if (url.startsWith("/api/v1/files/7") && method === "PUT") {
          written = true;
          return Promise.resolve(new Response(null, { status: 204 }));
        }
        if (url === "/api/v1/files/7" && method === "GET") {
          if (written && reReadFails) return Promise.resolve(jsonResponse(500, { status: 500 }));
          return Promise.resolve(jsonResponse(200, written ? OVERWRITTEN : STORED_FILE));
        }
        return Promise.resolve(jsonResponse(404, {}));
      });
    }

    async function overwrite(user: ReturnType<typeof userEvent.setup>, title: string) {
      await user.click(screen.getByRole("button", { name: "Overwrite with YAML" }));
      const area = await screen.findByRole("textbox", { name: "YAML content" });
      await user.click(area);
      await user.paste(yamlFor(title));
      await user.click(await screen.findByRole("button", { name: "Overwrite stored copy" }));
    }

    test("the form re-seeds, so the fields show the overwritten document", async () => {
      mockOverwrite(mockFetch);
      const user = userEvent.setup();
      renderEdit();

      expect(await screen.findByDisplayValue("Stored")).toBeInTheDocument();
      await overwrite(user, "Overwritten");

      expect(await screen.findByDisplayValue("Overwritten")).toBeInTheDocument();
      expect(screen.queryByDisplayValue("Stored")).not.toBeInTheDocument();
    });

    test("a Save afterwards writes the NEW document, never the pre-overwrite one", async () => {
      mockOverwrite(mockFetch);
      const user = userEvent.setup();
      renderEdit();

      expect(await screen.findByDisplayValue("Stored")).toBeInTheDocument();
      await overwrite(user, "Overwritten");
      await screen.findByDisplayValue("Overwritten");

      await user.click(screen.getByRole("button", { name: /^save$/i }));
      await waitFor(() => expect(screen.getByTestId("probe")).toHaveTextContent("/files"));

      const puts = mockFetch.mock.calls.filter(
        ([, init]) => (init as RequestInit | undefined)?.method === "PUT",
      );
      const saved = JSON.parse((puts.at(-1)![1] as RequestInit).body as string) as {
        metadata: { title: string };
      };
      expect(saved.metadata.title).toBe("Overwritten");
    });

    test("a failed re-read takes the save away rather than letting it revert the write", async () => {
      // The re-read goes through the page's own query, so its failure lands in that query's
      // error state and the editor is replaced by the load-error branch — no Save button, so
      // the stale form can never be written back over what just committed.
      mockOverwrite(mockFetch, true);
      const user = userEvent.setup();
      renderEdit();

      expect(await screen.findByDisplayValue("Stored")).toBeInTheDocument();
      await overwrite(user, "Overwritten");

      await waitFor(() =>
        expect(screen.queryByRole("button", { name: /^save$/i })).not.toBeInTheDocument(),
      );
    });
  });
});
