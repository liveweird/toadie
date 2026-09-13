import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { useState } from "react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen } from "../test/render";
import EntityGraphToolbar from "./EntityGraphToolbar";
import { useEntityGraphFilterState } from "../hooks/useEntityGraphFilterState";

function Host({
  queryForcedOpen = false,
  appliedCount,
}: {
  queryForcedOpen?: boolean;
  appliedCount?: number;
}) {
  const filters = useEntityGraphFilterState("toolbarTest", ["team", "service"]);
  const [queryOpen, setQueryOpen] = useState(false);
  return (
    <EntityGraphToolbar
      title="Entity graph"
      viewKey="toolbarTest"
      filters={filters}
      queryOpen={queryOpen}
      onQueryOpenChange={setQueryOpen}
      queryForcedOpen={queryForcedOpen}
      appliedCount={appliedCount}
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

  test("renders the title, both collapsed toggles, the captioned Blueprints group, and children", () => {
    renderWithProviders(<Host />);
    expect(screen.getByRole("heading", { name: "Entity graph" })).toBeInTheDocument();

    const filtersToggle = screen.getByRole("button", { name: "Filters" });
    expect(filtersToggle).toHaveAttribute("aria-expanded", "false");
    const queryToggle = screen.getByRole("button", { name: "Query" });
    expect(queryToggle).toHaveAttribute("aria-expanded", "false");

    const group = screen.getByRole("group", { name: "Blueprints" });
    expect(screen.getByText("Blueprints")).toBeInTheDocument();
    expect(group).toBeInTheDocument();

    expect(screen.getByRole("button", { name: "secondary control" })).toBeInTheDocument();
    expect(screen.queryByText("the query bar")).not.toBeInTheDocument();
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
});
