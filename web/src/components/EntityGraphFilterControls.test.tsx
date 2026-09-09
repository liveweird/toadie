import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { jsonResponse } from "../test/http";
import { renderWithProviders, screen } from "../test/render";
import EntityGraphFilterControls from "./EntityGraphFilterControls";
import type { EntityGraphFilterControlsState } from "../hooks/useEntityGraphFilterState";

type FetchMock = ReturnType<typeof vi.fn>;

function controls(overrides: Partial<EntityGraphFilterControlsState> = {}): EntityGraphFilterControlsState {
  return { blueprints: [], setBlueprints: vi.fn(), q: "", setQ: vi.fn(), ...overrides };
}

describe("EntityGraphFilterControls", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("toadie.auth.token", "fake-token");
    mockFetch.mockResolvedValue(
      jsonResponse(200, { items: [{ id: 1, identifier: "team" }, { id: 2, identifier: "service" }] }),
    );
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
});
