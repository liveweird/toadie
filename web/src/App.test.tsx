import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { MantineProvider } from "@mantine/core";
import { MemoryRouter } from "react-router-dom";
import { render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import App from "./App";
import { APP_VERSION } from "./changelog/version";

const TOKEN_KEY = "toadie.auth.token";

function renderApp(route: string) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <MantineProvider env="test">
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={[route]}>
          <App />
        </MemoryRouter>
      </QueryClientProvider>
    </MantineProvider>,
  );
}

describe("App shell", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  describe("when authenticated", () => {
    beforeEach(() => {
      localStorage.setItem(TOKEN_KEY, "fake-token");
    });

    test("renders the brand and the hierarchy page at /", async () => {
      renderApp("/");
      expect(await screen.findByRole("heading", { level: 2, name: "Hierarchy" })).toBeInTheDocument();
      expect(screen.getByText("Toadie")).toBeInTheDocument();
    });

    test("the navbar shows the version stamp", async () => {
      renderApp("/");
      expect(await screen.findByText(new RegExp(`v${APP_VERSION.replace(/\./g, "\\.")}`))).toBeInTheDocument();
    });

    test("the nav groups render open with their child links addressable", async () => {
      renderApp("/");
      // Group parents are toggle buttons, not links…
      expect(await screen.findByRole("button", { name: "Dictionaries" })).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Metadata" })).toBeInTheDocument();
      // …and start OPEN, so every child link is in the DOM immediately.
      for (const [name, href] of [
        ["Namespaces", "/namespaces"],
        ["Types", "/types"],
        ["Lifecycles", "/lifecycles"],
        ["Labels", "/labels"],
        ["Tags", "/tags"],
        ["Annotations", "/annotations"],
      ] as const) {
        expect(screen.getByRole("link", { name })).toHaveAttribute("href", href);
      }
      // The Graph leaf follows the renamed route.
      expect(screen.getByRole("link", { name: "Graph" })).toHaveAttribute("href", "/graph");
    });

    test("shows a Changelog nav link and a linked version stamp with the what's-new dot", async () => {
      renderApp("/");
      expect(await screen.findByRole("link", { name: "Changelog" })).toHaveAttribute(
        "href",
        "/changelog",
      );
      expect(screen.getByTitle("Build version")).toHaveAttribute("href", "/changelog");
      expect(screen.getByTitle("What's new")).toBeInTheDocument();
    });

    test("opening the changelog clears the what's-new dot immediately", async () => {
      const user = userEvent.setup();
      renderApp("/");
      await user.click(await screen.findByRole("link", { name: "Changelog" }));
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
      renderApp("/");
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
      expect(screen.getByRole("link", { name: "Back to home" })).toHaveAttribute("href", "/");
    });

    test("logout clears the session and lands on the login page", async () => {
      (fetch as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(
        new Response(null, { status: 204 }),
      );
      const user = userEvent.setup();
      renderApp("/");
      await user.click(await screen.findByRole("button", { name: "Logout" }));
      await waitFor(() => expect(localStorage.getItem(TOKEN_KEY)).toBeNull());
      expect(await screen.findByRole("heading", { name: "Sign in" })).toBeInTheDocument();
      // The signed-out banner rides the flagSignedOut() handoff.
      expect(screen.getByText("You've been signed out.")).toBeInTheDocument();
    });
  });

  describe("when not authenticated", () => {
    test("a protected route redirects to the login page", async () => {
      renderApp("/");
      expect(await screen.findByRole("heading", { name: "Sign in" })).toBeInTheDocument();
    });

    test("the login page renders directly at /login", async () => {
      renderApp("/login");
      expect(await screen.findByRole("heading", { name: "Sign in" })).toBeInTheDocument();
      expect(screen.getByText("Toadie")).toBeInTheDocument();
    });
  });
});
