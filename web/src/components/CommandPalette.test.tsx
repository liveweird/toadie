import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import { renderWithProviders, screen, waitFor } from "../test/render";
import CommandPalette from "./CommandPalette";
import { palette } from "../utils/commandPalette";

function Probe() {
  const { pathname } = useLocation();
  return <p>at {pathname}</p>;
}

function renderPalette() {
  return renderWithProviders(
    <>
      <CommandPalette />
      <Routes>
        <Route path="*" element={<Probe />} />
      </Routes>
    </>,
  );
}

const PAGE = {
  items: [{ id: 7, name: "payments-gateway", kind: "Component", namespace: "default" }],
  page: 1,
  pageSize: 10,
  total: 1,
};

describe("CommandPalette", () => {
  beforeEach(() => {
    localStorage.setItem("toadie.auth.token", "fake-token");
  });

  afterEach(() => {
    palette.close();
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("the header trigger opens the palette listing the session's pages and the catalog actions", async () => {
    vi.stubGlobal("fetch", vi.fn());
    const user = userEvent.setup();
    renderPalette();
    await user.click(screen.getAllByRole("button", { name: "Search and jump to…" })[0]);
    expect(await screen.findByRole("button", { name: /Hierarchy/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Change password/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /New catalog file/ })).toBeInTheDocument();
    // A regular session never sees the admin pages.
    expect(screen.queryByRole("button", { name: /^Users$/ })).not.toBeInTheDocument();
  });

  test("an admin session's palette includes the Administration pages, and a pick navigates", async () => {
    localStorage.setItem("toadie.auth.roles", JSON.stringify(["ADMIN"]));
    vi.stubGlobal("fetch", vi.fn());
    const user = userEvent.setup();
    renderPalette();
    palette.open();
    await user.click(await screen.findByRole("button", { name: /Users/ }));
    expect(await screen.findByText("at /users")).toBeInTheDocument();
  });

  test("typing two characters searches catalog files by name; a result opens its editor", async () => {
    const mockFetch = vi.fn().mockResolvedValue(Response.json(PAGE));
    vi.stubGlobal("fetch", mockFetch);
    const user = userEvent.setup();
    renderPalette();
    palette.open();
    await user.type(await screen.findByPlaceholderText("Search files or jump to a page…"), "pay");
    await waitFor(() =>
      expect(mockFetch).toHaveBeenCalledWith(
        "/api/v1/files?page=1&pageSize=10&sort=name&name=pay",
        expect.anything(),
      ),
    );
    await user.click(await screen.findByRole("button", { name: /payments-gateway/ }));
    expect(await screen.findByText("at /files/7/edit")).toBeInTheDocument();
  });

  test("a single character does not search", async () => {
    const mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    const user = userEvent.setup();
    renderPalette();
    palette.open();
    await user.type(await screen.findByPlaceholderText("Search files or jump to a page…"), "p");
    await new Promise((r) => setTimeout(r, 400));
    expect(mockFetch).not.toHaveBeenCalled();
  });
});
