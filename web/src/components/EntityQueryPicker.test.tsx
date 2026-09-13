import { useState } from "react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { fireEvent, screen, waitFor } from "@testing-library/react";
import EntityQueryPicker from "./EntityQueryPicker";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

type FetchMock = ReturnType<typeof vi.fn>;

const SAVED_QUERIES = [
  {
    id: 1,
    name: "My private",
    visibility: "PRIVATE",
    query: "MATCH (a)",
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
    query: "MATCH (b)",
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
    query: "MATCH (c)",
    createdBy: 8,
    creatorName: "Alice",
    creatorDeleted: false,
    createdAt: 1,
    updatedAt: 1,
  },
];

/** The picker over locally-held draft state — the page's role in real usage, standing in for
 *  `useEntityQuery`'s `draft`/`runText`. */
function Harness() {
  const [draft, setDraft] = useState("");
  return (
    <div>
      <EntityQueryPicker draft={draft} onPick={setDraft} />
      <button type="button" onClick={() => setDraft("changed")}>
        mutate-draft
      </button>
      <div data-testid="draft">{draft}</div>
    </div>
  );
}

async function openPicker() {
  fireEvent.click(screen.getByLabelText("Saved query", { selector: "input" }));
  await screen.findAllByRole("option");
}

describe("EntityQueryPicker", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("toadie.auth.token", "fake-token");
    localStorage.setItem("toadie.auth.userId", "5");
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url.startsWith("/api/v1/entity-queries")) {
        if (!init?.method || init.method === "GET") return Promise.resolve(jsonResponse(200, { items: SAVED_QUERIES }));
        if (init.method === "POST") return Promise.resolve(jsonResponse(201, { ...SAVED_QUERIES[0], id: 9 }));
        return Promise.resolve(new Response(null, { status: 204 }));
      }
      return Promise.resolve(jsonResponse(404, {}));
    });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("groups saved queries by visibility and names foreign creators", async () => {
    renderWithProviders(<Harness />);
    await openPicker();
    expect(screen.getByRole("option", { name: "My private" })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "My public" })).toBeInTheDocument();
    // The foreign public query carries its creator's name for disambiguation.
    expect(screen.getByRole("option", { name: "Theirs — Alice" })).toBeInTheDocument();
    expect(screen.getByText("Private")).toBeInTheDocument();
    expect(screen.getByText("Public")).toBeInTheDocument();
  });

  test("picking an option runs its query text", async () => {
    renderWithProviders(<Harness />);
    await openPicker();
    fireEvent.click(screen.getByRole("option", { name: "My private" }));
    await waitFor(() => expect(screen.getByTestId("draft")).toHaveTextContent("MATCH (a)"));
  });

  test("re-picking the already-picked query keeps it selected (never deselects)", async () => {
    // The pick is shared across canvases: a page usually opens with it already selected.
    localStorage.setItem("toadie.viewSettings.entityQuery.picked", JSON.stringify("1"));
    renderWithProviders(<Harness />);
    await waitFor(() => expect(screen.getByLabelText("Saved query", { selector: "input" })).toHaveValue("My private"));
    await openPicker();
    fireEvent.click(screen.getByRole("option", { name: "My private" }));
    await waitFor(() => expect(screen.getByLabelText("Saved query", { selector: "input" })).toHaveValue("My private"));
  });

  test("diverging from the picked query shows the modified badge", async () => {
    renderWithProviders(<Harness />);
    await openPicker();
    fireEvent.click(screen.getByRole("option", { name: "My private" }));
    expect(screen.queryByText("Modified")).not.toBeInTheDocument();
    fireEvent.click(screen.getByText("mutate-draft"));
    expect(await screen.findByText("Modified")).toBeInTheDocument();
  });

  test("clearing the select forgets the pick without changing the draft", async () => {
    renderWithProviders(<Harness />);
    await openPicker();
    fireEvent.click(screen.getByRole("option", { name: "My private" }));
    await waitFor(() => expect(screen.getByTestId("draft")).toHaveTextContent("MATCH (a)"));
    fireEvent.click(screen.getByLabelText("Clear saved query"));
    expect(screen.getByTestId("draft")).toHaveTextContent("MATCH (a)");
  });

  test("owner actions appear only for an owned query", async () => {
    renderWithProviders(<Harness />);
    const toggleMenu = () => fireEvent.click(screen.getByLabelText("Saved query actions"));
    // No selection: only Save-as.
    toggleMenu();
    expect(await screen.findByText("Save as new query…")).toBeInTheDocument();
    expect(screen.queryByText("Save changes")).not.toBeInTheDocument();
    toggleMenu(); // the Menu toggles on its target — close before moving on

    await openPicker();
    fireEvent.click(screen.getByRole("option", { name: "Theirs — Alice" }));
    toggleMenu();
    expect(await screen.findByText("Save as new query…")).toBeInTheDocument();
    expect(screen.queryByText("Delete")).not.toBeInTheDocument();
    toggleMenu();

    await openPicker();
    fireEvent.click(screen.getByRole("option", { name: "My private" }));
    toggleMenu();
    expect(await screen.findByText("Save changes")).toBeInTheDocument();
    expect(screen.getByText("Rename / visibility…")).toBeInTheDocument();
    expect(screen.getByText("Delete")).toBeInTheDocument();
  });

  test("save-as posts the current draft under the chosen name", async () => {
    renderWithProviders(<Harness />);
    fireEvent.click(screen.getByText("mutate-draft"));
    fireEvent.click(screen.getByLabelText("Saved query actions"));
    fireEvent.click(await screen.findByText("Save as new query…"));
    fireEvent.change(await screen.findByLabelText("Name"), { target: { value: "Fresh" } });
    fireEvent.click(screen.getByRole("radio", { name: /Public/ }));
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => {
      const post = mockFetch.mock.calls.find(([, init]) => (init as RequestInit)?.method === "POST");
      expect(post).toBeDefined();
      const body = JSON.parse((post?.[1] as RequestInit).body as string);
      expect(body.name).toBe("Fresh");
      expect(body.visibility).toBe("PUBLIC");
      expect(body.query).toBe("changed");
    });
  });

  test("delete goes through the confirm modal", async () => {
    renderWithProviders(<Harness />);
    await openPicker();
    fireEvent.click(screen.getByRole("option", { name: "My private" }));
    fireEvent.click(screen.getByLabelText("Saved query actions"));
    fireEvent.click(await screen.findByText("Delete"));
    expect(
      await screen.findByText('Saved query "My private" will be gone for everyone who can see it.'),
    ).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Delete" }));
    await waitFor(() => {
      const del = mockFetch.mock.calls.find(([, init]) => (init as RequestInit)?.method === "DELETE");
      expect(del?.[0]).toBe("/api/v1/entity-queries/1");
    });
  });

  test("save changes overwrites the selected query with the current draft", async () => {
    renderWithProviders(<Harness />);
    await openPicker();
    fireEvent.click(screen.getByRole("option", { name: "My private" }));
    fireEvent.click(screen.getByText("mutate-draft"));
    fireEvent.click(screen.getByLabelText("Saved query actions"));
    fireEvent.click(await screen.findByText("Save changes"));
    await waitFor(() => {
      const put = mockFetch.mock.calls.find(([, init]) => (init as RequestInit)?.method === "PUT");
      expect(put?.[0]).toBe("/api/v1/entity-queries/1");
      const body = JSON.parse((put?.[1] as RequestInit).body as string);
      // Name and visibility stay; the payload is the CURRENT draft.
      expect(body.name).toBe("My private");
      expect(body.query).toBe("changed");
    });
  });

  test("a failed save-changes shows the inline error", async () => {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url.startsWith("/api/v1/entity-queries")) {
        if (!init?.method || init.method === "GET") return Promise.resolve(jsonResponse(200, { items: SAVED_QUERIES }));
        return Promise.resolve(jsonResponse(403, { title: "Forbidden", status: 403 }));
      }
      return Promise.resolve(jsonResponse(404, {}));
    });
    renderWithProviders(<Harness />);
    await openPicker();
    fireEvent.click(screen.getByRole("option", { name: "My private" }));
    fireEvent.click(screen.getByText("mutate-draft"));
    fireEvent.click(screen.getByLabelText("Saved query actions"));
    fireEvent.click(await screen.findByText("Save changes"));
    expect(await screen.findByText("Only the creator can change a public query")).toBeInTheDocument();
  });

  test("rename via the editor keeps the query's STORED text", async () => {
    renderWithProviders(<Harness />);
    await openPicker();
    fireEvent.click(screen.getByRole("option", { name: "My private" }));
    fireEvent.click(screen.getByText("mutate-draft"));
    fireEvent.click(screen.getByLabelText("Saved query actions"));
    fireEvent.click(await screen.findByText("Rename / visibility…"));
    const nameInput = await screen.findByLabelText("Name");
    fireEvent.change(nameInput, { target: { value: "Renamed" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => {
      const put = mockFetch.mock.calls.find(([, init]) => (init as RequestInit)?.method === "PUT");
      expect(put?.[0]).toBe("/api/v1/entity-queries/1");
      const body = JSON.parse((put?.[1] as RequestInit).body as string);
      expect(body.name).toBe("Renamed");
      // The rename deliberately does NOT absorb the diverged current draft.
      expect(body.query).toBe("MATCH (a)");
    });
  });

  test("a save-as conflict renders the fixed 409 message inline", async () => {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url.startsWith("/api/v1/entity-queries")) {
        if (!init?.method || init.method === "GET") return Promise.resolve(jsonResponse(200, { items: SAVED_QUERIES }));
        return Promise.resolve(jsonResponse(409, { title: "Conflict", status: 409, detail: "dup" }));
      }
      return Promise.resolve(jsonResponse(404, {}));
    });
    renderWithProviders(<Harness />);
    fireEvent.click(screen.getByLabelText("Saved query actions"));
    fireEvent.click(await screen.findByText("Save as new query…"));
    fireEvent.change(await screen.findByLabelText("Name"), { target: { value: "Dup" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByText("You already have a saved query with this name")).toBeInTheDocument();
  });

  test("a 400 carrying diagnostics shows the first message under the name field", async () => {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url.startsWith("/api/v1/entity-queries")) {
        if (!init?.method || init.method === "GET") return Promise.resolve(jsonResponse(200, { items: SAVED_QUERIES }));
        return Promise.resolve(
          jsonResponse(400, {
            title: "Bad Request",
            status: 400,
            diagnostics: [{ code: "UNKNOWN_LABEL", message: "unknown label 'srv'" }],
          }),
        );
      }
      return Promise.resolve(jsonResponse(404, {}));
    });
    renderWithProviders(<Harness />);
    fireEvent.click(screen.getByText("mutate-draft"));
    fireEvent.click(screen.getByLabelText("Saved query actions"));
    fireEvent.click(await screen.findByText("Save as new query…"));
    fireEvent.change(await screen.findByLabelText("Name"), { target: { value: "Fresh" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByText("unknown label 'srv'")).toBeInTheDocument();
  });
});
