// Session state — token/roles storage and the render-time accessors
// (transport lives in ./http).

import type { components } from "./schema";

type LoginSuccess = components["schemas"]["LoginResponse"];

export const TOKEN_KEY = "toadie.auth.token";
const REFRESH_TOKEN_KEY = "toadie.auth.refreshToken";
const ROLES_KEY = "toadie.auth.roles";
const USER_ID_KEY = "toadie.auth.userId";
const DISABLED_FEATURES_KEY = "toadie.auth.disabledFeatures";

/** Additional roles — every user is implicitly a regular user; an empty set means no extra privileges. */
const USER_ROLES = ["ADMIN"] as const;
export type UserRole = (typeof USER_ROLES)[number];

/** Per-user gateable features — the DISABLED set travels the wire; empty = full access. */
export const FEATURES = ["MFA"] as const;
export type Feature = (typeof FEATURES)[number];

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string | null): void {
  if (token === null) localStorage.removeItem(TOKEN_KEY);
  else localStorage.setItem(TOKEN_KEY, token);
}

export function getRefreshToken(): string | null {
  return localStorage.getItem(REFRESH_TOKEN_KEY);
}

function setRefreshToken(token: string | null): void {
  if (token === null) localStorage.removeItem(REFRESH_TOKEN_KEY);
  else localStorage.setItem(REFRESH_TOKEN_KEY, token);
}

export function getRoles(): UserRole[] {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(ROLES_KEY) ?? "[]");
    return Array.isArray(parsed) ? parsed.filter((r): r is UserRole => USER_ROLES.includes(r)) : [];
  } catch {
    return [];
  }
}

export function getUserId(): number | null {
  const raw = localStorage.getItem(USER_ID_KEY);
  if (raw === null) return null;
  const parsed = Number(raw);
  return Number.isFinite(parsed) ? parsed : null;
}

export function getDisabledFeatures(): Feature[] {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(DISABLED_FEATURES_KEY) ?? "[]");
    return Array.isArray(parsed) ? parsed.filter((f): f is Feature => FEATURES.includes(f)) : [];
  } catch {
    return [];
  }
}

export function hasFeature(feature: Feature): boolean {
  return !getDisabledFeatures().includes(feature);
}

export function isAdmin(): boolean {
  return getRoles().includes("ADMIN");
}

export function clearSession(): void {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(REFRESH_TOKEN_KEY);
  localStorage.removeItem(ROLES_KEY);
  localStorage.removeItem(USER_ID_KEY);
  localStorage.removeItem(DISABLED_FEATURES_KEY);
}

/**
 * Persist the access + refresh pair (and the current roles/userId/feature flags) returned by
 * /login or /refresh. `?? []` keeps a mid-deploy older server (no disabledFeatures yet) harmless.
 */
export function persistSession(data: LoginSuccess): void {
  setToken(data.token);
  setRefreshToken(data.refreshToken);
  localStorage.setItem(ROLES_KEY, JSON.stringify(data.roles));
  localStorage.setItem(USER_ID_KEY, String(data.userId));
  localStorage.setItem(DISABLED_FEATURES_KEY, JSON.stringify(data.disabledFeatures ?? []));
}
