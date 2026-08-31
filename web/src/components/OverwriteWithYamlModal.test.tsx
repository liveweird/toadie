import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor } from "@testing-library/react";
import OverwriteWithYamlModal, { type OverwriteTarget } from "./OverwriteWithYamlModal";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const TOKEN_KEY = "toadie.auth.token";

type FetchMock = ReturnType<typeof vi.fn>;

const TARGET: OverwriteTarget = { id: 1, kind: "Component", name: "svc", namespace: "default" };

const STORED = {
  id: 1,
  kind: "Component",
  metadata: { name: "svc", namespace: "default", title: "Old title" },
  spec: { type: "service", lifecycle: "production", owner: "group:default/platform" },
  createdBy: 1,
  creatorName: "Alice",
  creatorDeleted: false,
  createdAt: 500,
  updatedAt: 2000,
  sourceUrl: "https://example.com/catalog-info.yaml",
  lastSyncedAt: 1000,
};

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

function mockRoutes(mockFetch: FetchMock, put: Response = new Response(null, { status: 204 })) {
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    if (url === "/api/v1/files/1" && method === "GET") return Promise.resolve(jsonResponse(200, STORED));
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

  test("stays closed without a file", () => {
    render(null);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(mockFetch).not.toHaveBeenCalled();
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
  });
});
