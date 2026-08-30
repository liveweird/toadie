import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { fireEvent, screen, waitFor } from "@testing-library/react";
import LensPicker from "./LensPicker";
import { useCatalogFileFilterState } from "../hooks/useCatalogFileFilterState";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

type FetchMock = ReturnType<typeof vi.fn>;

const VIEW_KEY = "lensPickerTest";
const STORE_PREFIX = `toadie.viewSettings.${VIEW_KEY}.filter`;

const LENSES = [
  {
    id: 1,
    name: "My private",
    visibility: "PRIVATE",
    filters: { namespace: "team-a", kind: ["Component"] },
    createdBy: 5,
    creatorName: "Me",
    creatorDeleted: false,
    createdAt: 1,
    updatedAt: 1,
  },
  {
    id: 2,
    name: "My public",
    visibility: "PUBLIC",
    filters: { tag: "java" },
    createdBy: 5,
    creatorName: "Me",
    creatorDeleted: false,
    createdAt: 1,
    updatedAt: 1,
  },
  {
    id: 3,
    name: "Theirs",
    visibility: "PUBLIC",
    filters: { namespace: "team-b" },
    createdBy: 8,
    creatorName: "Alice",
    creatorDeleted: false,
    createdAt: 1,
    updatedAt: 1,
  },
];

/** The picker over the REAL filter hook, with probes for the applied slots. */
function Harness() {
  const filters = useCatalogFileFilterState(VIEW_KEY);
  return (
    <div>
      <LensPicker values={filters.values} controls={filters.controls} />
      <button type="button" onClick={() => filters.controls.setTag("changed")}>
        mutate-tag
      </button>
      <div data-testid="namespace">{filters.controls.namespace}</div>
      <div data-testid="kinds">{filters.controls.kinds.join(",")}</div>
      <div data-testid="tag">{filters.controls.tag}</div>
    </div>
  );
}

async function openPicker() {
  fireEvent.click(screen.getByLabelText("Lens", { selector: "input" }));
  await screen.findAllByRole("option");
}

describe("LensPicker", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("toadie.auth.token", "fake-token");
    localStorage.setItem("toadie.auth.userId", "5");
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url.startsWith("/api/v1/lenses")) {
        if (!init?.method || init.method === "GET") return Promise.resolve(jsonResponse(200, { items: LENSES }));
        if (init.method === "POST") return Promise.resolve(jsonResponse(201, { ...LENSES[0], id: 9 }));
        return Promise.resolve(new Response(null, { status: 204 }));
      }
      return Promise.resolve(jsonResponse(404, {}));
    });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("groups lenses by visibility and names foreign creators", async () => {
    renderWithProviders(<Harness />);
    await openPicker();
    expect(screen.getByRole("option", { name: "My private" })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "My public" })).toBeInTheDocument();
    // The foreign public lens carries its creator's name for disambiguation.
    expect(screen.getByRole("option", { name: "Theirs — Alice" })).toBeInTheDocument();
    expect(screen.getByText("Private")).toBeInTheDocument();
    expect(screen.getByText("Public")).toBeInTheDocument();
  });

  test("selecting a lens applies its payload into the filter slots", async () => {
    renderWithProviders(<Harness />);
    await openPicker();
    fireEvent.click(screen.getByRole("option", { name: "My private" }));
    await waitFor(() => expect(screen.getByTestId("namespace")).toHaveTextContent("team-a"));
    expect(screen.getByTestId("kinds")).toHaveTextContent(/^Component$/);
    // A lens with no kind set restores every kind (absent = all visible).
    await openPicker();
    fireEvent.click(screen.getByRole("option", { name: "My public" }));
    await waitFor(() => expect(screen.getByTestId("tag")).toHaveTextContent("java"));
    expect(screen.getByTestId("kinds").textContent?.split(",")).toHaveLength(7);
    expect(screen.getByTestId("namespace")).toHaveTextContent(/^$/);
  });

  test("diverging from the selected lens shows the modified badge", async () => {
    renderWithProviders(<Harness />);
    await openPicker();
    fireEvent.click(screen.getByRole("option", { name: "My private" }));
    expect(screen.queryByText("Modified")).not.toBeInTheDocument();
    fireEvent.click(screen.getByText("mutate-tag"));
    expect(await screen.findByText("Modified")).toBeInTheDocument();
  });

  test("owner actions appear only for an owned lens", async () => {
    renderWithProviders(<Harness />);
    const toggleMenu = () => fireEvent.click(screen.getByLabelText("Lens actions"));
    // No selection: only Save-as.
    toggleMenu();
    expect(await screen.findByText("Save as new lens…")).toBeInTheDocument();
    expect(screen.queryByText("Save changes")).not.toBeInTheDocument();
    toggleMenu(); // the Menu toggles on its target — close before moving on

    await openPicker();
    fireEvent.click(screen.getByRole("option", { name: "Theirs — Alice" }));
    toggleMenu();
    expect(await screen.findByText("Save as new lens…")).toBeInTheDocument();
    expect(screen.queryByText("Delete")).not.toBeInTheDocument();
    toggleMenu();

    await openPicker();
    fireEvent.click(screen.getByRole("option", { name: "My private" }));
    toggleMenu();
    expect(await screen.findByText("Save changes")).toBeInTheDocument();
    expect(screen.getByText("Rename / visibility…")).toBeInTheDocument();
    expect(screen.getByText("Delete")).toBeInTheDocument();
  });

  test("save-as posts the current filters under the chosen name", async () => {
    localStorage.setItem(`${STORE_PREFIX}.namespace`, JSON.stringify("team-z"));
    renderWithProviders(<Harness />);
    fireEvent.click(screen.getByLabelText("Lens actions"));
    fireEvent.click(await screen.findByText("Save as new lens…"));
    fireEvent.change(await screen.findByLabelText("Name"), { target: { value: "Fresh" } });
    fireEvent.click(screen.getByRole("radio", { name: /Public/ }));
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => {
      const post = mockFetch.mock.calls.find(([, init]) => (init as RequestInit)?.method === "POST");
      expect(post).toBeDefined();
      const body = JSON.parse((post?.[1] as RequestInit).body as string);
      expect(body.name).toBe("Fresh");
      expect(body.visibility).toBe("PUBLIC");
      expect(body.filters.namespace).toBe("team-z");
    });
  });

  test("delete goes through the confirm modal", async () => {
    renderWithProviders(<Harness />);
    await openPicker();
    fireEvent.click(screen.getByRole("option", { name: "My private" }));
    fireEvent.click(screen.getByLabelText("Lens actions"));
    fireEvent.click(await screen.findByText("Delete"));
    expect(await screen.findByText('Delete lens "My private"?')).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Delete" }));
    await waitFor(() => {
      const del = mockFetch.mock.calls.find(([, init]) => (init as RequestInit)?.method === "DELETE");
      expect(del?.[0]).toBe("/api/v1/lenses/1");
    });
  });

  test("save changes overwrites the selected lens with the current filters", async () => {
    renderWithProviders(<Harness />);
    await openPicker();
    fireEvent.click(screen.getByRole("option", { name: "My private" }));
    fireEvent.click(screen.getByText("mutate-tag"));
    fireEvent.click(screen.getByLabelText("Lens actions"));
    fireEvent.click(await screen.findByText("Save changes"));
    await waitFor(() => {
      const put = mockFetch.mock.calls.find(([, init]) => (init as RequestInit)?.method === "PUT");
      expect(put?.[0]).toBe("/api/v1/lenses/1");
      const body = JSON.parse((put?.[1] as RequestInit).body as string);
      // Name and visibility stay; the payload is the CURRENT filters.
      expect(body.name).toBe("My private");
      expect(body.filters.tag).toBe("changed");
      expect(body.filters.namespace).toBe("team-a");
    });
  });

  test("a failed save-changes shows the inline error", async () => {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url.startsWith("/api/v1/lenses")) {
        if (!init?.method || init.method === "GET") return Promise.resolve(jsonResponse(200, { items: LENSES }));
        return Promise.resolve(jsonResponse(403, { title: "Forbidden", status: 403 }));
      }
      return Promise.resolve(jsonResponse(404, {}));
    });
    renderWithProviders(<Harness />);
    await openPicker();
    fireEvent.click(screen.getByRole("option", { name: "My private" }));
    fireEvent.click(screen.getByText("mutate-tag"));
    fireEvent.click(screen.getByLabelText("Lens actions"));
    fireEvent.click(await screen.findByText("Save changes"));
    expect(await screen.findByText("Only the creator can change a public lens")).toBeInTheDocument();
  });

  test("rename via the editor keeps the lens's STORED filters", async () => {
    renderWithProviders(<Harness />);
    await openPicker();
    fireEvent.click(screen.getByRole("option", { name: "My private" }));
    fireEvent.click(screen.getByText("mutate-tag"));
    fireEvent.click(screen.getByLabelText("Lens actions"));
    fireEvent.click(await screen.findByText("Rename / visibility…"));
    const nameInput = await screen.findByLabelText("Name");
    fireEvent.change(nameInput, { target: { value: "Renamed" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => {
      const put = mockFetch.mock.calls.find(([, init]) => (init as RequestInit)?.method === "PUT");
      expect(put?.[0]).toBe("/api/v1/lenses/1");
      const body = JSON.parse((put?.[1] as RequestInit).body as string);
      expect(body.name).toBe("Renamed");
      // The rename deliberately does NOT absorb the diverged current filters.
      expect(body.filters.tag).toBeUndefined();
      expect(body.filters.namespace).toBe("team-a");
    });
  });

  test("a save-as conflict renders the fixed 409 message inline", async () => {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url.startsWith("/api/v1/lenses")) {
        if (!init?.method || init.method === "GET") return Promise.resolve(jsonResponse(200, { items: LENSES }));
        return Promise.resolve(jsonResponse(409, { title: "Conflict", status: 409, detail: "dup" }));
      }
      return Promise.resolve(jsonResponse(404, {}));
    });
    renderWithProviders(<Harness />);
    fireEvent.click(screen.getByLabelText("Lens actions"));
    fireEvent.click(await screen.findByText("Save as new lens…"));
    fireEvent.change(await screen.findByLabelText("Name"), { target: { value: "Dup" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByText("You already have a lens with this name")).toBeInTheDocument();
  });
});
