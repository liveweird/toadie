import { StrictMode } from "react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { Route, Routes, useLocation } from "react-router-dom";
import { renderWithProviders, screen, waitFor } from "../test/render";
import { jsonResponse } from "../test/http";
import SsoCallback from "./SsoCallback";

const SESSION = {
  token: "access-sso",
  expiresAt: 1,
  refreshToken: "refresh-sso",
  refreshExpiresAt: 2,
  userId: 9,
  roles: [],
  disabledFeatures: [],
  language: "en",
  hasPassword: false,
};

function LocationProbe() {
  const location = useLocation();
  return <output data-testid="location">{location.pathname + location.search + location.hash}</output>;
}

function renderCallback(hash = "#code=hand-off-1") {
  return renderWithProviders(
    <StrictMode>
      <Routes>
        <Route path="/login/sso" element={<><SsoCallback /><LocationProbe /></>} />
        <Route path="*" element={<LocationProbe />} />
      </Routes>
    </StrictMode>,
    { route: `/login/sso${hash}` },
  );
}

describe("SsoCallback", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  const fetchMock = () => vi.mocked(globalThis.fetch);

  test("shows a named status while the exchange is pending", () => {
    fetchMock().mockReturnValue(new Promise(() => undefined));
    renderCallback();
    expect(screen.getByRole("status", { name: "Loading…" })).toBeInTheDocument();
  });

  test("exchanges the code once (StrictMode included), persists the session and lands on the saved path", async () => {
    fetchMock().mockResolvedValue(jsonResponse(200, SESSION));
    sessionStorage.setItem(
      "toadie.sso.returnTo",
      JSON.stringify({ pathname: "/entities", search: "?blueprint=_team", hash: "#q" }),
    );
    renderCallback();
    await waitFor(() => expect(screen.getByTestId("location")).toHaveTextContent("/entities?blueprint=_team#q"));
    expect(fetchMock()).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock().mock.calls[0];
    expect(url).toBe("/api/v1/login/sso/exchange");
    expect(JSON.parse((init as { body: string }).body)).toEqual({ code: "hand-off-1" });
    expect(localStorage.getItem("toadie.auth.token")).toBe("access-sso");
    expect(localStorage.getItem("toadie.auth.hasPassword")).toBe("false");
    expect(sessionStorage.getItem("toadie.sso.returnTo")).toBeNull();
  });

  test("strips the code from the address before the exchange resolves", async () => {
    fetchMock().mockReturnValue(new Promise(() => undefined));
    renderCallback();
    await waitFor(() => expect(screen.getByTestId("location")).toHaveTextContent(/^\/login\/sso$/));
    expect(JSON.stringify(localStorage)).not.toContain("hand-off-1");
  });

  test("lands on / without a saved location", async () => {
    fetchMock().mockResolvedValue(jsonResponse(200, SESSION));
    renderCallback();
    await waitFor(() => expect(screen.getByTestId("location")).toHaveTextContent(/^\/$/));
  });

  test("refuses an external saved location", async () => {
    fetchMock().mockResolvedValue(jsonResponse(200, SESSION));
    sessionStorage.setItem("toadie.sso.returnTo", JSON.stringify({ pathname: "//evil.example/steal" }));
    renderCallback();
    await waitFor(() => expect(screen.getByTestId("location")).toHaveTextContent(/^\/$/));
    expect(localStorage.getItem("toadie.auth.token")).toBe("access-sso");
  });

  test("a signed-in browser still exchanges and replaces its session", async () => {
    localStorage.setItem("toadie.auth.token", "stale");
    fetchMock().mockResolvedValue(jsonResponse(200, SESSION));
    renderCallback();
    await waitFor(() => expect(localStorage.getItem("toadie.auth.token")).toBe("access-sso"));
  });

  test("401 shows the no-longer-valid alert with a way back and starts no session", async () => {
    fetchMock().mockResolvedValue(jsonResponse(401, { title: "Unauthorized", status: 401 }));
    renderCallback();
    expect(await screen.findByText(/sign-in link is no longer valid/i)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Back to sign in" })).toHaveAttribute("href", "/login");
    expect(localStorage.getItem("toadie.auth.token")).toBeNull();
    expect(fetchMock()).toHaveBeenCalledTimes(1);
  });

  test("429 says to try again later", async () => {
    fetchMock().mockResolvedValue(jsonResponse(429, { title: "Too Many Requests", status: 429 }));
    renderCallback();
    expect(await screen.findByText("Too many attempts. Try again in a minute.")).toBeInTheDocument();
  });

  test("a network failure shows the generic alert", async () => {
    fetchMock().mockRejectedValue(new Error("offline"));
    renderCallback();
    expect(await screen.findByText(/could not be completed/i)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Back to sign in" })).toBeInTheDocument();
  });

  test.each(["", "#code=", "#other=1"])("a missing code (%s) shows the alert without a request", async (hash) => {
    renderCallback(hash);
    expect(await screen.findByText(/sign-in link is no longer valid/i)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Back to sign in" })).toHaveAttribute("href", "/login");
    expect(fetchMock()).not.toHaveBeenCalled();
  });
});
