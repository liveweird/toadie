// Session state — token/roles storage and the render-time accessors
// (transport lives in ./http).

import i18n, { asSupportedLanguage } from "../i18n";
import type { components } from "./schema";

type LoginSuccess = components["schemas"]["LoginResponse"];

export const TOKEN_KEY = "toadie.auth.token";
const REFRESH_TOKEN_KEY = "toadie.auth.refreshToken";
const ROLES_KEY = "toadie.auth.roles";
export const USER_ID_KEY = "toadie.auth.userId";
const DISABLED_FEATURES_KEY = "toadie.auth.disabledFeatures";

// Same-tab logout boundary. Refresh deliberately does not advance it: rotated credentials
// still belong to the same login family, while clearSession invalidates all pending work.
let clearSessionGeneration = 0;

/** Additional roles — every user is implicitly a regular user; an empty set means no extra privileges. */
const USER_ROLES = ["ADMIN"] as const;
export type UserRole = (typeof USER_ROLES)[number];

/** Per-user gateable features — the DISABLED set travels the wire; empty = full access. */
export const FEATURES = ["MFA"] as const;
export type Feature = (typeof FEATURES)[number];

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

function decodedSessionClaims(): Record<string, unknown> | null {
  const payload = getToken()?.split(".")[1];
  if (!payload) return null;
  try {
    const base64 = payload.replaceAll("-", "+").replaceAll("_", "/");
    const decoded: unknown = JSON.parse(atob(base64.padEnd(Math.ceil(base64.length / 4) * 4, "=")));
    return decoded !== null && typeof decoded === "object" ? decoded as Record<string, unknown> : null;
  } catch {
    return null;
  }
}

/** Stable login-family identity carried by both access-token generations. This is decoded
 *  only to partition client state; authentication still belongs entirely to the server. */
export function getSessionFamilyId(): string | null {
  const sid = decodedSessionClaims()?.sid;
  return typeof sid === "string" && sid.length > 0 ? sid : null;
}

export function setToken(token: string | null): void {
  if (token === null) localStorage.removeItem(TOKEN_KEY);
  else localStorage.setItem(TOKEN_KEY, token);
}

export function getRefreshToken(): string | null {
  return localStorage.getItem(REFRESH_TOKEN_KEY);
}

export function getClearSessionGeneration(): number {
  return clearSessionGeneration;
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

/** Atomic graph-cache owner: real access tokens bind userId to sid in one storage value.
 *  Mock/legacy tokens without both safe claims retain the established stored-user fallback. */
export function getSessionUserId(): number | null {
  const claims = decodedSessionClaims();
  const sid = claims?.sid;
  const userId = claims?.userId;
  return typeof sid === "string" && sid.length > 0 &&
    typeof userId === "number" && Number.isSafeInteger(userId) && userId >= 0
    ? userId
    : getUserId();
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
  clearSessionGeneration += 1;
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
  // Apply the user's stored language (V18) — one chokepoint covers login, the MFA step, and
  // the silent refresh (so an admin change propagates within the refresh window). The
  // inequality guard avoids re-firing languageChanged app-wide on every refresh; the
  // data.language truthiness guard keeps a mid-deploy older server harmless (the
  // disabledFeatures ?? [] precedent). changeLanguage caches to toadie.lang, so the stored
  // language also becomes the device language.
  const lang = asSupportedLanguage(data.language);
  if (data.language && lang !== asSupportedLanguage(i18n.resolvedLanguage)) {
    void i18n.changeLanguage(lang);
  }
}
