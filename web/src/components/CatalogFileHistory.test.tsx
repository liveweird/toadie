import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import CatalogFileHistory from "./CatalogFileHistory";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const TOKEN_KEY = "toadie.auth.token";

type FetchMock = ReturnType<typeof vi.fn>;

let nextId = 1;

function event(type: string, params: Record<string, string> = {}) {
  return {
    id: nextId++,
    catalogFileId: 7,
    userId: 2,
    userName: "Mona Maintainer",
    timestamp: Date.UTC(2026, 7, 30, 9, 15),
    type,
    params,
  };
}

function servePage(mockFetch: FetchMock, items: unknown[], total = items.length) {
  mockFetch.mockImplementation(() =>
    Promise.resolve(jsonResponse(200, { items, page: 1, pageSize: 10, total })),
  );
}

describe("CatalogFileHistory", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    nextId = 1;
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem(TOKEN_KEY, "fake-token");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("renders every event kind localized, with the actor and timestamp", async () => {
    servePage(mockFetch, [
      event("DELETED"),
      event("SYNCED", { changed: "metadata.title", "metadata.title.to": "From the repo" }),
      event("SYNCED"),
      event("UPDATED", { changed: "spec.owner,metadata.description" }),
      event("CREATED", { kind: "Component", origin: "import" }),
      event("CREATED", { kind: "Component" }),
    ]);
    renderWithProviders(<CatalogFileHistory fileId={7} />);

    expect(await screen.findByText("File deleted.")).toBeInTheDocument();
    expect(screen.getByText("Synced from the repo: Title.")).toBeInTheDocument();
    expect(screen.getByText("Synced from the repo — no changes.")).toBeInTheDocument();
    expect(screen.getByText("File updated: Owner, Description.")).toBeInTheDocument();
    expect(screen.getByText("File created by import.")).toBeInTheDocument();
    expect(screen.getByText("File created.")).toBeInTheDocument();
    expect(screen.getAllByText(/Mona Maintainer ·/)).toHaveLength(6);
  });

  test("a valued change gets its own line; a name-only one leaves the sentence to say it", async () => {
    servePage(mockFetch, [
      event("UPDATED", {
        changed: "spec.owner,metadata.tags,metadata.description",
        "spec.owner.from": "group:default/platform",
        "spec.owner.to": "group:default/payments",
        "metadata.tags.added": "billing",
        "metadata.tags.removed": "legacy",
      }),
    ]);
    renderWithProviders(<CatalogFileHistory fileId={7} />);

    expect(
      await screen.findByText("Owner: group:default/platform → group:default/payments"),
    ).toBeInTheDocument();
    expect(screen.getByText("Tags: +billing −legacy")).toBeInTheDocument();
    // The description changed but its text is never recorded — no body line repeats the title.
    expect(screen.queryByText(/^Description:/)).toBeNull();
  });

  test("set and cleared read differently from a two-sided change", async () => {
    servePage(mockFetch, [
      event("UPDATED", { changed: "sourceUrl", "sourceUrl.from": "https://example.com/a.yaml" }),
      event("UPDATED", { changed: "metadata.title", "metadata.title.to": "Checkout" }),
    ]);
    renderWithProviders(<CatalogFileHistory fileId={7} />);

    expect(await screen.findByText("Source file URL: cleared")).toBeInTheDocument();
    expect(screen.getByText("Title: set to Checkout")).toBeInTheDocument();
  });

  test("labels and annotations name the entry that moved", async () => {
    servePage(mockFetch, [
      event("UPDATED", {
        changed: "metadata.labels.tier,metadata.annotations.docs",
        "metadata.labels.tier.from": "gold",
        "metadata.labels.tier.to": "silver",
        "metadata.annotations.docs.to": "https://docs.example.com",
      }),
    ]);
    renderWithProviders(<CatalogFileHistory fileId={7} />);

    expect(
      await screen.findByText("File updated: Label “tier”, Annotation “docs”."),
    ).toBeInTheDocument();
    expect(screen.getByText("Label “tier”: gold → silver")).toBeInTheDocument();
    expect(screen.getByText("Annotation “docs”: set to https://docs.example.com")).toBeInTheDocument();
  });

  test("an unknown event kind and an unknown field render raw", async () => {
    servePage(mockFetch, [event("RESTORED"), event("UPDATED", { changed: "spec.somethingNew" })]);
    renderWithProviders(<CatalogFileHistory fileId={7} />);

    expect(await screen.findByText("RESTORED")).toBeInTheDocument();
    expect(screen.getByText("File updated: spec.somethingNew.")).toBeInTheDocument();
  });

  test("an empty history says so", async () => {
    servePage(mockFetch, [], 0);
    renderWithProviders(<CatalogFileHistory fileId={7} />);

    expect(await screen.findByText("No history yet.")).toBeInTheDocument();
  });

  test("a failed load shows an error instead of the empty-history note", async () => {
    mockFetch.mockResolvedValue(jsonResponse(500, { status: 500 }));
    renderWithProviders(<CatalogFileHistory fileId={7} />);

    expect(await screen.findByText("Load failed (500)")).toBeInTheDocument();
    expect(screen.queryByText("No history yet.")).toBeNull();
  });

  test("the pager appears only past one page and asks for the next one", async () => {
    servePage(mockFetch, [event("CREATED", { kind: "Component" })], 24);
    const user = userEvent.setup();
    renderWithProviders(<CatalogFileHistory fileId={7} />);

    expect(await screen.findByText("File created.")).toBeInTheDocument();
    // The whole URL, not a substring: buildQuery returns no leading "?", so a missing one
    // silently addresses the SPA catch-all instead of the API.
    expect(mockFetch.mock.calls[0][0]).toBe("/api/v1/files/7/events?page=1&pageSize=10");
    await user.click(screen.getByRole("button", { name: "2" }));

    expect(
      mockFetch.mock.calls.some(([url]) => url === "/api/v1/files/7/events?page=2&pageSize=10"),
    ).toBe(true);
  });
});
