/* eslint-disable react-refresh/only-export-components */
// -- the auth store helpers (signIn/signOut/useAuthed) live beside the route guards on purpose; a mixed file opts out of fast-refresh, which is fine for this rarely-edited module
import { useSyncExternalStore, type ReactElement } from "react";
import { Navigate, Outlet, useLocation } from "react-router-dom";
import type { QueryClient } from "@tanstack/react-query";
import {
  getSessionFamilyId,
  getSessionUserId,
  getToken,
  TOKEN_KEY,
  USER_ID_KEY,
} from "./api/session";

const listeners = new Set<() => void>();
const lifecycleListeners = new Set<() => void>();

function subscribeAuthChanges(cb: () => void): () => void {
  const onStorage = (e: StorageEvent) => {
    if (e.key === TOKEN_KEY || e.key === USER_ID_KEY || e.key === null) cb();
  };
  window.addEventListener("storage", onStorage);
  listeners.add(cb);
  return () => {
    window.removeEventListener("storage", onStorage);
    listeners.delete(cb);
  };
}

export function notifyAuthChange(): void {
  [...listeners].forEach((cb) => cb());
  // A lifecycle callback may dispose a store and subscribe its replacement. Iterate a
  // snapshot so that replacement belongs to the new session rather than this notification.
  [...lifecycleListeners].forEach((cb) => cb());
}

/** Current-SPA login/logout boundary; unlike the render subscription, token refresh storage
 *  events do not fire it. Consumers use this to discard state owned by the former session. */
export function subscribeAuthLifecycle(cb: () => void): () => void {
  let sessionFamilyId = getSessionFamilyId();
  const onStorage = (event: StorageEvent) => {
    if (event.key !== TOKEN_KEY && event.key !== USER_ID_KEY && event.key !== null) return;
    const next = getSessionFamilyId();
    // Refresh rotates both JWTs but retains `sid`; logout and another login do not.
    if (next === sessionFamilyId) return;
    sessionFamilyId = next;
    cb();
  };
  window.addEventListener("storage", onStorage);
  lifecycleListeners.add(cb);
  return () => {
    window.removeEventListener("storage", onStorage);
    lifecycleListeners.delete(cb);
  };
}

/**
 * Clears the whole query cache at every auth boundary — the lifecycle fires from the
 * transport's definitive refresh rejection (`api/http.ts`), `Login.tsx#finishSignIn`, and
 * cross-tab `storage` sid changes, so cached data from a former session can never leak into
 * the next one via a query that hasn't refetched yet. Call once, right after the QueryClient
 * is constructed (`main.tsx`); the explicit sign-out's own `queryClient.clear()`
 * (`UserMenu.tsx`) stays in place too — a harmless double.
 */
export function bindQueryCacheToAuthBoundary(client: QueryClient): () => void {
  return subscribeAuthLifecycle(() => client.clear());
}

let pendingSignedOutBanner = false;

/** Survives a full-page navigation (the identity provider's end-session round trip); the tab, not the browser. */
const SIGNED_OUT_KEY = "toadie.signedOut";

export function flagSignedOut(): void {
  pendingSignedOutBanner = true;
}

/** This document's own mark: a stored flag carrying it was written by the page that is about to leave. */
const THIS_PAGE = Array.from(crypto.getRandomValues(new Uint32Array(2)), (n) => n.toString(36)).join("-");

/** Carries the flag across a top-level navigation away from the SPA; blocked storage just loses the banner. */
export function persistSignedOutFlag(): void {
  try {
    sessionStorage.setItem(SIGNED_OUT_KEY, THIS_PAGE);
  } catch {
    // Storage blocked: the sign-out still happened, only the banner is lost.
  }
}

/**
 * One-shot read of the signed-out banner flag. The in-memory flag is this page's own; the stored
 * one belongs to the NEXT document. The SPA's redirect to /login renders (possibly more than once)
 * before the navigation to the provider unloads the page, so a stored flag this same page wrote is
 * never consumed here — only the page the provider returns to reads and removes it.
 */
export function consumeSignedOut(): boolean {
  if (pendingSignedOutBanner) {
    pendingSignedOutBanner = false;
    return true;
  }
  try {
    const stored = sessionStorage.getItem(SIGNED_OUT_KEY);
    if (stored === null || stored === THIS_PAGE) return false;
    sessionStorage.removeItem(SIGNED_OUT_KEY);
    return true;
  } catch {
    return false;
  }
}

function useAuth(): { token: string | null; isAuthenticated: boolean } {
  const token = useSyncExternalStore(subscribeAuthChanges, getToken, () => null);
  return { token, isAuthenticated: token !== null };
}

/** Reactive session owner for state whose cache is partitioned per account. */
export function useSessionUserId(): number | null {
  return useSyncExternalStore(subscribeAuthChanges, getSessionUserId, () => null);
}

type LocationStateWithFrom = {
  from?: { pathname?: unknown; search?: unknown; hash?: unknown };
} | null;

/** Restore only a router-created, root-relative app location. */
export function internalReturnLocation(state: unknown): string {
  const from = (state as LocationStateWithFrom)?.from;
  const pathname = from?.pathname;
  if (typeof pathname !== "string" || !pathname.startsWith("/") || pathname.startsWith("//") || pathname.includes("\\")) {
    return "/";
  }
  const search = typeof from?.search === "string" && (from.search === "" || from.search.startsWith("?"))
    ? from.search
    : "";
  const hash = typeof from?.hash === "string" && (from.hash === "" || from.hash.startsWith("#"))
    ? from.hash
    : "";
  return `${pathname}${search}${hash}`;
}

export function RequireAuth(): ReactElement {
  const { isAuthenticated } = useAuth();
  const location = useLocation();
  if (!isAuthenticated) {
    return <Navigate to="/login" state={{ from: location }} replace />;
  }
  return <Outlet />;
}

export function RedirectIfAuthed({ children }: { children: ReactElement }): ReactElement {
  const { isAuthenticated } = useAuth();
  const location = useLocation();
  if (isAuthenticated) {
    return <Navigate to={internalReturnLocation(location.state)} replace />;
  }
  return children;
}
