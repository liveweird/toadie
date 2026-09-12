import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor } from "@testing-library/react";
import Hierarchies from "./Hierarchies";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const TOKEN_KEY = "toadie.auth.token";
const ROLES_KEY = "toadie.auth.roles";

type FetchMock = ReturnType<typeof vi.fn>;

const SEED = [
  { id: 1, value: "composition", isDefault: false },
  { id: 2, value: "ownership", isDefault: false },
];

/**
 * Serves the dictionary endpoint statefully: GET returns `current`, a PUT stores its parsed
 * payload and re-mints ids for id-less items (the server contract the re-seed depends on).
 */
function serveDictionary(mockFetch: FetchMock, initial = SEED, putStatus = 204) {
  let current = [...initial];
  let nextId = 100;
  const puts: Array<{ items: Array<{ id?: number; value: string; isDefault: boolean }> }> = [];
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    if (!url.startsWith("/api/v1/dictionaries/hierarchies")) {
      return Promise.resolve(jsonResponse(404, {}));
    }
    if (init?.method === "PUT") {
      if (putStatus !== 204) {
        return Promise.resolve(jsonResponse(putStatus, { title: "x", status: putStatus }));
      }
      const body = JSON.parse(String(init.body)) as (typeof puts)[number];
      puts.push(body);
      current = body.items.map((item) => ({
        id: item.id ?? nextId++,
        value: item.value,
        isDefault: item.isDefault,
      }));
      return Promise.resolve(new Response(null, { status: 204 }));
    }
    return Promise.resolve(jsonResponse(200, { items: current }));
  });
  return puts;
}

function renderPage() {
  return renderWithProviders(<Hierarchies />, { route: "/hierarchies" });
}

describe("Hierarchies page", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem(TOKEN_KEY, "fake-token");
    localStorage.setItem(ROLES_KEY, JSON.stringify(["ADMIN"]));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("non-admin sees the ordered read-only list without editor controls or default badges", async () => {
    localStorage.setItem(ROLES_KEY, "[]");
    serveDictionary(mockFetch);
    renderPage();
    expect(await screen.findByText("composition")).toBeInTheDocument();
    expect(screen.getByText("ownership")).toBeInTheDocument();
    // Hierarchies have no default concept — no badge anywhere.
    expect(screen.queryByText("Default", { exact: true })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Add hierarchy" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Save" })).not.toBeInTheDocument();
  });

  test("editor prefills from the server, has no default radios, and Save stays disabled until dirty", async () => {
    serveDictionary(mockFetch);
    renderPage();
    const first = await screen.findByRole("textbox", { name: "Hierarchy 1" });
    expect(first).toHaveValue("composition");
    expect(screen.getByRole("textbox", { name: "Hierarchy 2" })).toHaveValue("ownership");
    expect(screen.queryByRole("radio")).not.toBeInTheDocument();
    const save = screen.getByRole("button", { name: "Save" });
    expect(save).toBeDisabled();
    await userEvent.type(first, "x");
    expect(save).toBeEnabled();
  });

  test("adding an entry sends it id-less and unflagged while existing rows keep their ids", async () => {
    const puts = serveDictionary(mockFetch);
    renderPage();
    await screen.findByRole("textbox", { name: "Hierarchy 1" });
    await userEvent.click(screen.getByRole("button", { name: "Add hierarchy" }));
    await userEvent.type(screen.getByRole("textbox", { name: "Hierarchy 3" }), "cost-center");
    await userEvent.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0].items).toEqual([
      { id: 1, value: "composition", isDefault: false },
      { id: 2, value: "ownership", isDefault: false },
      { value: "cost-center", isDefault: false },
    ]);
    // Re-seed: Save drops back to disabled and a resubmit would now carry the minted id.
    await waitFor(() => expect(screen.getByRole("button", { name: "Save" })).toBeDisabled());
  });

  test("reordering and removing rows shape the payload from the visible order", async () => {
    const puts = serveDictionary(mockFetch, [
      { id: 1, value: "composition", isDefault: false },
      { id: 2, value: "ownership", isDefault: false },
      { id: 3, value: "cost-center", isDefault: false },
    ]);
    renderPage();
    await screen.findByRole("textbox", { name: "Hierarchy 1" });
    await userEvent.click(screen.getByRole("button", { name: "Move hierarchy 3 up" }));
    await userEvent.click(screen.getByRole("button", { name: "Remove hierarchy 1" }));
    await userEvent.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0].items).toEqual([
      { id: 3, value: "cost-center", isDefault: false },
      { id: 2, value: "ownership", isDefault: false },
    ]);
  });

  test("a duplicate value flags the later row inline and blocks the save", async () => {
    const puts = serveDictionary(mockFetch);
    renderPage();
    await screen.findByRole("textbox", { name: "Hierarchy 1" });
    await userEvent.click(screen.getByRole("button", { name: "Add hierarchy" }));
    await userEvent.type(screen.getByRole("textbox", { name: "Hierarchy 3" }), "Ownership");
    await userEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByText("This hierarchy is already in the list")).toBeInTheDocument();
    expect(puts).toHaveLength(0);
  });

  test("a 409 save renders the conflict vocabulary and keeps the editor editable", async () => {
    serveDictionary(mockFetch, SEED, 409);
    renderPage();
    const first = await screen.findByRole("textbox", { name: "Hierarchy 1" });
    await userEvent.type(first, "x");
    await userEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(
      await screen.findByText(/Save conflict — swapping two values needs two saves/),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save" })).toBeEnabled();
  });

  test("a save whose re-read fails freezes the editor behind a reload prompt", async () => {
    let served = false;
    mockFetch.mockImplementation((_url: string, init?: RequestInit) => {
      if (init?.method === "PUT") return Promise.resolve(new Response(null, { status: 204 }));
      if (!served) {
        served = true;
        return Promise.resolve(jsonResponse(200, { items: SEED }));
      }
      return Promise.reject(new Error("network down"));
    });
    renderPage();
    const first = await screen.findByRole("textbox", { name: "Hierarchy 1" });
    await userEvent.type(first, "x");
    await userEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(
      await screen.findByText(/Saved — but reloading the editor failed/),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
  });

  test("a dirty Cancel asks before discarding and reset restores the loaded values", async () => {
    serveDictionary(mockFetch);
    renderPage();
    const first = await screen.findByRole("textbox", { name: "Hierarchy 1" });
    await userEvent.type(first, "x");
    await userEvent.click(screen.getByRole("button", { name: "Cancel" }));
    await userEvent.click(await screen.findByRole("button", { name: "Discard" }));
    expect(screen.getByRole("textbox", { name: "Hierarchy 1" })).toHaveValue("composition");
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
  });

  test("a load failure renders the alert instead of an empty editor", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(500, { title: "x", status: 500 })));
    renderPage();
    expect(await screen.findByText("Could not load the hierarchies")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Save" })).not.toBeInTheDocument();
  });
});
