// Auth flows — login and logout (transport in ./http, session state in ./session).

import { API_BASE, ApiError, safeJson, timeoutSignal } from "./http";
import type { paths } from "./schema";
import { clearSession, getRefreshToken, getToken, persistSession } from "./session";

type LoginBody = paths["/api/v1/login"]["post"]["requestBody"]["content"]["application/json"];
type LoginSuccess = paths["/api/v1/login"]["post"]["responses"]["200"]["content"]["application/json"];

export async function login(credentials: LoginBody): Promise<LoginSuccess> {
  const res = await fetch(`${API_BASE}/api/v1/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(credentials),
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
 * throws ApiError on 429 (one request per minute per address) or 503 (deployment without email).
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

export async function logout(): Promise<void> {
  const token = getToken();
  if (!token) return;
  try {
    await fetch(`${API_BASE}/api/v1/logout`, {
      method: "POST",
      headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
      // Include the refresh token so an explicit logout revokes it too, not just the access token.
      body: JSON.stringify({ refreshToken: getRefreshToken() }),
      signal: timeoutSignal(),
    });
  } catch {
    // Best-effort revoke: offline/timeout must not block the LOCAL sign-out — a rejected
    // fetch skipping clearSession would leave the user apparently signed in.
  }
  clearSession();
}
