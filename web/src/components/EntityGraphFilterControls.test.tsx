import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { jsonResponse } from "../test/http";
import { renderWithProviders, screen } from "../test/render";
import EntityGraphFilterControls from "./EntityGraphFilterControls";
import type { EntityGraphFilterControlsState } from "../hooks/useEntityGraphFilterState";

type FetchMock = ReturnType<typeof vi.fn>;

function controls(overrides: Partial<EntityGraphFilterControlsState> = {}): EntityGraphFilterControlsState {
  return {
    blueprints: [],
    setBlueprints: vi.fn(),
    q: "",
    setQ: vi.fn(),
    team: "",
    setTeam: vi.fn(),
    ...overrides,
  };
}

describe("EntityGraphFilterControls", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("toadie.auth.token", "fake-token");
    mockFetch.mockImplementation((url: string) => {
      // The Team select's own options pool: `useEntityOptions(TEAM_BLUEPRINT)` over
      // `GET /api/v1/entities?blueprint=_team…`.
      if (url.startsWith("/api/v1/entities?blueprint=_team"))
        return Promise.resolve(
          jsonResponse(200, {
            items: [
              { id: 10, identifier: "platform", title: "Platform Team", findings: [] },
              { id: 11, identifier: "growth", title: "Growth Team", findings: [] },
            ],
            page: 1,
            pageSize: 100,
            total: 2,
          }),
        );
      return Promise.resolve(
        jsonResponse(200, { items: [{ id: 1, identifier: "team" }, { id: 2, identifier: "service" }] }),
      );
    });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("picking a blueprint calls setBlueprints with the new selection", async () => {
    const setBlueprints = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<EntityGraphFilterControls controls={controls({ setBlueprints })} />);

    await user.click(screen.getByLabelText("Blueprints", { selector: "input" }));
    await user.click(await screen.findByRole("option", { name: "team" }));

    expect(setBlueprints).toHaveBeenCalledWith(["team"]);
  });

  test("typing in the search field calls setQ, and the clear button resets it", async () => {
    const setQ = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<EntityGraphFilterControls controls={controls({ q: "pay", setQ })} />);

    await user.click(screen.getByLabelText("Clear search filter"));
    expect(setQ).toHaveBeenCalledWith("");
  });

  test("picking a team calls setTeam, fed by the _team blueprint's own entities", async () => {
    const setTeam = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<EntityGraphFilterControls controls={controls({ setTeam })} />);

    await user.click(screen.getByLabelText("Team", { selector: "input" }));
    expect(mockFetch).toHaveBeenCalledWith(
      expect.stringMatching(/^\/api\/v1\/entities\?blueprint=_team/),
      expect.anything(),
    );
    await user.click(await screen.findByRole("option", { name: "platform — Platform Team" }));

    expect(setTeam).toHaveBeenCalledWith("platform");
  });

  test("clearing the team filter calls setTeam with an empty string", async () => {
    const setTeam = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<EntityGraphFilterControls controls={controls({ team: "platform", setTeam })} />);

    await user.click(screen.getByLabelText("Clear team filter"));
    expect(setTeam).toHaveBeenCalledWith("");
  });
});
