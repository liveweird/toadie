import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { MantineProvider } from "@mantine/core";
import { MemoryRouter } from "react-router-dom";
import { render, screen, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import App from "./App";
import { APP_VERSION } from "./changelog/version";
import { mockFetch, type MockRoute } from "./test/http";
import { currentFixture, historyFixture } from "./test/telemetryFixtures";

// The telemetry panel's lazy chart chunk draws with recharts, which has no layout under happy-dom.
vi.mock("@mantine/charts", () => ({ LineChart: () => <div data-testid="line-chart" /> }));

const TOKEN_KEY = "toadie.auth.token";
const WORLD_KEY = "toadie.viewSettings.appShell.world";

function renderApp(route: string) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <MantineProvider env="test" theme={{ respectReducedMotion: true }}>
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={[route]}>
          <App />
        </MemoryRouter>
      </QueryClientProvider>
    </MantineProvider>,
  );
}

/** Every fetch the two worlds' home/registry pages might issue, all answering an empty
 *  workspace — the tests below only care about shell chrome (sections, the switch, headings). */
function stubWorldFetches(extra: MockRoute[] = []) {
  return mockFetch([
    ...extra,
    { match: "/api/v1/dictionaries/namespaces", respond: { status: 200, body: { items: [] } } },
    { match: "/api/v1/dictionaries/hierarchies", respond: { status: 200, body: { items: [] } } },
    { match: "/api/v1/files/graph", respond: { status: 200, body: { nodes: [], edges: [] } } },
    { match: "/api/v1/blueprints", respond: { status: 200, body: { items: [] } } },
    { match: "/api/v1/entities/graph", respond: { status: 200, body: { nodes: [], edges: [] } } },
  ]);
}

describe("App shell", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  describe("when authenticated", () => {
    beforeEach(() => {
      localStorage.setItem(TOKEN_KEY, "fake-token");
      vi.stubGlobal("fetch", stubWorldFetches());
    });

    test("renders the brand and the hierarchy page at /hierarchy", async () => {
      renderApp("/hierarchy");
      expect(await screen.findByRole("heading", { level: 2, name: "Hierarchy" })).toBeInTheDocument();
      expect(screen.getByText("Toadie")).toBeInTheDocument();
    });

    test("the navbar shows the version stamp", async () => {
      renderApp("/hierarchy");
      expect(await screen.findByText(new RegExp(`v${APP_VERSION.replace(/\./g, "\\.")}`))).toBeInTheDocument();
    });

    test("the sidebar renders its Backstage sections with every leaf link addressable", async () => {
      renderApp("/hierarchy");
      // Sections are labelled groups, never toggles — every leaf is in the DOM immediately.
      const registries = await screen.findByRole("group", { name: "Dictionaries" });
      for (const [name, href] of [
        ["Namespaces", "/namespaces"],
        ["Types", "/types"],
        ["Lifecycles", "/lifecycles"],
        ["Labels", "/labels"],
        ["Tags", "/tags"],
        ["Annotations", "/annotations"],
      ] as const) {
        expect(within(registries).getByRole("link", { name })).toHaveAttribute("href", href);
      }
      expect(screen.queryByRole("button", { name: "Dictionaries" })).not.toBeInTheDocument();
      expect(screen.getByRole("link", { name: "Graph" })).toHaveAttribute("href", "/graph");
      // Account items live in the header menu, not the sidebar.
      expect(screen.queryByRole("link", { name: "Change password" })).not.toBeInTheDocument();
      // A non-admin session sees no Administration section at all.
      expect(screen.queryByRole("group", { name: "Administration" })).not.toBeInTheDocument();
    });

    test("an admin session sees the Administration section", async () => {
      localStorage.setItem("toadie.auth.roles", JSON.stringify(["ADMIN"]));
      renderApp("/hierarchy");
      const admin = await screen.findByRole("group", { name: "Administration" });
      expect(within(admin).getByRole("link", { name: "Users" })).toHaveAttribute("href", "/users");
      expect(within(admin).getByRole("link", { name: "Feature flags" })).toHaveAttribute("href", "/feature-flags");
      expect(within(admin).getByRole("link", { name: "Integration clients" })).toHaveAttribute(
        "href",
        "/integration-clients",
      );
    });

    describe("the telemetry panel", () => {
      const PANEL_KEY = "toadie.viewSettings.telemetry.panelOpen";

      function stubTelemetry(current: unknown = currentFixture()) {
        const fetchMock = stubWorldFetches([
          { match: "/api/v1/telemetry/current", respond: { status: 200, body: current } },
          { match: "/api/v1/telemetry/history", respond: { status: 200, body: historyFixture("memory", "24h", []) } },
        ]);
        vi.stubGlobal("fetch", fetchMock);
        return fetchMock;
      }

      test("an admin opens it from the account menu (it lands in an aside), and closes it with its own button, which hands focus to the page", async () => {
        localStorage.setItem("toadie.auth.roles", JSON.stringify(["ADMIN"]));
        stubTelemetry();
        const user = userEvent.setup();
        renderApp("/hierarchy");
        await user.click(await screen.findByRole("button", { name: /Account menu$/ }));
        const item = await screen.findByRole("menuitem", { name: "Telemetry panel" });
        expect(item).not.toHaveAttribute("aria-current");
        await user.click(item);
        const aside = await screen.findByRole("complementary", { name: "Telemetry" });
        expect(await within(aside).findByRole("button", { name: "Memory" })).toHaveAttribute("aria-expanded", "true");
        expect(localStorage.getItem(PANEL_KEY)).toBe("true");
        await user.click(within(aside).getByRole("button", { name: "Close" }));
        await waitFor(() => expect(screen.queryByRole("complementary", { name: "Telemetry" })).not.toBeInTheDocument());
        expect(localStorage.getItem(PANEL_KEY)).toBe("false");
        expect(document.getElementById("main-content")).toHaveFocus();
      });

      test("a stored open flag never shows it to a regular user, and nothing is requested", async () => {
        localStorage.setItem(PANEL_KEY, "true");
        const fetchMock = stubTelemetry();
        renderApp("/hierarchy");
        await screen.findByRole("heading", { level: 2, name: "Hierarchy" });
        expect(screen.queryByRole("complementary", { name: "Telemetry" })).not.toBeInTheDocument();
        expect(fetchMock.mock.calls.some(([url]) => String(url).includes("/telemetry/"))).toBe(false);
        // A regular user's account menu has no item for it either.
        await userEvent.setup().click(screen.getByRole("button", { name: /Account menu$/ }));
        await screen.findByRole("menuitem", { name: /Changelog/ });
        expect(screen.queryByRole("menuitem", { name: "Telemetry panel" })).not.toBeInTheDocument();
      });

      test("an admin with the flag stored finds it open after a reload, in the Port world too", async () => {
        localStorage.setItem("toadie.auth.roles", JSON.stringify(["ADMIN"]));
        localStorage.setItem(PANEL_KEY, "true");
        stubTelemetry();
        renderApp("/blueprints");
        expect(await screen.findByRole("radio", { name: "Port" })).toBeChecked();
        expect(await screen.findByRole("complementary", { name: "Telemetry" })).toBeInTheDocument();
      });

      test("a render crash inside the panel keeps the page and offers Close panel, which clears the stored flag", async () => {
        localStorage.setItem("toadie.auth.roles", JSON.stringify(["ADMIN"]));
        localStorage.setItem(PANEL_KEY, "true");
        // A malformed answer (no processes) crashes the panel's render.
        stubTelemetry({ generatedAt: 1 });
        const consoleError = vi.spyOn(console, "error").mockImplementation(() => {});
        const user = userEvent.setup();
        try {
          renderApp("/hierarchy");
          const aside = await screen.findByRole("complementary", { name: "Telemetry" });
          expect(await within(aside).findByText("The telemetry panel stopped working")).toBeInTheDocument();
          // The shell around it survives.
          expect(screen.getByRole("heading", { level: 2, name: "Hierarchy" })).toBeInTheDocument();
          await user.click(within(aside).getByRole("button", { name: "Close panel" }));
          await waitFor(() => expect(screen.queryByRole("complementary", { name: "Telemetry" })).not.toBeInTheDocument());
          expect(localStorage.getItem(PANEL_KEY)).toBe("false");
        } finally {
          consoleError.mockRestore();
        }
      });
    });

    test("the account menu holds the Changelog link; the stamp links there and the trigger carries the dot", async () => {
      const user = userEvent.setup();
      renderApp("/hierarchy");
      expect(await screen.findByTitle("Build version")).toHaveAttribute("href", "/changelog");
      // The what's-new dot rides the account-menu trigger while the version is unseen.
      expect(screen.getByTitle("What's new")).toBeInTheDocument();
      await user.click(screen.getByRole("button", { name: /Account menu$/ }));
      expect(await screen.findByRole("menuitem", { name: /Changelog/ })).toHaveAttribute("href", "/changelog");
    });

    test("opening the changelog clears the what's-new dot immediately", async () => {
      const user = userEvent.setup();
      renderApp("/hierarchy");
      await user.click(await screen.findByRole("button", { name: /Account menu$/ }));
      await user.click(await screen.findByRole("menuitem", { name: /Changelog/ }));
      // Explicit timeout — the Changelog chunk is lazy.
      expect(
        await screen.findByRole("heading", { level: 2, name: "Changelog" }, { timeout: 5000 }),
      ).toBeInTheDocument();
      await waitFor(() => expect(screen.queryByTitle("What's new")).not.toBeInTheDocument());
      expect(JSON.parse(localStorage.getItem("toadie.changelog")!)).toEqual({
        seenVersion: APP_VERSION,
      });
    });

    test("shows no dot when the current version was already seen", async () => {
      localStorage.setItem("toadie.changelog", JSON.stringify({ seenVersion: APP_VERSION }));
      renderApp("/hierarchy");
      await screen.findByRole("heading", { level: 2, name: "Hierarchy" });
      expect(screen.queryByTitle("What's new")).not.toBeInTheDocument();
    });

    test("an unmatched URL renders the not-found page inside the shell", async () => {
      renderApp("/definitely/not-a-page");
      expect(
        await screen.findByRole("heading", { level: 2, name: "Page not found" }),
      ).toBeInTheDocument();
      // The Shell mounted around it — an unmatched URL must never render a blank document.
      expect(screen.getByText("Toadie")).toBeInTheDocument();
      // The catch-all's "back home" link always points at "/" — the world-aware redirect.
      expect(screen.getByRole("link", { name: "Back to home" })).toHaveAttribute("href", "/");
    });

    test("logout clears the session and lands on the login page", async () => {
      const user = userEvent.setup();
      renderApp("/hierarchy");
      await user.click(await screen.findByRole("button", { name: /Account menu$/ }));
      await user.click(await screen.findByRole("menuitem", { name: "Sign out" }));
      await waitFor(() => expect(localStorage.getItem(TOKEN_KEY)).toBeNull());
      expect(await screen.findByRole("heading", { name: "Sign in" })).toBeInTheDocument();
      // The signed-out banner rides the flagSignedOut() handoff.
      expect(screen.getByText("You've been signed out.")).toBeInTheDocument();
    });

    describe("the world switch", () => {
      test("/ with empty storage lands on the Port home", async () => {
        renderApp("/");
        expect(
          await screen.findByRole("heading", { level: 2, name: "Entity hierarchy" }),
        ).toBeInTheDocument();
      });

      test("/ with a stored Backstage world lands on the Backstage home, radio checked", async () => {
        localStorage.setItem(WORLD_KEY, JSON.stringify("backstage"));
        renderApp("/");
        expect(await screen.findByRole("heading", { level: 2, name: "Hierarchy" })).toBeInTheDocument();
        expect(screen.getByRole("radio", { name: "Backstage" })).toBeChecked();
      });

      test("a deep link into Port overrides a stored Backstage world and rewrites storage", async () => {
        localStorage.setItem(WORLD_KEY, JSON.stringify("backstage"));
        renderApp("/blueprints");
        expect(await screen.findByRole("radio", { name: "Port" })).toBeChecked();
        expect(screen.getByRole("group", { name: "Ontology" })).toBeInTheDocument();
        expect(screen.queryByRole("group", { name: "Catalog" })).not.toBeInTheDocument();
        expect(localStorage.getItem(WORLD_KEY)).toBe(JSON.stringify("port"));
      });

      test("clicking Port from the Backstage home navigates to the Port home", async () => {
        const user = userEvent.setup();
        renderApp("/hierarchy");
        await screen.findByRole("heading", { level: 2, name: "Hierarchy" });
        await user.click(screen.getByRole("radio", { name: "Port" }));
        expect(
          await screen.findByRole("heading", { level: 2, name: "Entity hierarchy" }),
        ).toBeInTheDocument();
      });

      test("a global page keeps the stored world untouched", async () => {
        localStorage.setItem(WORLD_KEY, JSON.stringify("backstage"));
        renderApp("/changelog");
        expect(await screen.findByRole("radio", { name: "Backstage" })).toBeChecked();
        expect(localStorage.getItem(WORLD_KEY)).toBe(JSON.stringify("backstage"));
      });

      test("the brand link opens the current world's home", async () => {
        renderApp("/hierarchy");
        await screen.findByRole("heading", { level: 2, name: "Hierarchy" });
        expect(screen.getByRole("link", { name: "Toadie" })).toHaveAttribute("href", "/hierarchy");
      });
    });
  });

  describe("when not authenticated", () => {
    test("a protected route redirects to the login page", async () => {
      vi.stubGlobal("fetch", vi.fn());
      renderApp("/");
      expect(await screen.findByRole("heading", { name: "Sign in" })).toBeInTheDocument();
    });

    test("the login page renders directly at /login", async () => {
      vi.stubGlobal("fetch", vi.fn());
      renderApp("/login");
      expect(await screen.findByRole("heading", { name: "Sign in" })).toBeInTheDocument();
      expect(screen.getByText("Toadie")).toBeInTheDocument();
    });
  });
});
