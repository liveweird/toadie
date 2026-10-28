// Auth flows — login and logout (transport in ./http, session state in ./session).

import { API_BASE, ApiError, ownsCurrentSession, safeJson, sessionBoundary, timeoutSignal } from "./http";
import type { components, paths } from "./schema";
import { internalReturnLocation } from "../auth";
import { clearSession, getRefreshToken, getToken, persistSession } from "./session";

type LoginBody = paths["/api/v1/login"]["post"]["requestBody"]["content"]["application/json"];
// Tokens, or (MFA-enabled accounts) a second-factor challenge — discriminate via isMfaChallenge.
type LoginOk = paths["/api/v1/login"]["post"]["responses"]["200"]["content"]["application/json"];
type LoginSuccess = components["schemas"]["LoginResponse"];
type LogoutResponse = components["schemas"]["LogoutResponse"];
export type MfaChallenge = components["schemas"]["MfaChallengeResponse"];

export function isMfaChallenge(data: LoginOk): data is MfaChallenge {
  return "mfaRequired" in data;
}

export async function login(credentials: LoginBody): Promise<LoginOk> {
  const res = await fetch(`${API_BASE}/api/v1/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(credentials),
    signal: timeoutSignal(),
  });
  if (!res.ok) throw new ApiError(res.status, await safeJson(res));
  const data = (await res.json()) as LoginOk;
  // An MFA challenge carries no tokens — the session starts only after verifyMfa.
  if (!isMfaChallenge(data)) persistSession(data);
  return data;
}

type MfaVerifyBody =
  paths["/api/v1/login/mfa"]["post"]["requestBody"]["content"]["application/json"];

/**
 * Second login step for MFA-enabled accounts: exchanges the challenge id + emailed 6-digit
 * code for the ordinary token pair (persisted like a login). Throws ApiError on 401
 * (invalid/expired code) or 429 (rate-limited).
 */
export async function verifyMfa(challengeId: string, code: string): Promise<LoginSuccess> {
  const body: MfaVerifyBody = { challengeId, code };
  const res = await fetch(`${API_BASE}/api/v1/login/mfa`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
    signal: timeoutSignal(),
  });
  if (!res.ok) throw new ApiError(res.status, await safeJson(res));
  const data = (await res.json()) as LoginSuccess;
  persistSession(data);
  return data;
}

type PasswordResetBody =
  paths["/api/v1/password-reset"]["post"]["requestBody"]["content"]["application/json"];

/**
 * Self-service password reset. Always 202 for a well-formed request (no account enumeration);
 * throws ApiError on 429 (one request per minute per address) or 503 (email/reset origin unavailable).
 */
export async function requestPasswordReset(email: string): Promise<void> {
  const body: PasswordResetBody = { email };
  const res = await fetch(`${API_BASE}/api/v1/password-reset`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
    signal: timeoutSignal(),
  });
  if (!res.ok) throw new ApiError(res.status, await safeJson(res));
}

/** Public single-purpose credential exchange; never refresh/retry it through authedFetch. */
export async function confirmPasswordReset(token: string, password: string): Promise<void> {
  const body: components["schemas"]["PasswordResetConfirmRequest"] = { token, password };
  const res = await fetch(`${API_BASE}/api/v1/password-reset/confirm`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
    signal: timeoutSignal(),
  });
  if (!res.ok) throw new ApiError(res.status, await safeJson(res));
}

/** Only an http(s) URL may be navigated to; anything else (javascript:, data:, junk) reads as absent. */
function httpUrlOrNull(value: unknown): string | null {
  if (typeof value !== "string") return null;
  try {
    const { protocol } = new URL(value);
    return protocol === "https:" || protocol === "http:" ? value : null;
  } catch {
    return null;
  }
}

/**
 * Signs out: revokes server-side (best-effort) and clears the local session. Resolves the identity
 * provider's end-session URL when the server answered `200 {endSessionUrl}` for an SSO session
 * (http/https only — the caller navigates there, and must neither store nor log it), else null.
 * Also null when another login replaced this one while the revoke was in flight: a stale sign-out
 * must not carry the browser away from the newer session.
 */
export async function logout(): Promise<string | null> {
  const token = getToken();
  if (!token) return null;
  // Captured BEFORE the await: if the session is signed out and ANOTHER login happens while
  // the revoke is in flight, this call's late completion must not wipe the new session.
  const owner = sessionBoundary();
  let endSessionUrl: string | null = null;
  try {
    const res = await fetch(`${API_BASE}/api/v1/logout`, {
      method: "POST",
      headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
      // Include the refresh token so an explicit logout revokes it too, not just the access token.
      body: JSON.stringify({ refreshToken: getRefreshToken() }),
      signal: timeoutSignal(),
    });
    if (res.status === 200) {
      endSessionUrl = httpUrlOrNull(((await res.json()) as Partial<LogoutResponse>).endSessionUrl);
    }
  } catch {
    // Best-effort revoke: offline/timeout must not block the LOCAL sign-out — a rejected
    // fetch skipping clearSession would leave the user apparently signed in.
  }
  if (!ownsCurrentSession(owner)) return null;
  clearSession();
  return endSessionUrl;
}

export type LoginOptions = components["schemas"]["LoginOptionsResponse"];

/** Public read of what the sign-in page may offer besides the password form (the SSO button); never sends a JWT. */
export async function loginOptions(): Promise<LoginOptions> {
  const res = await fetch(`${API_BASE}/api/v1/login/options`, { signal: timeoutSignal() });
  if (!res.ok) throw new ApiError(res.status, await safeJson(res));
  return (await res.json()) as LoginOptions;
}

/**
 * Second step of an SSO sign-in: exchanges the one-time hand-off code from the `/login/sso#code=`
 * fragment for the ordinary token pair (persisted like a login). The request rides the attempt
 * cookie the start navigation set. Throws ApiError on 401 (unknown/used/expired code or missing
 * cookie) or 429; never refresh/retry it through authedFetch.
 */
export async function exchangeSsoCode(code: string): Promise<LoginSuccess> {
  const body: components["schemas"]["SsoExchangeRequest"] = { code };
  const res = await fetch(`${API_BASE}/api/v1/login/sso/exchange`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
    signal: timeoutSignal(),
  });
  if (!res.ok) throw new ApiError(res.status, await safeJson(res));
  const data = (await res.json()) as LoginSuccess;
  persistSession(data);
  return data;
}

const SSO_RETURN_KEY = "toadie.sso.returnTo";

/**
 * Begins an SSO sign-in: remembers where to land afterwards (sessionStorage — it must survive the
 * round trip through the identity provider, but not the tab) and leaves the SPA through a
 * top-level navigation the server answers with a 302 to the provider. Without a `from` any
 * stale remembered location is dropped, so an abandoned attempt never redirects a later one.
 */
export function startSsoLogin(from?: { pathname?: unknown; search?: unknown; hash?: unknown } | null): void {
  try {
    if (from && typeof from === "object") {
      sessionStorage.setItem(SSO_RETURN_KEY, JSON.stringify({
        pathname: from.pathname,
        search: from.search,
        hash: from.hash,
      }));
    } else {
      sessionStorage.removeItem(SSO_RETURN_KEY);
    }
  } catch {
    // Storage blocked: the sign-in still works, it just lands on the home page.
  }
  window.location.assign(`${API_BASE}/api/v1/login/sso/start`);
}

/** Reads and removes the remembered location; only a root-relative app path survives (`internalReturnLocation`), default `/`. */
export function takeSsoReturnLocation(): string {
  let from: unknown = null;
  try {
    const raw = sessionStorage.getItem(SSO_RETURN_KEY);
    sessionStorage.removeItem(SSO_RETURN_KEY);
    if (raw !== null) from = JSON.parse(raw);
  } catch {
    // Unreadable or blocked storage reads as "nothing remembered".
  }
  return internalReturnLocation({ from });
}
