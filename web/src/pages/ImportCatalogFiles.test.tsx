import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { fireEvent, screen, waitFor } from "@testing-library/react";
import ImportCatalogFiles from "./ImportCatalogFiles";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const TOKEN_KEY = "toadie.auth.token";

type FetchMock = ReturnType<typeof vi.fn>;

const TWO_DOCS = [
  "kind: Component",
  "metadata:",
  "  name: payments-svc",
  "spec:",
  "  type: service",
  "  lifecycle: production",
  "  owner: platform",
  "---",
  "kind: Group",
  "metadata:",
  "  name: team-a",
  "spec:",
  "  type: team",
  "  children: []",
  "",
].join("\n");

function renderPage() {
  return renderWithProviders(<ImportCatalogFiles />, { route: "/files/import" });
}

function pasteYaml(text: string) {
  fireEvent.change(screen.getByLabelText("YAML content"), { target: { value: text } });
}

describe("ImportCatalogFiles page", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn().mockResolvedValue(jsonResponse(404, {}));
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem(TOKEN_KEY, "fake-token");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("starts empty with the import button disabled", () => {
    renderPage();
    expect(screen.getByRole("heading", { name: "Import catalog files" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Import" })).toBeDisabled();
  });

  test("pasting parseable YAML shows the document count and enables importing", async () => {
    renderPage();
    pasteYaml(TWO_DOCS);
    // The parse rides a 300 ms debounce — the summary (and the button) follow it.
    expect(await screen.findByText("2 documents ready to import")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Import" })).toBeEnabled();
  });

  test("a document error is listed with its number and keeps the import disabled", async () => {
    renderPage();
    pasteYaml("kind: Component\nmetadata:\n  name: a\n  color: green\nspec: {}\n");
    expect(await screen.findByText("1 document has problems")).toBeInTheDocument();
    expect(screen.getByText(/Document 1: unknown key metadata\.color/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Import" })).toBeDisabled();
  });

  test("importing posts the parsed documents and renders the per-row results", async () => {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url === "/api/v1/files/import" && init?.method === "POST") {
        return Promise.resolve(
          jsonResponse(200, {
            results: [
              {
                index: 0,
                kind: "Component",
                namespace: "default",
                name: "payments-svc",
                status: "CREATED",
                fileId: 7,
              },
              {
                index: 1,
                kind: "Group",
                namespace: "default",
                name: "team-a",
                status: "CREATED_WITH_FINDINGS",
                fileId: 8,
                message: "spec.owner reference 'ghost' does not resolve to a stored entity",
              },
            ],
          }),
        );
      }
      return Promise.resolve(jsonResponse(404, {}));
    });
    const user = userEvent.setup();
    renderPage();
    pasteYaml(TWO_DOCS);
    await screen.findByText("2 documents ready to import");

    await user.click(screen.getByRole("button", { name: "Import" }));

    // Both stored statuses count as imported; the waived row carries its finding message.
    expect(await screen.findByText("Imported 2 of 2 documents.")).toBeInTheDocument();
    expect(screen.getByText("Created")).toBeInTheDocument();
    expect(screen.getByText("Created with findings")).toBeInTheDocument();
    expect(
      screen.getByText("spec.owner reference 'ghost' does not resolve to a stored entity"),
    ).toBeInTheDocument();
    const [, init] = mockFetch.mock.calls.find(([url]) => url === "/api/v1/files/import")!;
    const body = JSON.parse((init as RequestInit).body as string) as {
      files: { kind: string; metadata: { name: string } }[];
    };
    expect(body.files.map((f) => f.metadata.name)).toEqual(["payments-svc", "team-a"]);
  });

  test("INVALID and ERROR rows render with their messages", async () => {
    mockFetch.mockImplementation((url: string) =>
      url === "/api/v1/files/import"
        ? Promise.resolve(
            jsonResponse(200, {
              results: [
                {
                  index: 0,
                  kind: "Component",
                  namespace: "default",
                  name: "payments-svc",
                  status: "INVALID",
                  message: "spec.type is required for kind Component",
                },
                {
                  index: 1,
                  kind: "Group",
                  namespace: "default",
                  name: "team-a",
                  status: "ERROR",
                  message: "Storage failed",
                },
              ],
            }),
          )
        : Promise.resolve(jsonResponse(404, {})),
    );
    const user = userEvent.setup();
    renderPage();
    pasteYaml(TWO_DOCS);
    await screen.findByText("2 documents ready to import");

    await user.click(screen.getByRole("button", { name: "Import" }));

    expect(await screen.findByText("Imported 0 of 2 documents.")).toBeInTheDocument();
    expect(screen.getByText("Invalid")).toBeInTheDocument();
    expect(screen.getByText("Failed")).toBeInTheDocument();
    expect(screen.getByText("spec.type is required for kind Component")).toBeInTheDocument();
  });

  test("a rejected batch shows the fixed-vocabulary error", async () => {
    mockFetch.mockImplementation((url: string) =>
      url === "/api/v1/files/import"
        ? Promise.resolve(jsonResponse(400, { title: "Bad Request", status: 400 }))
        : Promise.resolve(jsonResponse(404, {})),
    );
    const user = userEvent.setup();
    renderPage();
    pasteYaml(TWO_DOCS);
    await screen.findByText("2 documents ready to import");

    await user.click(screen.getByRole("button", { name: "Import" }));

    expect(
      await screen.findByText("Too many documents for one import (the limit is 200)."),
    ).toBeInTheDocument();
  });

  test("editing the text clears stale results", async () => {
    mockFetch.mockImplementation((url: string) =>
      url === "/api/v1/files/import"
        ? Promise.resolve(
            jsonResponse(200, {
              results: [
                { index: 0, kind: "Component", namespace: "default", name: "payments-svc", status: "CREATED", fileId: 7 },
                { index: 1, kind: "Group", namespace: "default", name: "team-a", status: "CREATED", fileId: 8 },
              ],
            }),
          )
        : Promise.resolve(jsonResponse(404, {})),
    );
    const user = userEvent.setup();
    renderPage();
    pasteYaml(TWO_DOCS);
    await screen.findByText("2 documents ready to import");
    await user.click(screen.getByRole("button", { name: "Import" }));
    expect(await screen.findByText("Imported 2 of 2 documents.")).toBeInTheDocument();

    pasteYaml(TWO_DOCS.replace("payments-svc", "other-svc"));
    await waitFor(() =>
      expect(screen.queryByText("Imported 2 of 2 documents.")).not.toBeInTheDocument(),
    );
  });

  test("fetching a URL loads the text, normalizing GitHub blob links first", async () => {
    mockFetch.mockImplementation((fetchUrl: string, init?: RequestInit) =>
      fetchUrl === "/api/v1/files/fetch" && init?.method === "POST"
        ? Promise.resolve(jsonResponse(200, { content: TWO_DOCS }))
        : Promise.resolve(jsonResponse(404, {})),
    );
    const user = userEvent.setup();
    renderPage();

    const urlInput = screen.getByLabelText("Fetch from URL");
    expect(screen.getByRole("button", { name: "Fetch" })).toBeDisabled();
    fireEvent.change(urlInput, {
      target: { value: "https://github.com/acme/service/blob/main/catalog-info.yaml" },
    });
    await user.click(screen.getByRole("button", { name: "Fetch" }));

    expect(await screen.findByText("2 documents ready to import")).toBeInTheDocument();
    expect(screen.getByLabelText("YAML content")).toHaveValue(TWO_DOCS);

    const [, init] = mockFetch.mock.calls.find(([u]) => u === "/api/v1/files/fetch")!;
    const body = JSON.parse((init as RequestInit).body as string) as { url: string };
    expect(body.url).toBe("https://raw.githubusercontent.com/acme/service/main/catalog-info.yaml");
  });

  test("a blocked URL shows the fixed public-https message", async () => {
    mockFetch.mockImplementation((fetchUrl: string) =>
      fetchUrl === "/api/v1/files/fetch"
        ? Promise.resolve(jsonResponse(400, { title: "Bad Request", status: 400 }))
        : Promise.resolve(jsonResponse(404, {})),
    );
    const user = userEvent.setup();
    renderPage();

    fireEvent.change(screen.getByLabelText("Fetch from URL"), {
      target: { value: "http://10.0.0.1/catalog-info.yaml" },
    });
    await user.click(screen.getByRole("button", { name: "Fetch" }));

    expect(
      await screen.findByText(
        "The URL must be a public https address (GitHub/GitLab links are converted automatically).",
      ),
    ).toBeInTheDocument();
  });

  test("an upstream failure shows the status-tagged fetch message", async () => {
    mockFetch.mockImplementation((fetchUrl: string) =>
      fetchUrl === "/api/v1/files/fetch"
        ? Promise.resolve(jsonResponse(502, { title: "Bad Gateway", status: 502 }))
        : Promise.resolve(jsonResponse(404, {})),
    );
    const user = userEvent.setup();
    renderPage();

    fireEvent.change(screen.getByLabelText("Fetch from URL"), {
      target: { value: "https://example.com/catalog-info.yaml" },
    });
    await user.click(screen.getByRole("button", { name: "Fetch" }));

    expect(await screen.findByText(/Couldn't fetch the URL \(502\)/)).toBeInTheDocument();
  });

  test("picking a file loads its text into the textarea", async () => {
    renderPage();
    const file = new File([TWO_DOCS], "catalog-info.yaml", { type: "text/yaml" });
    const input = document.querySelector('input[type="file"]')!;
    fireEvent.change(input, { target: { files: [file] } });

    await waitFor(() => expect(screen.getByText("2 documents ready to import")).toBeInTheDocument());
  });
});
