import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor, within } from "@testing-library/react";
import { QueryClient } from "@tanstack/react-query";
import { conversionMappingResponse } from "../test/fixtures";
import { jsonResponse, mockFetch, type MockRoute } from "../test/http";
import { renderWithProviders } from "../test/render";
import BackstageCatalogImport from "./BackstageCatalogImport";

const TOKEN_KEY = "toadie.auth.token";

const YAML = `apiVersion: backstage.io/v1alpha1
kind: Component
metadata:
  name: checkout
spec:
  type: service
  lifecycle: production
  owner: group:default/platform
`;

const DOCUMENT = { blueprint: "service", identifier: "checkout", title: "checkout", properties: {}, relations: {} };

const CONVERTED = {
  documents: [DOCUMENT],
  rows: [
    {
      index: 0,
      status: "CONVERTED_WITH_FINDINGS",
      kind: "Component",
      namespace: "default",
      name: "checkout",
      blueprint: "service",
      identifier: "checkout",
      existingId: 12,
      existingUpdatedAt: 1700,
      findings: [{ code: "OWNER_DROPPED", field: "spec.owner", message: "no such team" }],
      unmapped: ["metadata.description"],
    },
  ],
};

const ENTITY_ROW = { index: 0, blueprint: "service", identifier: "checkout", status: "UPDATED", id: 12 };

function render(routes: MockRoute[] = [], mappings = [conversionMappingResponse({ id: 3, name: "Default mapping" })]) {
  const fetchStub = mockFetch([
    ...routes,
    { match: "/api/v1/conversion-mappings", respond: { status: 200, body: { items: mappings } } },
    { method: "POST", match: "/api/v1/conversion-mappings/3/entity-documents", respond: { status: 200, body: CONVERTED } },
  ]);
  vi.stubGlobal("fetch", fetchStub);
  renderWithProviders(<BackstageCatalogImport />);
  return fetchStub;
}

const callsTo = (fetchStub: ReturnType<typeof mockFetch>, path: string) =>
  fetchStub.mock.calls.filter(([url, init]) => url === path && init?.method === "POST");

async function pasteYaml(user: ReturnType<typeof userEvent.setup>, text = YAML) {
  const field = await screen.findByLabelText("YAML content");
  await user.click(field);
  await user.paste(text);
}

describe("BackstageCatalogImport", () => {
  beforeEach(() => {
    localStorage.setItem(TOKEN_KEY, "fake-token");
    localStorage.setItem("toadie.auth.roles", JSON.stringify(["ADMIN"]));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("Convert previews the conversion report and stores nothing", async () => {
    const user = userEvent.setup();
    const fetchStub = render();

    await pasteYaml(user);
    const convert = screen.getByRole("button", { name: "Convert" });
    await waitFor(() => expect(convert).toBeEnabled());
    await user.click(convert);

    const table = await screen.findByRole("table", { name: "Conversion rows" });
    expect(within(table).getByText("Component:default/checkout")).toBeInTheDocument();
    expect(within(table).getByText("service / checkout")).toBeInTheDocument();
    expect(within(table).getByText("Owner dropped")).toBeInTheDocument();
    expect(within(table).getByRole("link", { name: "Edit service / checkout" })).toHaveAttribute("href", "/entities/12/edit");

    const [call] = callsTo(fetchStub, "/api/v1/conversion-mappings/3/entity-documents");
    const body = JSON.parse((call[1] as RequestInit).body as string) as { files: { metadata: { name: string } }[]; merge: boolean };
    expect(body.merge).toBe(true);
    expect(body.files.map((f) => f.metadata.name)).toEqual(["checkout"]);
    expect(callsTo(fetchStub, "/api/v1/entities/import")).toHaveLength(0);
    expect(callsTo(fetchStub, "/api/v1/entities/import/check")).toHaveLength(0);
  });

  test("Import opens a confirm dialog with a fresh conversion and dry run, and Confirm writes the previewed batch without converting again", async () => {
    const user = userEvent.setup();
    const fetchStub = render([
      { method: "POST", match: "/api/v1/entities/import/check", respond: { status: 200, body: { results: [ENTITY_ROW] } } },
      { method: "POST", match: "/api/v1/entities/import", respond: { status: 200, body: { results: [ENTITY_ROW] } } },
    ]);
    const conversions = () => callsTo(fetchStub, "/api/v1/conversion-mappings/3/entity-documents").length;

    await pasteYaml(user);
    const convert = screen.getByRole("button", { name: "Convert" });
    await waitFor(() => expect(convert).toBeEnabled());
    await user.click(convert);
    await screen.findByRole("table", { name: "Conversion rows" });
    expect(conversions()).toBe(1);

    await user.click(screen.getByRole("button", { name: "Import" }));

    // Opening the dialog converts FRESH (not the preview's documents) and dry-runs; nothing is written yet.
    const dialog = await screen.findByRole("dialog", { name: "Import into Port" });
    expect(await within(dialog).findByText("Would be updated")).toBeInTheDocument();
    expect(within(dialog).getByText("Owner dropped")).toBeInTheDocument();
    expect(conversions()).toBe(2);
    expect(callsTo(fetchStub, "/api/v1/entities/import/check")).toHaveLength(1);
    expect(callsTo(fetchStub, "/api/v1/entities/import")).toHaveLength(0);
    expect(within(dialog).queryByText(/Merge is off/)).not.toBeInTheDocument();

    await user.click(within(dialog).getByRole("button", { name: "Confirm import" }));

    // Confirm imports exactly what was previewed (no third conversion), guarded by the token.
    expect(await within(dialog).findByText("Updated")).toBeInTheDocument();
    expect(conversions()).toBe(2);
    const [importCall] = callsTo(fetchStub, "/api/v1/entities/import");
    expect(JSON.parse((importCall[1] as RequestInit).body as string)).toMatchObject({
      documents: [DOCUMENT],
      replaceExisting: true,
      expected: [{ index: 0, updatedAt: 1700 }],
    });
    expect(within(dialog).getAllByRole("link", { name: "Edit service / checkout" })[0]).toHaveAttribute("href", "/entities/12/edit");
    await user.click(within(dialog).getByRole("button", { name: "Close" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  test("with Merge off the dialog states that the converted document replaces the entity wholesale", async () => {
    const user = userEvent.setup();
    const fetchStub = render([
      { method: "POST", match: "/api/v1/entities/import/check", respond: { status: 200, body: { results: [ENTITY_ROW] } } },
    ]);

    await pasteYaml(user);
    await user.click(screen.getByRole("switch", { name: /merge into existing entities/i }));
    const importButton = screen.getByRole("button", { name: "Import" });
    await waitFor(() => expect(importButton).toBeEnabled());
    await user.click(importButton);

    const dialog = await screen.findByRole("dialog", { name: "Import into Port" });
    expect(within(dialog).getByText(/Merge is off: each converted document replaces its Port entity wholesale/)).toBeInTheDocument();
    const [call] = callsTo(fetchStub, "/api/v1/conversion-mappings/3/entity-documents");
    expect(JSON.parse((call[1] as RequestInit).body as string).merge).toBe(false);
  });

  test("Cancel in the dialog writes nothing", async () => {
    const user = userEvent.setup();
    const fetchStub = render([
      { method: "POST", match: "/api/v1/entities/import/check", respond: { status: 200, body: { results: [ENTITY_ROW] } } },
    ]);

    await pasteYaml(user);
    const importButton = screen.getByRole("button", { name: "Import" });
    await waitFor(() => expect(importButton).toBeEnabled());
    await user.click(importButton);
    const dialog = await screen.findByRole("dialog", { name: "Import into Port" });
    await within(dialog).findByText("Would be updated");
    await user.click(within(dialog).getByRole("button", { name: "Cancel" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(callsTo(fetchStub, "/api/v1/entities/import")).toHaveLength(0);
  });

  test("a chunk failing part-way keeps the stored rows, says how many chunks went through, refreshes the entities and uses the IMPORT's own wording", async () => {
    const invalidateSpy = vi.spyOn(QueryClient.prototype, "invalidateQueries");
    const user = userEvent.setup();
    const many = Array.from({ length: 201 }, (_, i) => YAML.replace("name: checkout", `name: svc-${i}`)).join("---\n");
    let importCalls = 0;
    render([
      {
        method: "POST",
        match: "/api/v1/conversion-mappings/3/entity-documents",
        respond: (_url, init) => {
          const files = (JSON.parse(init!.body as string) as { files: { metadata: { name: string } }[] }).files;
          return jsonResponse(200, {
            documents: files.map((f) => ({ blueprint: "service", identifier: f.metadata.name, title: f.metadata.name, properties: {}, relations: {} })),
            rows: files.map((f, index) => ({ index, status: "CONVERTED", kind: "Component", namespace: "default", name: f.metadata.name, blueprint: "service", identifier: f.metadata.name, findings: [], unmapped: [] })),
          });
        },
      },
      { method: "POST", match: "/api/v1/entities/import/check", respond: (_url, init) => {
          const docs = (JSON.parse(init!.body as string) as { documents: { identifier: string }[] }).documents;
          return jsonResponse(200, { results: docs.map((d, index) => ({ index, blueprint: "service", identifier: d.identifier, status: "CREATED" })) });
        } },
      {
        method: "POST",
        match: "/api/v1/entities/import",
        respond: (_url, init) => {
          importCalls += 1;
          if (importCalls > 1) return jsonResponse(500, { title: "boom", status: 500 });
          const docs = (JSON.parse(init!.body as string) as { documents: { identifier: string }[] }).documents;
          return jsonResponse(200, { results: docs.map((d, index) => ({ index, blueprint: "service", identifier: d.identifier, status: "CREATED", id: 100 + index })) });
        },
      },
    ]);

    await pasteYaml(user, many);
    const importButton = screen.getByRole("button", { name: "Import" });
    await waitFor(() => expect(importButton).toBeEnabled());
    await user.click(importButton);
    const dialog = await screen.findByRole("dialog", { name: "Import into Port" });
    await within(dialog).findAllByText("Would be created");
    await user.click(within(dialog).getByRole("button", { name: "Confirm import" }));

    expect(await within(dialog).findByText(/Stopped after 1 of 2 chunks/)).toBeInTheDocument();
    expect(within(dialog).getByText(/Import failed \(500\)/)).toBeInTheDocument();
    expect(within(dialog).queryByText("Conversion failed")).not.toBeInTheDocument();
    expect(within(dialog).getByText("These entities were stored before the failure:")).toBeInTheDocument();
    expect(within(dialog).getAllByText("Created").length).toBeGreaterThan(0);
    await waitFor(() => expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ["entities"] }));
    invalidateSpy.mockRestore();
  });

  test("the inputs are disabled while a conversion is in flight", async () => {
    const user = userEvent.setup();
    let release!: () => void;
    const gate = new Promise<void>((resolve) => { release = resolve; });
    render([
      {
        method: "POST",
        match: "/api/v1/conversion-mappings/3/entity-documents",
        respond: async () => {
          await gate;
          return jsonResponse(200, CONVERTED);
        },
      },
    ]);

    await pasteYaml(user);
    const convert = screen.getByRole("button", { name: "Convert" });
    await waitFor(() => expect(convert).toBeEnabled());
    await user.click(convert);

    await waitFor(() => expect(screen.getByLabelText("YAML content")).toBeDisabled());
    expect(screen.getByRole("switch", { name: /merge into existing entities/i })).toBeDisabled();
    expect(screen.getByRole("combobox", { name: "Conversion mapping" })).toBeDisabled();
    release();
    await screen.findByRole("table", { name: "Conversion rows" });
    expect(screen.getByLabelText("YAML content")).toBeEnabled();
  });

  test("Check import dry-runs against /entities/import/check", async () => {
    const user = userEvent.setup();
    const fetchStub = render([
      { method: "POST", match: "/api/v1/entities/import/check", respond: { status: 200, body: { results: [ENTITY_ROW] } } },
    ]);

    await pasteYaml(user);
    const check = screen.getByRole("button", { name: "Check" });
    await waitFor(() => expect(check).toBeEnabled());
    await user.click(check);

    expect(await screen.findByText("Would be updated")).toBeInTheDocument();
    expect(callsTo(fetchStub, "/api/v1/entities/import/check")).toHaveLength(1);
    expect(callsTo(fetchStub, "/api/v1/entities/import")).toHaveLength(0);
  });

  test("a malformed document lists the parse error and disables every action", async () => {
    const user = userEvent.setup();
    render();

    await pasteYaml(user, "kind: Component\nmetadata:\n  name: x\n  bogus: 1\n");

    expect(await screen.findByText(/unknown key metadata.bogus/i)).toBeInTheDocument();
    for (const name of ["Convert", "Check", "Import"]) {
      expect(screen.getByRole("button", { name })).toBeDisabled();
    }
  });

  test("a stale mapping blocks the actions", async () => {
    const user = userEvent.setup();
    render([], [
      conversionMappingResponse({ id: 3, name: "Stale", findings: [{ code: "UNKNOWN_BLUEPRINT", path: "/pairs/0", message: "gone" }] }),
    ]);

    await pasteYaml(user);

    expect(await screen.findByText(/went stale after a blueprint or registry edit/i)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Import" })).toBeDisabled();
  });

  test("a 409 from the conversion shows the stale message and imports nothing", async () => {
    const user = userEvent.setup();
    const fetchStub = render([
      { method: "POST", match: "/api/v1/conversion-mappings/3/entity-documents", respond: { status: 409, body: { title: "Conflict", status: 409 } } },
    ]);

    await pasteYaml(user);
    const checkButton = screen.getByRole("button", { name: "Check" });
    await waitFor(() => expect(checkButton).toBeEnabled());
    await user.click(checkButton);

    expect(await screen.findByText("Conversion failed")).toBeInTheDocument();
    expect(await screen.findByText(/blocking findings and cannot convert/i)).toBeInTheDocument();
    expect(callsTo(fetchStub, "/api/v1/entities/import")).toHaveLength(0);
  });

  test("Fetch from URL loads the text through the SSRF-guarded catalog fetch", async () => {
    const user = userEvent.setup();
    const fetchStub = render([{ method: "POST", match: "/api/v1/files/fetch", respond: { status: 200, body: { content: YAML } } }]);

    await user.type(await screen.findByLabelText("Fetch from URL"), "https://github.com/acme/svc/blob/main/catalog-info.yaml");
    await user.click(screen.getByRole("button", { name: "Fetch" }));

    await waitFor(() => expect((screen.getByLabelText("YAML content") as HTMLTextAreaElement).value).toBe(YAML));
    const [call] = callsTo(fetchStub, "/api/v1/files/fetch");
    expect(JSON.parse((call[1] as RequestInit).body as string)).toEqual({
      url: "https://raw.githubusercontent.com/acme/svc/main/catalog-info.yaml",
    });
  });
});
