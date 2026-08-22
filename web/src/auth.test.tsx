import { describe, expect, test } from "vitest";
import { Route, Routes } from "react-router-dom";
import { renderWithProviders, screen } from "./test/render";
import { RedirectIfAuthed, RequireAuth, consumeSignedOut, flagSignedOut } from "./auth";

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
