import { describe, expect, test } from "vitest";
import { Route, Routes } from "react-router-dom";
import { QueryClient } from "@tanstack/react-query";
import { renderWithProviders, screen } from "./test/render";
import {
  RedirectIfAuthed,
  RequireAuth,
  bindQueryCacheToAuthBoundary,
  consumeSignedOut,
  flagSignedOut,
  notifyAuthChange,
} from "./auth";

const TOKEN_KEY = "toadie.auth.token";

function TestRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<RedirectIfAuthed><div>login page</div></RedirectIfAuthed>} />
      <Route element={<RequireAuth />}>
        <Route path="/secret" element={<div>secret page</div>} />
      </Route>
    </Routes>
  );
}

describe("route guards", () => {
  test("RequireAuth redirects an anonymous visitor to /login", () => {
    renderWithProviders(<TestRoutes />, { route: "/secret" });
    expect(screen.getByText("login page")).toBeInTheDocument();
  });

  test("RequireAuth renders the outlet for an authenticated visitor", () => {
    localStorage.setItem(TOKEN_KEY, "token");
    renderWithProviders(<TestRoutes />, { route: "/secret" });
    expect(screen.getByText("secret page")).toBeInTheDocument();
  });

  test("RedirectIfAuthed bounces an authenticated visitor off /login", () => {
    localStorage.setItem(TOKEN_KEY, "token");
    renderWithProviders(
      <Routes>
        <Route path="/" element={<div>home page</div>} />
        <Route path="/login" element={<RedirectIfAuthed><div>login page</div></RedirectIfAuthed>} />
      </Routes>,
      { route: "/login" },
    );
    expect(screen.getByText("home page")).toBeInTheDocument();
  });

  test("the signed-out flag is one-shot", () => {
    flagSignedOut();
    expect(consumeSignedOut()).toBe(true);
    expect(consumeSignedOut()).toBe(false);
  });
});

describe("bindQueryCacheToAuthBoundary", () => {
  test("clears the query cache when the auth lifecycle fires, and stops once unsubscribed", () => {
    const client = new QueryClient();
    client.setQueryData(["thing"], { hello: "world" });
    expect(client.getQueryData(["thing"])).toEqual({ hello: "world" });

    const unsubscribe = bindQueryCacheToAuthBoundary(client);
    notifyAuthChange();
    expect(client.getQueryData(["thing"])).toBeUndefined();

    unsubscribe();
    client.setQueryData(["thing"], { hello: "again" });
    notifyAuthChange();
    expect(client.getQueryData(["thing"])).toEqual({ hello: "again" });
  });
});
