/* eslint-disable react-refresh/only-export-components */
// -- the auth store helpers (signIn/signOut/useAuthed) live beside the route guards on purpose; a mixed file opts out of fast-refresh, which is fine for this rarely-edited module
import { useSyncExternalStore, type ReactElement } from "react";
import { Navigate, Outlet, useLocation } from "react-router-dom";
import { getToken, TOKEN_KEY } from "./api/session";

const listeners = new Set<() => void>();

function subscribe(cb: () => void): () => void {
  const onStorage = (e: StorageEvent) => {
    if (e.key === TOKEN_KEY || e.key === null) cb();
  };
  window.addEventListener("storage", onStorage);
  listeners.add(cb);
  return () => {
    window.removeEventListener("storage", onStorage);
    listeners.delete(cb);
  };
}

export function notifyAuthChange(): void {
  listeners.forEach((cb) => cb());
}

let pendingSignedOutBanner = false;

export function flagSignedOut(): void {
  pendingSignedOutBanner = true;
}

export function consumeSignedOut(): boolean {
  const v = pendingSignedOutBanner;
  pendingSignedOutBanner = false;
  return v;
}

function useAuth(): { token: string | null; isAuthenticated: boolean } {
  const token = useSyncExternalStore(subscribe, getToken, () => null);
  return { token, isAuthenticated: token !== null };
}

type LocationStateWithFrom = { from?: { pathname?: string } } | null;

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
    const from = (location.state as LocationStateWithFrom)?.from?.pathname;
    return <Navigate to={from ?? "/"} replace />;
  }
  return children;
}
