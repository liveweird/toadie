import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import { renderWithProviders, screen, waitFor } from "../test/render";
import CommandPalette from "./CommandPalette";
import { palette } from "../utils/commandPalette";
import type { World } from "../utils/navigation";

function Probe() {
  const { pathname } = useLocation();
  return <p>at {pathname}</p>;
}

function renderPalette(world: World = "backstage") {
  return renderWithProviders(
    <>
      <CommandPalette world={world} />
      <Routes>
        <Route path="*" element={<Probe />} />
      </Routes>
    </>,
  );
}

const FILE_PAGE = {
  items: [{ id: 7, name: "payments-gateway", kind: "Component", namespace: "default" }],
  page: 1,
  pageSize: 10,
  total: 1,
};

const ENTITY_PAGE = {
  items: [
    {
      id: 7,
      blueprint: "service",
      identifier: "payments-gateway",
      title: "Payments Gateway",
      properties: {},
      relations: {},
      findings: [],
      createdBy: 1,
      creatorName: "Alice",
      creatorDeleted: false,
      createdAt: 0,
      updatedAt: 0,
    },
  ],
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

  describe("backstage world", () => {
    test("the header trigger opens the palette listing the session's pages and the catalog actions", async () => {
      vi.stubGlobal("fetch", vi.fn());
      const user = userEvent.setup();
      renderPalette("backstage");
      await user.click(screen.getAllByRole("button", { name: "Search and jump to…" })[0]);
      expect(await screen.findByRole("button", { name: /Hierarchy/ })).toBeInTheDocument();
      expect(screen.getByRole("button", { name: /Change password/ })).toBeInTheDocument();
      expect(screen.getByRole("button", { name: /New catalog file/ })).toBeInTheDocument();
      expect(screen.getByRole("button", { name: /Import/ })).toBeInTheDocument();
      // A regular session never sees the admin pages, and the Port world's pages are absent.
      expect(screen.queryByRole("button", { name: /^Users$/ })).not.toBeInTheDocument();
      expect(screen.queryByRole("button", { name: /^Blueprints$/ })).not.toBeInTheDocument();
    });

    test("an admin session's palette includes the Administration pages, and a pick navigates", async () => {
      localStorage.setItem("toadie.auth.roles", JSON.stringify(["ADMIN"]));
      vi.stubGlobal("fetch", vi.fn());
      const user = userEvent.setup();
      renderPalette("backstage");
      palette.open();
      await user.click(await screen.findByRole("button", { name: /Users/ }));
      expect(await screen.findByText("at /users")).toBeInTheDocument();
    });

    test("typing two characters searches catalog files by name; a result opens its editor", async () => {
      const mockFetch = vi.fn().mockResolvedValue(Response.json(FILE_PAGE));
      vi.stubGlobal("fetch", mockFetch);
      const user = userEvent.setup();
      renderPalette("backstage");
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
      renderPalette("backstage");
      palette.open();
      await user.type(await screen.findByPlaceholderText("Search files or jump to a page…"), "p");
      await new Promise((r) => setTimeout(r, 400));
      expect(mockFetch).not.toHaveBeenCalled();
    });
  });

  describe("port world", () => {
    test("the palette lists the Port pages and the ontology actions, never the Backstage files page", async () => {
      vi.stubGlobal("fetch", vi.fn());
      const user = userEvent.setup();
      renderPalette("port");
      await user.click(screen.getAllByRole("button", { name: "Search and jump to…" })[0]);
      expect(await screen.findByRole("button", { name: /^Blueprints$/ })).toBeInTheDocument();
      expect(screen.getByRole("button", { name: /Entity hierarchy/ })).toBeInTheDocument();
      expect(screen.getByRole("button", { name: /New entity/ })).toBeInTheDocument();
      expect(screen.getByRole("button", { name: /Import ontology/ })).toBeInTheDocument();
      expect(screen.queryByRole("button", { name: /^Files$/ })).not.toBeInTheDocument();
    });

    test("an admin session sees the Users page in the Port world too", async () => {
      localStorage.setItem("toadie.auth.roles", JSON.stringify(["ADMIN"]));
      vi.stubGlobal("fetch", vi.fn());
      const user = userEvent.setup();
      renderPalette("port");
      await user.click(screen.getAllByRole("button", { name: "Search and jump to…" })[0]);
      expect(await screen.findByRole("button", { name: /Users/ })).toBeInTheDocument();
    });

    test("New entity navigates to the entities list", async () => {
      vi.stubGlobal("fetch", vi.fn());
      const user = userEvent.setup();
      renderPalette("port");
      palette.open();
      await user.click(await screen.findByRole("button", { name: /New entity/ }));
      expect(await screen.findByText("at /entities")).toBeInTheDocument();
    });

    test("Import ontology navigates to the ontology import page", async () => {
      vi.stubGlobal("fetch", vi.fn());
      const user = userEvent.setup();
      renderPalette("port");
      palette.open();
      await user.click(await screen.findByRole("button", { name: /Import ontology/ }));
      expect(await screen.findByText("at /ontology/import")).toBeInTheDocument();
    });

    test("typing two characters searches entities by identifier/title; a result opens its editor", async () => {
      const mockFetch = vi.fn().mockResolvedValue(Response.json(ENTITY_PAGE));
      vi.stubGlobal("fetch", mockFetch);
      const user = userEvent.setup();
      renderPalette("port");
      palette.open();
      await user.type(await screen.findByPlaceholderText("Search entities or jump to a page…"), "pay");
      await waitFor(() =>
        expect(mockFetch).toHaveBeenCalledWith(
          "/api/v1/entities?q=pay&page=1&pageSize=10&sort=identifier",
          expect.anything(),
        ),
      );
      const option = await screen.findByRole("button", { name: /payments-gateway/ });
      expect(option).toHaveTextContent("service · Payments Gateway");
      await user.click(option);
      expect(await screen.findByText("at /entities/7/edit")).toBeInTheDocument();
    });

    test("a single character does not search entities", async () => {
      const mockFetch = vi.fn();
      vi.stubGlobal("fetch", mockFetch);
      const user = userEvent.setup();
      renderPalette("port");
      palette.open();
      await user.type(await screen.findByPlaceholderText("Search entities or jump to a page…"), "p");
      await new Promise((r) => setTimeout(r, 400));
      expect(mockFetch).not.toHaveBeenCalled();
    });
  });

  test("the trigger and search placeholder switch text per world", async () => {
    vi.stubGlobal("fetch", vi.fn());
    const { rerender } = renderWithProviders(<CommandPalette world="backstage" />);
    expect(screen.getByText("Search files or jump to a page…")).toBeInTheDocument();
    rerender(<CommandPalette world="port" />);
    expect(screen.getByText("Search entities or jump to a page…")).toBeInTheDocument();
  });
});
