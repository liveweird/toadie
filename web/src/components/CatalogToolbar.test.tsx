import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen, within } from "../test/render";
import CatalogToolbar from "./CatalogToolbar";
import { useCatalogFileFilterState } from "../hooks/useCatalogFileFilterState";
import { ENTITY_KINDS } from "../utils/catalogFileForm";

function Host({ onKinds }: { onKinds?: (kinds: string[]) => void }) {
  const filters = useCatalogFileFilterState("toolbarTest");
  const controls = onKinds
    ? { ...filters, controls: { ...filters.controls, setKinds: onKinds } }
    : filters;
  return (
    <CatalogToolbar viewKey="toolbarTest" filters={controls}>
      <button type="button">secondary control</button>
    </CatalogToolbar>
  );
}

describe("CatalogToolbar", () => {
  beforeEach(() => {
    localStorage.setItem("toadie.auth.token", "fake-token");
    // The lens picker and the filter controls load their option sources — answer them empty.
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json({ items: [] })));
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("one row: the Filters toggle, the lens picker, and the caption-less kind pills; children as a second row", () => {
    renderWithProviders(<Host />);
    expect(screen.getByRole("button", { name: "Filters" })).toHaveAttribute("aria-expanded", "false");
    expect(screen.getByRole("combobox", { name: "Lens" })).toBeInTheDocument();
    const kinds = screen.getByRole("group", { name: "Kind" });
    for (const kind of ENTITY_KINDS) {
      expect(within(kinds).getByRole("checkbox", { name: kind })).toBeChecked();
    }
    // No visible "Kind" caption — the group's aria-label names it.
    expect(screen.queryByText("Kind", { exact: true })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "secondary control" })).toBeInTheDocument();
  });

  test("toggling a pill reports the new visible-kinds set", async () => {
    const user = userEvent.setup();
    const seen: string[][] = [];
    renderWithProviders(<Host onKinds={(next) => seen.push(next)} />);
    await user.click(screen.getByRole("checkbox", { name: "User" }));
    expect(seen.at(-1)).toEqual(ENTITY_KINDS.filter((k) => k !== "User"));
  });
});
