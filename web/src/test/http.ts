// Shared test helper: a JSON Response for fetch mocks (previously copy-pasted per test file).
import { vi } from "vitest";

export function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

/** One `mockFetch` route: matched by HTTP method (default GET) and either an exact path
 *  (compared against the URL with its query string stripped) or a RegExp tested against the
 *  full URL (query string included — use this for `startsWith`-style prefix matching). */
export type MockRoute = {
  method?: string;
  match: string | RegExp;
  /** A ready `Response`, a `{status, body}` pair JSON-encoded via `jsonResponse`, or a
   *  callback for a route whose answer depends on the request (paging, filters, …). */
  respond:
    | Response
    | { status: number; body: unknown }
    | ((url: string, init?: RequestInit) => Response | PromiseLike<Response>);
};

/**
 * A `vi.fn()` stand-in for `globalThis.fetch` dispatching by method + path/pattern, replacing
 * a hand-rolled `mockFetch.mockImplementation((url) => { if (url.startsWith(...)) ... })`
 * per test file. Routes are tried in order; the first match wins. An unmatched request throws
 * (naming the method and URL) instead of silently 404ing, so a missing route fails the test
 * that triggered it rather than the next assertion. The returned value IS the `vi.fn()`, so
 * callers keep their existing `mockFetch.mock.calls` assertions (e.g. `calledWith` helpers).
 *
 * Usage: `vi.stubGlobal("fetch", mockFetch(routes))` (and `vi.unstubAllGlobals()` in `afterEach`,
 * as before) — this only builds the dispatcher, matching the existing per-file stub lifecycle.
 */
export function mockFetch(routes: MockRoute[]) {
  return vi.fn(async (url: string, init?: RequestInit): Promise<Response> => {
    const method = (init?.method ?? "GET").toUpperCase();
    const path = url.split("?")[0];
    const route = routes.find((candidate) => {
      if ((candidate.method ?? "GET").toUpperCase() !== method) return false;
      return typeof candidate.match === "string" ? candidate.match === path : candidate.match.test(url);
    });
    if (!route) throw new Error(`mockFetch: no route matched ${method} ${url}`);
    if (route.respond instanceof Response) return route.respond;
    if (typeof route.respond === "function") return route.respond(url, init);
    return jsonResponse(route.respond.status, route.respond.body);
  });
}
