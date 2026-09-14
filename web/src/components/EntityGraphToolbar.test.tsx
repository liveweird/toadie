import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { useState } from "react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen, within } from "../test/render";
import EntityGraphToolbar from "./EntityGraphToolbar";
import { useEntityGraphFilterState } from "../hooks/useEntityGraphFilterState";

function Host({
  queryForcedOpen = false,
  appliedCount,
  unstableSetter = false,
  hiddenRelationsCount,
}: {
  queryForcedOpen?: boolean;
  appliedCount?: number;
  /** Hand the toolbar a FRESH `onQueryOpenChange` closure on every render — the shape a
   *  `useStoredState` setter has on the real pages, not `useState`'s stable one. */
  unstableSetter?: boolean;
  hiddenRelationsCount?: number;
}) {
  const filters = useEntityGraphFilterState("toolbarTest", ["team", "service"]);
  const [queryOpen, setQueryOpenStable] = useState(false);
  const setQueryOpen = unstableSetter ? (open: boolean) => setQueryOpenStable(open) : setQueryOpenStable;
  return (
    <EntityGraphToolbar
      title="Entity graph"
      viewKey="toolbarTest"
      filters={filters}
      queryOpen={queryOpen}
      onQueryOpenChange={setQueryOpen}
      queryForcedOpen={queryForcedOpen}
      appliedCount={appliedCount}
      hiddenRelationsCount={hiddenRelationsCount}
      query={<div>the query bar</div>}
    >
      <button type="button">secondary control</button>
    </EntityGraphToolbar>
  );
}

describe("EntityGraphToolbar", () => {
  beforeEach(() => {
    localStorage.setItem("toadie.auth.token", "fake-token");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json({ items: [] })));
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("renders the title, all three collapsed toggles, and children; opening Visibility reveals the captioned Blueprints group", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Host />);
    expect(screen.getByRole("heading", { name: "Entity graph" })).toBeInTheDocument();

    const filtersToggle = screen.getByRole("button", { name: "Filters" });
    expect(filtersToggle).toHaveAttribute("aria-expanded", "false");
    const visibilityToggle = screen.getByRole("button", { name: /^Visibility/ });
    expect(visibilityToggle).toHaveAttribute("aria-expanded", "false");
    const queryToggle = screen.getByRole("button", { name: "Query" });
    expect(queryToggle).toHaveAttribute("aria-expanded", "false");

    // Collapsed: the pills group is absent, like every other section's content.
    expect(screen.queryByRole("group", { name: "Blueprints" })).not.toBeInTheDocument();

    await user.click(visibilityToggle);
    expect(visibilityToggle).toHaveAttribute("aria-expanded", "true");
    const group = screen.getByRole("group", { name: "Blueprints" });
    expect(screen.getByText("Blueprints")).toBeInTheDocument();
    expect(group).toBeInTheDocument();

    expect(screen.getByRole("button", { name: "secondary control" })).toBeInTheDocument();
    expect(screen.queryByText("the query bar")).not.toBeInTheDocument();
  });

  test("the Visibility badge counts hidden blueprints plus hidden relations, and persists per view", async () => {
    const user = userEvent.setup();
    const { unmount } = renderWithProviders(<Host hiddenRelationsCount={1} />);

    const visibilityToggle = screen.getByRole("button", { name: /^Visibility/ });
    await user.click(visibilityToggle);
    await user.click(screen.getByRole("checkbox", { name: "service" }));
    unmount();

    // Reload the host: the toggle stays open (persisted under `toolbarTest.pillsOpen`) and the
    // badge counts the one hidden blueprint plus the caller's hidden-relations count.
    renderWithProviders(<Host hiddenRelationsCount={1} />);
    const reopened = screen.getByRole("button", { name: /^Visibility/ });
    expect(reopened).toHaveAttribute("aria-expanded", "true");
    expect(within(reopened).getByText("2")).toBeInTheDocument();
    expect(localStorage.getItem("toadie.viewSettings.toolbarTest.pillsOpen")).toBe("true");
  });

  test("the Filters toggle opens the drawer and persists per view", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Host />);
    const toggle = screen.getByRole("button", { name: "Filters" });
    await user.click(toggle);
    expect(toggle).toHaveAttribute("aria-expanded", "true");
    expect(localStorage.getItem("toadie.viewSettings.toolbarTest.filtersOpen")).toBe("true");
  });

  test("no applied badge when appliedCount is unset", () => {
    renderWithProviders(<Host />);
    expect(screen.queryByTestId("entityQuery-applied")).not.toBeInTheDocument();
  });

  test("the Applied badge shows on the Query toggle for 0, 1, and 3 entities", () => {
    const { rerender } = renderWithProviders(<Host appliedCount={0} />);
    expect(screen.getByTestId("entityQuery-applied")).toHaveTextContent("Applied · 0 entities");

    rerender(<Host appliedCount={1} />);
    expect(screen.getByTestId("entityQuery-applied")).toHaveTextContent("Applied · 1 entity");

    rerender(<Host appliedCount={3} />);
    expect(screen.getByTestId("entityQuery-applied")).toHaveTextContent("Applied · 3 entities");
  });

  test("queryForcedOpen opens the Query section and renders the query bar", () => {
    renderWithProviders(<Host queryForcedOpen />);
    expect(screen.getByRole("button", { name: /^Query/ })).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByText("the query bar")).toBeInTheDocument();
  });

  test("a forced-open section can still be closed by hand while the refusal stays in force, even with an unstable setter", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Host queryForcedOpen unstableSetter />);
    const toggle = screen.getByRole("button", { name: /^Query/ });
    expect(toggle).toHaveAttribute("aria-expanded", "true");

    await user.click(toggle);
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByText("the query bar")).not.toBeInTheDocument();

    // A further re-render (another user interaction) must not re-arm the forced open.
    await user.click(screen.getByRole("button", { name: "Filters" }));
    expect(toggle).toHaveAttribute("aria-expanded", "false");
  });
});
