import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor, within } from "@testing-library/react";
import { QueryClient } from "@tanstack/react-query";
import type { CatalogFileRequest } from "../api/catalogFiles";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";
import CatalogFilesBatchModal from "./CatalogFilesBatchModal";

const file = (name: string): CatalogFileRequest => ({ kind: "Component", metadata: { name }, spec: {} });
const files = (n: number) => Array.from({ length: n }, (_, i) => file(`f${i}`));

type Body = { files: CatalogFileRequest[] };
const rowsFor = (init: RequestInit | undefined, status: string, withIds = false) =>
  (JSON.parse(init!.body as string) as Body).files.map((f, index) => ({
    index,
    kind: "Component",
    namespace: "default",
    name: f.metadata.name,
    status,
    ...(withIds ? { fileId: 500 + index } : {}),
  }));

describe("CatalogFilesBatchModal", () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    localStorage.setItem("toadie.auth.token", "fake-token");
    fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  const calls = (path: string) => fetchMock.mock.calls.filter(([url]) => url === path);

  test("converts fresh on open and again on Confirm, so neither step acts on the page's snapshot", async () => {
    fetchMock.mockImplementation(async (url: string, init?: RequestInit) =>
      jsonResponse(200, { results: rowsFor(init, "CREATED", !url.endsWith("/check")) }),
    );
    const convert = vi.fn(async () => files(2));
    const user = userEvent.setup();
    renderWithProviders(<CatalogFilesBatchModal convert={convert} onClose={() => undefined} />);

    const dialog = await screen.findByRole("dialog", { name: "Create / update catalog files" });
    expect(await within(dialog).findAllByText("Would be created")).toHaveLength(2);
    expect(convert).toHaveBeenCalledTimes(1);
    expect(calls("/api/v1/files/import")).toHaveLength(0);

    await user.click(within(dialog).getByRole("button", { name: "Confirm" }));
    expect(await within(dialog).findAllByText("Created")).toHaveLength(2);
    expect(convert).toHaveBeenCalledTimes(2);
    expect(calls("/api/v1/files/import")).toHaveLength(1);
  });

  test("a failed second chunk shows the STORED rows with a stopped-after notice, hides the predictions and still refreshes the catalog", async () => {
    const invalidateSpy = vi.spyOn(QueryClient.prototype, "invalidateQueries");
    let importCalls = 0;
    fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
      if (url === "/api/v1/files/import/check") return jsonResponse(200, { results: rowsFor(init, "CREATED") });
      importCalls += 1;
      return importCalls === 1 ? jsonResponse(200, { results: rowsFor(init, "CREATED", true) }) : jsonResponse(500, { title: "boom", status: 500 });
    });
    const user = userEvent.setup();
    renderWithProviders(<CatalogFilesBatchModal convert={async () => files(201)} onClose={() => undefined} />);

    const dialog = await screen.findByRole("dialog", { name: "Create / update catalog files" });
    await within(dialog).findByText(/201 of 201 would be stored/);
    await user.click(within(dialog).getByRole("button", { name: "Confirm" }));

    expect(await within(dialog).findByText(/Stopped after 200 of 201 files/)).toBeInTheDocument();
    expect(within(dialog).getByText("These files were stored before the failure:")).toBeInTheDocument();
    expect(within(dialog).getByText("200 of 200 stored — 200 created, 0 updated, 0 unchanged")).toBeInTheDocument();
    expect(within(dialog).queryByText("Would be created")).not.toBeInTheDocument();
    expect(within(dialog).getByRole("link", { name: "Edit f0" })).toHaveAttribute("href", "/files/500/edit");
    expect(within(dialog).getByRole("button", { name: "Close" })).toBeInTheDocument();
    await waitFor(() => expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ["catalogFiles"] }));
    invalidateSpy.mockRestore();
  });

  test("a failure on the first chunk says nothing was stored", async () => {
    fetchMock.mockImplementation(async (url: string, init?: RequestInit) =>
      url === "/api/v1/files/import/check" ? jsonResponse(200, { results: rowsFor(init, "CREATED") }) : jsonResponse(500, { title: "boom", status: 500 }),
    );
    const user = userEvent.setup();
    renderWithProviders(<CatalogFilesBatchModal convert={async () => files(1)} onClose={() => undefined} />);

    const dialog = await screen.findByRole("dialog", { name: "Create / update catalog files" });
    await within(dialog).findByText("Would be created");
    await user.click(within(dialog).getByRole("button", { name: "Confirm" }));

    expect(await within(dialog).findByText(/Stopped after 0 of 1 files/)).toBeInTheDocument();
    expect(within(dialog).queryByText("Would be created")).not.toBeInTheDocument();
  });

  test("a failed check shows the failure and offers no Confirm", async () => {
    fetchMock.mockResolvedValue(jsonResponse(500, { title: "boom", status: 500 }));
    renderWithProviders(<CatalogFilesBatchModal convert={async () => files(1)} onClose={() => undefined} />);

    const dialog = await screen.findByRole("dialog", { name: "Create / update catalog files" });
    expect(await within(dialog).findByText(/The check failed/)).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "Confirm" })).toBeDisabled();
  });

  test("a long report lists the rows that need attention first and announces the cap", async () => {
    fetchMock.mockImplementation(async (_url: string, init?: RequestInit) =>
      jsonResponse(200, {
        results: rowsFor(init, "CREATED").map((row) => (row.name === "f349" ? { ...row, status: "INVALID", message: "refused" } : row)),
      }),
    );
    renderWithProviders(<CatalogFilesBatchModal convert={async () => files(350)} onClose={() => undefined} />);

    const dialog = await screen.findByRole("dialog", { name: "Create / update catalog files" });
    const table = await within(dialog).findByRole("table", { name: "Catalog file results" });
    // 350 rows, 300 listed + the header: the INVALID row (last in request order) leads the list.
    expect(within(table).getAllByRole("row")).toHaveLength(301);
    expect(within(table).getAllByRole("row")[1]).toHaveTextContent("f349");
    expect(within(dialog).getByText(/Showing the first 300 of 350 rows/)).toBeInTheDocument();
  });
});
