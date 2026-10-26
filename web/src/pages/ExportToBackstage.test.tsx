import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor, within } from "@testing-library/react";
import { Route, Routes } from "react-router-dom";
import { blueprintResponse, conversionMappingResponse } from "../test/fixtures";
import { mockFetch, type MockRoute } from "../test/http";
import { renderWithProviders } from "../test/render";

// The query bar's real CodeMirror editor has its own tests — page tests drive a textarea stand-in.
vi.mock("../components/QueryEditor", () => ({
  default: ({ value, onChange, ariaLabel }: { value: string; onChange: (v: string) => void; ariaLabel: string }) => (
    <textarea aria-label={ariaLabel} value={value} onChange={(e) => onChange(e.target.value)} />
  ),
}));

import ExportToBackstage from "./ExportToBackstage";

const TOKEN_KEY = "toadie.auth.token";

const FILE = {
  kind: "Component",
  metadata: { name: "checkout", namespace: "default" },
  spec: { type: "service", lifecycle: "production", owner: "group:default/platform" },
};

const DOCUMENTS = {
  files: [FILE],
  rows: [
    {
      index: 0,
      status: "CONVERTED_WITH_FINDINGS",
      blueprint: "service",
      identifier: "checkout",
      entityId: 7,
      kind: "Component",
      namespace: "default",
      name: "checkout",
      existingId: 21,
      existingRevision: 5,
      findings: [{ code: "OWNER_TRUNCATED", field: "team", message: "kept the first team" }],
      unmapped: ["properties.tier", "relations.uses"],
    },
    { index: 1, status: "SKIPPED", blueprint: "widget", identifier: "w1", entityId: 8, findings: [], unmapped: [] },
  ],
};

function render(routes: MockRoute[] = [], mappings = [conversionMappingResponse({ id: 3, name: "Default mapping" })]) {
  const fetchStub = mockFetch([
    { match: "/api/v1/conversion-mappings", respond: { status: 200, body: { items: mappings } } },
    { match: "/api/v1/blueprints", respond: { status: 200, body: { items: [blueprintResponse({ identifier: "service" })] } } },
    { match: "/api/v1/dictionaries/hierarchies", respond: { status: 200, body: { items: [] } } },
    { match: "/api/v1/entity-queries", respond: { status: 200, body: { items: [] } } },
    { method: "POST", match: "/api/v1/entities/query/check", respond: { status: 200, body: { diagnostics: [] } } },
    ...routes,
  ]);
  vi.stubGlobal("fetch", fetchStub);
  renderWithProviders(
    <Routes>
      <Route path="/ontology/export-backstage" element={<ExportToBackstage />} />
    </Routes>,
    { route: "/ontology/export-backstage" },
  );
  return fetchStub;
}

const convertCalls = (fetchStub: ReturnType<typeof mockFetch>) =>
  fetchStub.mock.calls.filter(([url]) => (url as string).includes("/catalog-documents")).map(([url]) => new URL(url as string, "http://x"));

describe("ExportToBackstage page", () => {
  beforeEach(() => {
    localStorage.setItem(TOKEN_KEY, "fake-token");
    localStorage.setItem("toadie.auth.userId", "9");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("the only mapping is preselected; Convert shows rows, findings and the YAML preview", async () => {
    const user = userEvent.setup();
    const fetchStub = render([{ match: /\/catalog-documents/, respond: { status: 200, body: DOCUMENTS } }]);

    const convert = await screen.findByRole("button", { name: "Convert" });
    await waitFor(() => expect(convert).toBeEnabled());
    expect(screen.getByRole("combobox", { name: "Conversion mapping" })).toHaveValue("Default mapping");
    await user.click(convert);

    const table = await screen.findByRole("table", { name: "Conversion rows" });
    expect(within(table).getByText("service / checkout")).toBeInTheDocument();
    expect(within(table).getByText("Component:default/checkout")).toBeInTheDocument();
    expect(within(table).getByText("Owner truncated")).toBeInTheDocument();
    expect(within(table).getByText("kept the first team")).toBeInTheDocument();
    expect(within(table).getByText("Skipped")).toBeInTheDocument();
    expect(within(table).getByText("2 fields")).toBeInTheDocument();
    // The merged-over stored file links to its editor.
    expect(within(table).getByRole("link", { name: "Edit Component:default/checkout" })).toHaveAttribute("href", "/files/21/edit");
    expect(screen.getByText("2 rows — 0 converted, 1 with findings, 1 skipped, 0 invalid")).toBeInTheDocument();
    expect(screen.getByLabelText("Backstage catalog YAML")).toHaveTextContent("kind: Component");

    const [call] = convertCalls(fetchStub);
    expect(call.pathname).toBe("/api/v1/conversion-mappings/3/catalog-documents");
    expect(call.searchParams.get("merge")).toBe("true");
    expect(call.searchParams.has("blueprint")).toBe(false);
  });

  test("switching merge off sends merge=false", async () => {
    const user = userEvent.setup();
    const fetchStub = render([{ match: /\/catalog-documents/, respond: { status: 200, body: { files: [], rows: [] } } }]);

    await user.click(await screen.findByRole("switch", { name: /merge into existing catalog files/i }));
    const convert = screen.getByRole("button", { name: "Convert" });
    await waitFor(() => expect(convert).toBeEnabled());
    await user.click(convert);

    await waitFor(() => expect(convertCalls(fetchStub)).toHaveLength(1));
    expect(convertCalls(fetchStub)[0].searchParams.get("merge")).toBe("false");
    expect(await screen.findByText(/nothing converted/i)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Download YAML" })).toBeDisabled();
  });

  test("with several mappings none is preselected and Convert waits for a pick", async () => {
    const user = userEvent.setup();
    const fetchStub = render([{ match: /\/catalog-documents/, respond: { status: 200, body: DOCUMENTS } }], [
      conversionMappingResponse({ id: 3, name: "Alpha" }),
      conversionMappingResponse({ id: 4, name: "Beta" }),
    ]);

    const select = await screen.findByRole("combobox", { name: "Conversion mapping" });
    await waitFor(() => expect(select).toBeEnabled());
    expect(select).toHaveValue("");
    expect(screen.getByRole("button", { name: "Convert" })).toBeDisabled();

    await user.click(select);
    await user.click(await screen.findByRole("option", { name: "Beta" }));
    await user.click(screen.getByRole("button", { name: "Convert" }));

    await waitFor(() => expect(convertCalls(fetchStub)).toHaveLength(1));
    expect(convertCalls(fetchStub)[0].pathname).toBe("/api/v1/conversion-mappings/4/catalog-documents");
  });

  test("a stale mapping blocks Convert and links to its editor", async () => {
    render([], [
      conversionMappingResponse({
        id: 3,
        name: "Stale",
        findings: [{ code: "UNKNOWN_BLUEPRINT", path: "/pairs/0/blueprint", message: "gone" }],
      }),
    ]);

    expect(await screen.findByText(/went stale after a blueprint or registry edit/i)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Convert" })).toBeDisabled();
    expect(screen.getAllByRole("link", { name: "Conversion mappings" }).some((a) => a.getAttribute("href") === "/ontology/mappings/3/edit")).toBe(true);
  });

  test("a 409 from the server shows the stale message inline", async () => {
    const user = userEvent.setup();
    render([{ match: /\/catalog-documents/, respond: { status: 409, body: { title: "Conflict", status: 409 } } }]);

    const convert = await screen.findByRole("button", { name: "Convert" });
    await waitFor(() => expect(convert).toBeEnabled());
    await user.click(convert);

    expect(await screen.findByText("Conversion failed")).toBeInTheDocument();
    expect(screen.getByText(/blocking findings and cannot convert/i)).toBeInTheDocument();
  });

  test("Download YAML hands the multi-document stream to the browser", async () => {
    const user = userEvent.setup();
    const createObjectURL = vi.fn().mockReturnValue("blob:fake");
    vi.stubGlobal("URL", Object.assign(URL, { createObjectURL, revokeObjectURL: vi.fn() }));
    let downloadedName: string | undefined;
    const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(function (this: HTMLAnchorElement) {
      downloadedName = this.download;
    });
    render([{ match: /\/catalog-documents/, respond: { status: 200, body: DOCUMENTS } }]);

    const convert = await screen.findByRole("button", { name: "Convert" });
    await waitFor(() => expect(convert).toBeEnabled());
    await user.click(convert);
    await user.click(await screen.findByRole("button", { name: "Download YAML" }));

    expect(downloadedName).toBe("toadie-backstage-export.yaml");
    expect(await (createObjectURL.mock.calls[0][0] as Blob).text()).toContain("name: checkout");
    click.mockRestore();
  });

  test("changing the merge switch after a Convert drops the now-stale result", async () => {
    const user = userEvent.setup();
    render([{ match: /\/catalog-documents/, respond: { status: 200, body: DOCUMENTS } }]);

    const convert = await screen.findByRole("button", { name: "Convert" });
    await waitFor(() => expect(convert).toBeEnabled());
    await user.click(convert);
    await screen.findByRole("table", { name: "Conversion rows" });

    await user.click(screen.getByRole("switch", { name: /merge into existing catalog files/i }));

    expect(screen.queryByRole("table", { name: "Conversion rows" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Create / update catalog files" })).not.toBeInTheDocument();
  });

  test("more than 200 files announce that cross-chunk references surface as findings", async () => {
    const user = userEvent.setup();
    const files = Array.from({ length: 201 }, (_, i) => ({ ...FILE, metadata: { name: `svc-${i}` } }));
    render([{ match: /\/catalog-documents/, respond: { status: 200, body: { files, rows: [] } } }]);

    const convert = await screen.findByRole("button", { name: "Convert" });
    await waitFor(() => expect(convert).toBeEnabled());
    await user.click(convert);

    expect(await screen.findByText(/More than 200 files are written in chunks of 200/)).toBeInTheDocument();
  });

  test("the Create / update dialog converts ONCE on open, with the selection that produced the result, and Confirm imports that batch", async () => {
    const user = userEvent.setup();
    const fetchStub = render([
      { match: /\/catalog-documents/, respond: { status: 200, body: DOCUMENTS } },
      {
        method: "POST",
        match: "/api/v1/files/import/check",
        respond: { status: 200, body: { results: [{ index: 0, kind: "Component", namespace: "default", name: "checkout", status: "UPDATED" }] } },
      },
      {
        method: "POST",
        match: "/api/v1/files/import",
        respond: { status: 200, body: { results: [{ index: 0, kind: "Component", namespace: "default", name: "checkout", status: "UPDATED", fileId: 21 }] } },
      },
    ]);

    const convert = await screen.findByRole("button", { name: "Convert" });
    await waitFor(() => expect(convert).toBeEnabled());
    await user.click(convert);
    await screen.findByRole("table", { name: "Conversion rows" });
    expect(convertCalls(fetchStub)).toHaveLength(1);

    await user.click(screen.getByRole("button", { name: "Create / update catalog files" }));
    const dialog = await screen.findByRole("dialog", { name: "Create / update catalog files" });
    await within(dialog).findByText("Would be updated");
    expect(convertCalls(fetchStub)).toHaveLength(2);

    await user.click(within(dialog).getByRole("button", { name: "Confirm" }));
    await within(dialog).findByText("Updated");
    expect(convertCalls(fetchStub)).toHaveLength(2);
    const searches = convertCalls(fetchStub).map((url) => url.search);
    expect(new Set(searches).size).toBe(1);
  });

  test("Create / update runs the dry run, then the real import after Confirm", async () => {
    const user = userEvent.setup();
    const fetchStub = render([
      { match: /\/catalog-documents/, respond: { status: 200, body: DOCUMENTS } },
      {
        method: "POST",
        match: "/api/v1/files/import/check",
        respond: { status: 200, body: { results: [{ index: 0, kind: "Component", namespace: "default", name: "checkout", status: "UPDATED" }] } },
      },
      {
        method: "POST",
        match: "/api/v1/files/import",
        respond: { status: 200, body: { results: [{ index: 0, kind: "Component", namespace: "default", name: "checkout", status: "UPDATED", fileId: 21 }] } },
      },
    ]);

    const convert = await screen.findByRole("button", { name: "Convert" });
    await waitFor(() => expect(convert).toBeEnabled());
    await user.click(convert);
    await user.click(await screen.findByRole("button", { name: "Create / update catalog files" }));

    const dialog = await screen.findByRole("dialog", { name: "Create / update catalog files" });
    expect(await within(dialog).findByText("Would be updated")).toBeInTheDocument();
    const checkCall = fetchStub.mock.calls.find(([url]) => url === "/api/v1/files/import/check")!;
    expect(JSON.parse((checkCall[1] as RequestInit).body as string)).toEqual({ files: [FILE], replaceExisting: true, expected: [{ index: 0, revision: 5, fileId: 21 }] });
    expect(fetchStub.mock.calls.some(([url]) => url === "/api/v1/files/import")).toBe(false);

    await user.click(within(dialog).getByRole("button", { name: "Confirm" }));

    expect(await within(dialog).findByText("Updated")).toBeInTheDocument();
    expect(within(dialog).getByRole("link", { name: "Edit checkout" })).toHaveAttribute("href", "/files/21/edit");
    const importCall = fetchStub.mock.calls.find(([url]) => url === "/api/v1/files/import")!;
    expect(JSON.parse((importCall[1] as RequestInit).body as string)).toEqual({ files: [FILE], replaceExisting: true, expected: [{ index: 0, revision: 5, fileId: 21 }] });
    await user.click(within(dialog).getByRole("button", { name: "Close" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });
});
