import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import EntityHistory from "./EntityHistory";
import { jsonResponse } from "../test/http";
import { blueprintResponse, entityResponse, pageOf } from "../test/fixtures";
import { renderWithProviders } from "../test/render";

const TOKEN_KEY = "toadie.auth.token";

type FetchMock = ReturnType<typeof vi.fn>;

const BLUEPRINT = blueprintResponse({
  schema: {
    properties: { stage: { type: "string", title: "Stage" }, tier: { type: "string" } },
    required: [],
  },
  relations: { owner: { title: "Owning team", target: "team", required: false, many: false } },
});

let nextId = 1;

function event(type: string, params: Record<string, string> = {}, who: Record<string, unknown> = {}) {
  return {
    id: nextId++,
    entityId: 5,
    userId: 2,
    userName: "Mona Maintainer",
    userDeleted: false,
    userServiceAccount: false,
    timestamp: Date.UTC(2026, 7, 30, 9, 15),
    type,
    params,
    ...who,
  };
}

function servePage(mockFetch: FetchMock, items: unknown[], total = items.length) {
  mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(200, pageOf(items, { pageSize: 10, total }))));
}

function renderHistory(entity = entityResponse()) {
  return renderWithProviders(<EntityHistory entity={entity} blueprint={BLUEPRINT} />);
}

describe("EntityHistory", () => {
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

  test("renders every sentence type localized, with the actor and timestamp", async () => {
    servePage(mockFetch, [
      event("DELETED"),
      event("SYNCED", { changed: "title", "title.to": "From the source" }),
      event("SYNCED"),
      event("UPDATED", { changed: "properties.stage,team", origin: "import-deferred" }),
      event("UPDATED", { changed: "properties.stage", origin: "import" }),
      event("UPDATED", {
        changed: "relations.owner",
        origin: "cascade",
        causeEntityId: "9",
        causeBlueprint: "team",
        causeFrom: "payments",
        causeTo: "billing",
      }),
      event("UPDATED", { changed: "properties.stage,relations.owner" }),
      event("CREATED", { blueprint: "service", origin: "import" }),
      event("CREATED", { blueprint: "service" }),
    ]);
    renderHistory();

    expect(await screen.findByText("Entity deleted.")).toBeInTheDocument();
    expect(screen.getByText("Synced from the source: Title.")).toBeInTheDocument();
    expect(screen.getByText("Synced from the source — no changes.")).toBeInTheDocument();
    expect(
      screen.getByText("Entity updated by import (deferred references): Stage, Team."),
    ).toBeInTheDocument();
    expect(screen.getByText("Entity updated by import: Stage.")).toBeInTheDocument();
    expect(
      screen.getByText("Entity updated after team “payments” was renamed to “billing”: Owning team."),
    ).toBeInTheDocument();
    expect(screen.getByText("Entity updated: Stage, Owning team.")).toBeInTheDocument();
    expect(screen.getByText("Entity created by import.")).toBeInTheDocument();
    expect(screen.getByText("Entity created.")).toBeInTheDocument();
    expect(screen.getAllByText(/Mona Maintainer ·/)).toHaveLength(9);
  });

  test("an unknown origin falls back to the plain sentence; an incomplete cascade does too", async () => {
    servePage(mockFetch, [
      event("UPDATED", { changed: "title", origin: "teleport" }),
      event("UPDATED", { changed: "icon", origin: "cascade" }),
      event("CREATED", { origin: "teleport" }),
    ]);
    renderHistory();

    expect(await screen.findByText("Entity updated: Title.")).toBeInTheDocument();
    expect(screen.getByText("Entity updated: Icon.")).toBeInTheDocument();
    expect(screen.getByText("Entity created.")).toBeInTheDocument();
  });

  test("a valued change gets its own line; a name-only one leaves the sentence to say it", async () => {
    servePage(mockFetch, [
      event("UPDATED", {
        changed: "title,properties.tags,identifier,properties.stage",
        "title.from": "Checkout",
        "title.to": "Checkout service",
        "properties.tags.added": "billing",
        "properties.tags.removed": "legacy",
        "identifier.to": "checkout-svc",
      }),
    ]);
    renderHistory();

    expect(await screen.findByText("Title: Checkout → Checkout service")).toBeInTheDocument();
    expect(screen.getByText("tags: +billing −legacy")).toBeInTheDocument();
    expect(screen.getByText("Identifier: set to checkout-svc")).toBeInTheDocument();
    // Stage changed by name only — the sentence names it, no body line repeats it.
    expect(screen.queryByText(/^Stage:/)).toBeNull();
  });

  test("properties and relations are named by their blueprint title, else by the raw id", async () => {
    servePage(mockFetch, [
      event("UPDATED", {
        changed: "properties.stage,properties.tier,properties.gone,relations.owner,relations.vanished",
        "properties.stage.from": "dev",
        "properties.stage.to": "prod",
        "properties.gone.to": "x",
        "relations.vanished.to": "y",
      }),
    ]);
    renderHistory();

    expect(
      await screen.findByText("Entity updated: Stage, tier, gone, Owning team, vanished."),
    ).toBeInTheDocument();
    expect(screen.getByText("Stage: dev → prod")).toBeInTheDocument();
    expect(screen.getByText("gone: set to x")).toBeInTheDocument();
    expect(screen.getByText("vanished: set to y")).toBeInTheDocument();
  });

  test("an unknown field and an unknown event kind render raw", async () => {
    servePage(mockFetch, [event("RESTORED"), event("UPDATED", { changed: "somethingNew" })]);
    renderHistory();

    expect(await screen.findByText("RESTORED")).toBeInTheDocument();
    expect(screen.getByText("Entity updated: somethingNew.")).toBeInTheDocument();
  });

  test("the overflow and cascade notes pluralize", async () => {
    servePage(mockFetch, [
      event("UPDATED", { changed: "title", more: "1", cascaded: "1" }),
      event("UPDATED", { changed: "icon", more: "12", cascaded: "3" }),
      event("UPDATED", { changed: "team", more: "nope", cascaded: "0" }),
    ]);
    renderHistory();

    expect(await screen.findByText("…and 1 more field")).toBeInTheDocument();
    expect(screen.getByText("1 referring entity updated")).toBeInTheDocument();
    expect(screen.getByText("…and 12 more fields")).toBeInTheDocument();
    expect(screen.getByText("3 referring entities updated")).toBeInTheDocument();
    expect(screen.getAllByText(/more field/)).toHaveLength(2);
    expect(screen.getAllByText(/referring entit/)).toHaveLength(2);
  });

  test("the actor carries its account state", async () => {
    servePage(mockFetch, [
      event("UPDATED", { changed: "title" }, { userName: "ci-bot", userServiceAccount: true, userDeleted: true }),
      event("UPDATED", { changed: "title" }, { userName: "mcp-writer", userServiceAccount: true }),
      event("UPDATED", { changed: "title" }, { userName: "Gone Gary", userDeleted: true }),
      event("UPDATED", { changed: "title" }),
    ]);
    renderHistory();

    expect(await screen.findByText(/^ci-bot \(revoked integration client\) ·/)).toBeInTheDocument();
    expect(screen.getByText(/^mcp-writer \(integration client\) ·/)).toBeInTheDocument();
    expect(screen.getByText(/^Gone Gary \(deleted\) ·/)).toBeInTheDocument();
    expect(screen.getByText(/^Mona Maintainer ·/)).toBeInTheDocument();
  });

  test("an entity that predates the history ends in a created-by footer", async () => {
    servePage(mockFetch, [event("UPDATED", { changed: "title" })]);
    renderHistory(entityResponse({ creatorName: "Alice Creator", createdAt: Date.UTC(2026, 0, 5, 8, 30) }));

    expect(await screen.findByText("Entity updated: Title.")).toBeInTheDocument();
    expect(
      screen.getByText(/^Created by Alice Creator on .*2026.*, before change history was recorded\. Earlier changes are not shown\.$/),
    ).toBeInTheDocument();
  });

  test("the footer names a deleted creator and is absent once a CREATED event is shown", async () => {
    servePage(mockFetch, [event("CREATED", { blueprint: "service" })]);
    renderHistory(entityResponse({ creatorDeleted: true }));

    expect(await screen.findByText("Entity created.")).toBeInTheDocument();
    expect(screen.queryByText(/before change history was recorded/)).toBeNull();
  });

  test("with no events at all the footer sits under the empty note", async () => {
    servePage(mockFetch, [], 0);
    renderHistory(entityResponse({ creatorName: "Alice Creator", creatorDeleted: true }));

    expect(await screen.findByText("No history yet.")).toBeInTheDocument();
    expect(screen.getByText(/^Created by Alice Creator \(deleted\) on /)).toBeInTheDocument();
  });

  test("the footer belongs to the last page only", async () => {
    mockFetch.mockImplementation((url: string) =>
      Promise.resolve(
        jsonResponse(
          200,
          url.includes("page=2")
            ? pageOf([event("UPDATED", { changed: "icon" })], { page: 2, pageSize: 10, total: 11 })
            : pageOf([event("UPDATED", { changed: "title" })], { page: 1, pageSize: 10, total: 11 }),
        ),
      ),
    );
    const user = userEvent.setup();
    renderHistory();

    expect(await screen.findByText("Entity updated: Title.")).toBeInTheDocument();
    expect(screen.queryByText(/before change history was recorded/)).toBeNull();
    // The whole URL, not a substring: buildQuery returns no leading "?".
    expect(mockFetch.mock.calls[0][0]).toBe("/api/v1/entities/5/events?page=1&pageSize=10");

    await user.click(screen.getByRole("button", { name: "2" }));
    expect(await screen.findByText("Entity updated: Icon.")).toBeInTheDocument();
    expect(screen.getByText(/before change history was recorded/)).toBeInTheDocument();
  });

  test("a failed load shows an error instead of the empty note or the footer", async () => {
    mockFetch.mockResolvedValue(jsonResponse(500, { status: 500 }));
    renderHistory();

    expect(await screen.findByText("Load failed (500)")).toBeInTheDocument();
    expect(screen.queryByText("No history yet.")).toBeNull();
    expect(screen.queryByText(/before change history was recorded/)).toBeNull();
  });

  test("a pending load shows the named status", () => {
    mockFetch.mockImplementation(() => new Promise(() => {}));
    renderHistory();

    expect(screen.getByRole("status")).toBeInTheDocument();
  });
});
