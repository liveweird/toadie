import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { act, fireEvent, screen, waitFor } from "@testing-library/react";
import OverwriteWithYamlModal, { type OverwriteTarget } from "./OverwriteWithYamlModal";
import type { CatalogFileRequest } from "../api/catalogFiles";
import { jsonResponse } from "../test/http";
import { catalogFileResponse } from "../test/fixtures";
import { renderWithProviders } from "../test/render";
import { catalogInfoYaml } from "../utils/catalogYaml";

const TOKEN_KEY = "toadie.auth.token";

type FetchMock = ReturnType<typeof vi.fn>;

const TARGET: OverwriteTarget = { id: 1, kind: "Component", name: "svc", namespace: "default" };

const STORED = catalogFileResponse({
  id: 1,
  metadata: { name: "svc", namespace: "default", title: "Old title" },
  spec: { type: "service", lifecycle: "production", owner: "group:default/platform" },
  creatorName: "Alice",
  createdAt: 500,
  updatedAt: 2000,
  sourceUrl: "https://example.com/catalog-info.yaml",
  lastSyncedAt: 1000,
});

const yaml = (title: string, name = "svc") =>
  [
    "apiVersion: backstage.io/v1alpha1",
    "kind: Component",
    "metadata:",
    `  name: ${name}`,
    `  title: ${title}`,
    "spec:",
    "  type: service",
    "  lifecycle: production",
    "  owner: group:default/platform",
    "",
  ].join("\n");

function mockRoutes(
  mockFetch: FetchMock,
  put: Response = new Response(null, { status: 204 }),
  detail: object = STORED,
) {
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    if (url === "/api/v1/files/1" && method === "GET") return Promise.resolve(jsonResponse(200, detail));
    if (url.startsWith("/api/v1/files/1") && method === "PUT") return Promise.resolve(put);
    if (url === "/api/v1/files/check" && method === "POST") {
      return Promise.resolve(jsonResponse(200, { findings: [] }));
    }
    return Promise.resolve(jsonResponse(404, {}));
  });
}

describe("OverwriteWithYamlModal", () => {
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

  const render = (file: OverwriteTarget | null = TARGET) =>
    renderWithProviders(<OverwriteWithYamlModal file={file} onClose={onClose} />);

  const paste = async (user: ReturnType<typeof userEvent.setup>, text: string) => {
    const area = await screen.findByRole("textbox", { name: "YAML content" });
    await user.click(area);
    await user.paste(text);
  };

  // The confirm button's `loading` prop drives Mantine's internal loader Transition
  // (Button.mjs, `duration: 150`, hardcoded — not exposed as a prop). `env="test"` on the
  // MantineProvider only skips the STYLES that Transition would render; `useTransition`
  // (use-transition.mjs) schedules its real rAF→rAF→setTimeout(150) chain regardless. Every
  // test that toggles `saving` true→false (a confirmed overwrite, success or failure) leaves
  // that chain pending; if it fires after RTL's afterEach `cleanup()` — a real race, not a
  // logic error — it dispatches a state update into this file's already-torn-down happy-dom
  // window ("window is not defined", CI PR #26). Draining it here, before the test returns,
  // guarantees the update lands on the still-mounted component instead.
  const settleButtonTransition = () =>
    act(() => new Promise((resolve) => setTimeout(resolve, 300)));

  test("stays closed without a file", () => {
    render(null);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(mockFetch).not.toHaveBeenCalled();
  });

  test("exposes a named loading status while the stored copy is pending", async () => {
    let releaseDetail: (response: Response) => void = () => {};
    const detailPromise = new Promise<Response>((resolve) => {
      releaseDetail = resolve;
    });
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      const method = init?.method ?? "GET";
      if (url === "/api/v1/files/1" && method === "GET") return detailPromise;
      return Promise.resolve(jsonResponse(404, {}));
    });
    render();

    expect(
      await screen.findByRole("status", { name: "Loading the stored copy" }),
    ).toBeInTheDocument();

    releaseDetail(jsonResponse(200, STORED));
    await waitFor(() => expect(screen.queryByRole("status")).not.toBeInTheDocument());
  });

  test("diffs the pasted YAML against the stored copy and PUTs it on confirm", async () => {
    mockRoutes(mockFetch);
    const user = userEvent.setup();
    render();

    await paste(user, yaml("New title"));
    expect(await screen.findByText("- title: Old title")).toBeInTheDocument();
    expect(screen.getByText("+ title: New title")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Overwrite stored copy" }));
    await waitFor(() => expect(onClose).toHaveBeenCalled());

    const put = mockFetch.mock.calls.find(
      ([, init]) => (init as RequestInit | undefined)?.method === "PUT",
    );
    expect(put).toBeDefined();
    const body = JSON.parse((put![1] as RequestInit).body as string) as {
      metadata: { title: string };
      sourceUrl?: string;
    };
    expect(body.metadata.title).toBe("New title");
    // The reference must survive: PUT is a full replace, so an omitted sourceUrl would
    // silently unlink the file from its repo and reset the sync state.
    expect(body.sourceUrl).toBe("https://example.com/catalog-info.yaml");
    await settleButtonTransition();
  });

  test("unparsable YAML is refused and the confirm stays disabled", async () => {
    mockRoutes(mockFetch);
    const user = userEvent.setup();
    render();

    await paste(user, "kind: Component\n  broken: [");
    expect(await screen.findByText("That is not a valid catalog-info.yaml.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Overwrite stored copy" })).toBeDisabled();
  });

  test("a multi-document paste must contain this file's identity", async () => {
    mockRoutes(mockFetch);
    const user = userEvent.setup();
    render();

    await paste(user, `${yaml("A", "other-a")}---\n${yaml("B", "other-b")}`);
    expect(await screen.findByText(/None of the documents is svc/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Overwrite stored copy" })).toBeDisabled();
  });

  test("YAML identical to the stored copy leaves nothing to overwrite", async () => {
    mockRoutes(mockFetch);
    const user = userEvent.setup();
    render();

    await paste(user, yaml("Old title"));
    expect(
      await screen.findByText("The YAML you supplied matches the stored copy — nothing to overwrite."),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Overwrite stored copy" })).toBeDisabled();
  });

  test("a failed overwrite shows the error inline and keeps the modal open", async () => {
    mockRoutes(mockFetch, jsonResponse(409, { title: "Conflict", status: 409, detail: "taken" }));
    const user = userEvent.setup();
    render();

    await paste(user, yaml("New title"));
    await user.click(await screen.findByRole("button", { name: "Overwrite stored copy" }));
    expect(await screen.findByText("Overwrite failed")).toBeInTheDocument();
    expect(onClose).not.toHaveBeenCalled();
    await settleButtonTransition();
  });

  test("picking a file loads its text into the editor", async () => {
    mockRoutes(mockFetch);
    const user = userEvent.setup();
    render();

    const picker = await screen.findByRole("button", { name: "Choose file…" });
    const file = new File([yaml("From a file")], "catalog-info.yaml", { type: "text/yaml" });
    await user.upload(picker.parentElement!.querySelector("input")!, file);

    expect(await screen.findByText("+ title: From a file")).toBeInTheDocument();
  });

  test("large YAML shows both complete documents and confirms the full replacement", async () => {
    const definition = Array.from(
      { length: 600 },
      (_, index) => `path-${index}: ${"x".repeat(80)}`,
    ).join("\n");
    const api = (version: string): CatalogFileRequest => ({
      kind: "API",
      metadata: { name: "large-api", namespace: "default" },
      spec: {
        type: "openapi",
        lifecycle: "production",
        owner: "group:default/platform",
        definition: `${definition}\nversion: ${version}`,
      },
    });
    const current = api("old");
    const replacement = api("new");
    const currentYaml = catalogInfoYaml(current);
    const replacementYaml = catalogInfoYaml(replacement);
    mockRoutes(mockFetch, new Response(null, { status: 204 }), {
      ...STORED,
      kind: current.kind,
      metadata: current.metadata,
      spec: current.spec,
    });
    const user = userEvent.setup();
    renderWithProviders(
      <OverwriteWithYamlModal
        file={{ ...TARGET, kind: "API", name: "large-api" }}
        onClose={onClose}
      />,
    );

    const textarea = await screen.findByRole("textbox", { name: "YAML content" });
    fireEvent.change(textarea, { target: { value: currentYaml } });
    expect(
      await screen.findByText("The YAML you supplied matches the stored copy — nothing to overwrite."),
    ).toBeInTheDocument();
    expect(screen.queryByText(/too large for a detailed line comparison/)).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Overwrite stored copy" })).toBeDisabled();

    fireEvent.change(textarea, {
      target: { value: replacementYaml },
    });
    expect(await screen.findByText(/too large for a detailed line comparison/)).toBeInTheDocument();
    const stored = screen.getByRole("group", { name: "Complete stored YAML" });
    const next = screen.getByRole("group", { name: "Complete replacement YAML" });
    expect(stored).toHaveAttribute("tabindex", "0");
    expect(next).toHaveAttribute("tabindex", "0");
    expect(stored.textContent).toBe(currentYaml);
    expect(next.textContent).toBe(replacementYaml);
    expect(stored.querySelectorAll("pre")).toHaveLength(1);
    expect(next.querySelectorAll("pre")).toHaveLength(1);

    await user.click(screen.getByRole("button", { name: "Overwrite stored copy" }));
    await waitFor(() => expect(onClose).toHaveBeenCalled());
    const put = mockFetch.mock.calls.find(([, init]) => (init as RequestInit | undefined)?.method === "PUT");
    const body = JSON.parse((put![1] as RequestInit).body as string) as CatalogFileRequest & {
      sourceUrl?: string;
    };
    expect(body.spec.definition).toBe(replacement.spec.definition);
    expect(body.sourceUrl).toBe(STORED.sourceUrl);
    await settleButtonTransition();
  });

  test("Esc cannot dismiss the modal mid-overwrite; it closes once the PUT settles", async () => {
    mockRoutes(mockFetch);
    let releasePut: (response: Response) => void = () => {};
    const base = mockFetch.getMockImplementation() as (
      url: string,
      init?: RequestInit,
    ) => Promise<Response>;
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (init?.method === "PUT") {
        return new Promise<Response>((resolve) => {
          releasePut = resolve;
        });
      }
      return base(url, init);
    });
    const user = userEvent.setup();
    render();

    await paste(user, yaml("New title"));
    await user.click(await screen.findByRole("button", { name: "Overwrite stored copy" }));
    await user.keyboard("{Escape}");
    expect(onClose).not.toHaveBeenCalled();

    releasePut(new Response(null, { status: 204 }));
    await waitFor(() => expect(onClose).toHaveBeenCalled());
    await settleButtonTransition();
  });
});
