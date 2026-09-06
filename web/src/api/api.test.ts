import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { jsonResponse } from "../test/http";
import {
  ApiError,
  authedFetch,
  buildQuery,
  isTimeoutError,
  jsonRequest,
  shouldRetryQuery,
  safeJson,
  timeoutSignal,
  voidRequest,
} from "./http";
import { login, logout, isMfaChallenge, verifyMfa } from "./auth";
import {
  TOKEN_KEY,
  clearSession,
  getRefreshToken,
  getRoles,
  getToken,
  getUserId,
  isAdmin,
  persistSession,
  setToken,
} from "./session";
import { getDisabledFeatures, hasFeature } from "./session";
import i18n from "../i18n";

const SESSION = {
  token: "access-1",
  expiresAt: 1,
  refreshToken: "refresh-1",
  refreshExpiresAt: 2,
  userId: 7,
  roles: ["ADMIN" as const],
  disabledFeatures: [],
  language: "en" as const,
};

const NEXT_SESSION = {
  ...SESSION,
  token: "next-access",
  refreshToken: "next-refresh",
  userId: 8,
};

function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

function sessionWithSid(sid: string, generation: number) {
  const payload = btoa(JSON.stringify({ sid })).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
  return {
    ...SESSION,
    token: `header.${payload}.signature-${generation}`,
    refreshToken: `refresh-${sid}-${generation}`,
  };
}

describe("session", () => {
  test("persistSession applies the stored language to the UI and skips a language-less payload", async () => {
    persistSession({ ...SESSION, language: "pl" });
    await vi.waitFor(() => expect(i18n.resolvedLanguage).toBe("pl"));
    // A mid-deploy older server sends no language — the UI stays untouched.
    persistSession({ ...SESSION, language: undefined as unknown as "en" });
    expect(i18n.resolvedLanguage).toBe("pl");
    await i18n.changeLanguage("en");
  });

  test("persistSession stores the pair, roles, and userId; clearSession removes them", () => {
    persistSession(SESSION);
    expect(getToken()).toBe("access-1");
    expect(getRefreshToken()).toBe("refresh-1");
    expect(getRoles()).toEqual(["ADMIN"]);
    expect(getUserId()).toBe(7);
    expect(isAdmin()).toBe(true);

    clearSession();
    expect(getToken()).toBeNull();
    expect(getRefreshToken()).toBeNull();
    expect(getRoles()).toEqual([]);
    expect(getUserId()).toBeNull();
    expect(isAdmin()).toBe(false);
  });

  test("corrupt stored roles and userId degrade to safe defaults", () => {
    localStorage.setItem("toadie.auth.roles", "{not json");
    localStorage.setItem("toadie.auth.userId", "NaN-ish");
    expect(getRoles()).toEqual([]);
    expect(getUserId()).toBeNull();
  });

  test("disabled features persist with the session and drive hasFeature", () => {
    persistSession({ ...SESSION, disabledFeatures: ["MFA"] });
    expect(getDisabledFeatures()).toEqual(["MFA"]);
    expect(hasFeature("MFA")).toBe(false);

    persistSession(SESSION); // empty set = full access
    expect(hasFeature("MFA")).toBe(true);

    // Corrupt storage degrades to the safe default (everything enabled).
    localStorage.setItem("toadie.auth.disabledFeatures", "{not json");
    expect(getDisabledFeatures()).toEqual([]);
    expect(hasFeature("MFA")).toBe(true);
  });

  test("unknown role names are filtered out", () => {
    localStorage.setItem("toadie.auth.roles", JSON.stringify(["ADMIN", "SUPERUSER"]));
    expect(getRoles()).toEqual(["ADMIN"]);
  });

  test("setToken(null) removes the stored token", () => {
    setToken("abc");
    expect(getToken()).toBe("abc");
    setToken(null);
    expect(getToken()).toBeNull();
  });
});

describe("ApiError and helpers", () => {
  test("detail and instance surface RFC 7807 fields when present", () => {
    const err = new ApiError(409, { title: "Conflict", status: 409, detail: "Duplicate", instance: "/x" });
    expect(err.status).toBe(409);
    expect(err.detail).toBe("Duplicate");
    expect(err.instance).toBe("/x");
    expect(new ApiError(500, null).detail).toBeUndefined();
  });

  test("shouldRetryQuery never retries a 4xx and caps transient retries at two", () => {
    expect(shouldRetryQuery(0, new ApiError(403, null))).toBe(false);
    expect(shouldRetryQuery(0, new ApiError(500, null))).toBe(true);
    expect(shouldRetryQuery(1, new Error("network"))).toBe(true);
    expect(shouldRetryQuery(2, new Error("network"))).toBe(false);
  });

  test("safeJson returns null for a non-JSON body", async () => {
    expect(await safeJson(new Response("<html>", { status: 502 }))).toBeNull();
    expect(await safeJson(jsonResponse(400, { a: 1 }))).toEqual({ a: 1 });
  });

  test("buildQuery skips absent values but keeps false and 0", () => {
    expect(buildQuery({ a: "x", b: null, c: undefined, d: "", e: false, f: 0 })).toBe("a=x&e=false&f=0");
  });

  test("isTimeoutError matches only the transport deadline rejection", () => {
    expect(isTimeoutError(new DOMException("t", "TimeoutError"))).toBe(true);
    expect(isTimeoutError(new Error("t"))).toBe(false);
  });

  test("timeoutSignal is an AbortSignal where supported", () => {
    const signal = timeoutSignal();
    if (typeof AbortSignal.timeout === "function") expect(signal).toBeInstanceOf(AbortSignal);
    else expect(signal).toBeUndefined();
  });
});

describe("authedFetch", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  const fetchMock = () => fetch as unknown as ReturnType<typeof vi.fn>;

  test("sends the bearer and returns the response", async () => {
    persistSession(SESSION);
    fetchMock().mockResolvedValueOnce(jsonResponse(200, { ok: true }));

    const res = await authedFetch("/api/v1/thing");

    expect(res.status).toBe(200);
    const [url, init] = fetchMock().mock.calls[0];
    expect(url).toBe("/api/v1/thing");
    expect(new Headers(init.headers).get("Authorization")).toBe("Bearer access-1");
  });

  test("a 401 triggers one silent refresh and a retry with the new token", async () => {
    persistSession(SESSION);
    fetchMock()
      .mockResolvedValueOnce(jsonResponse(401, {}))
      .mockResolvedValueOnce(jsonResponse(200, { ...SESSION, token: "access-2", refreshToken: "refresh-2" }))
      .mockResolvedValueOnce(jsonResponse(200, { ok: true }));

    const res = await authedFetch("/api/v1/thing");

    expect(res.status).toBe(200);
    expect(getToken()).toBe("access-2");
    const retryInit = fetchMock().mock.calls[2][1];
    expect(new Headers(retryInit.headers).get("Authorization")).toBe("Bearer access-2");
  });

  test("a rejected refresh clears the session", async () => {
    persistSession(SESSION);
    fetchMock()
      .mockResolvedValueOnce(jsonResponse(401, {}))
      .mockResolvedValueOnce(jsonResponse(401, {}));

    const res = await authedFetch("/api/v1/thing");

    expect(res.status).toBe(401);
    expect(getToken()).toBeNull();
    expect(getRefreshToken()).toBeNull();
  });

  test("a transient refresh failure keeps the session", async () => {
    persistSession(SESSION);
    fetchMock()
      .mockResolvedValueOnce(jsonResponse(401, {}))
      .mockResolvedValueOnce(jsonResponse(503, {}));

    const res = await authedFetch("/api/v1/thing");

    expect(res.status).toBe(401);
    expect(getToken()).toBe("access-1");
    expect(getRefreshToken()).toBe("refresh-1");
  });

  test("concurrent 401s share one refresh and both replay in the same session", async () => {
    persistSession(SESSION);
    const refreshResponse = deferred<Response>();
    fetchMock()
      .mockResolvedValueOnce(jsonResponse(401, {}))
      .mockResolvedValueOnce(jsonResponse(401, {}))
      .mockReturnValueOnce(refreshResponse.promise)
      .mockResolvedValueOnce(jsonResponse(200, { request: "a" }))
      .mockResolvedValueOnce(jsonResponse(200, { request: "b" }));

    const first = authedFetch("/api/v1/a");
    const second = authedFetch("/api/v1/b");
    await vi.waitFor(() => expect(fetchMock()).toHaveBeenCalledTimes(3));
    refreshResponse.resolve(
      jsonResponse(200, { ...SESSION, token: "access-2", refreshToken: "refresh-2" }),
    );

    await expect(Promise.all([first, second])).resolves.toMatchObject([{ status: 200 }, { status: 200 }]);
    expect(fetchMock().mock.calls.filter(([url]) => url === "/api/v1/refresh")).toHaveLength(1);
  });

  test("a late 401 in the same family refreshes with the latest rotated pair", async () => {
    const firstPair = sessionWithSid("family-a", 1);
    const secondPair = sessionWithSid("family-a", 2);
    const thirdPair = sessionWithSid("family-a", 3);
    persistSession(firstPair);
    const lateResponse = deferred<Response>();
    fetchMock()
      .mockResolvedValueOnce(jsonResponse(401, {}))
      .mockReturnValueOnce(lateResponse.promise)
      .mockResolvedValueOnce(jsonResponse(200, secondPair))
      .mockResolvedValueOnce(jsonResponse(200, { request: "first" }))
      .mockResolvedValueOnce(jsonResponse(200, thirdPair))
      .mockResolvedValueOnce(jsonResponse(200, { request: "late" }));

    const first = authedFetch("/api/v1/first");
    const late = authedFetch("/api/v1/late");
    await expect(first).resolves.toMatchObject({ status: 200 });
    lateResponse.resolve(jsonResponse(401, {}));
    await expect(late).resolves.toMatchObject({ status: 200 });

    const refreshCalls = fetchMock().mock.calls.filter(([url]) => url === "/api/v1/refresh");
    expect(refreshCalls).toHaveLength(2);
    expect(JSON.parse((refreshCalls[1][1] as RequestInit).body as string)).toEqual({
      refreshToken: secondPair.refreshToken,
    });
    expect(getToken()).toBe(thirdPair.token);
  });

  test("a new login family neither shares nor loses its refresh slot to the old family", async () => {
    const oldPair = sessionWithSid("family-old", 1);
    const oldRotated = sessionWithSid("family-old", 2);
    const newPair = sessionWithSid("family-new", 1);
    const newRotated = sessionWithSid("family-new", 2);
    persistSession(oldPair);
    const oldRefresh = deferred<Response>();
    const newRefresh = deferred<Response>();
    fetchMock()
      .mockResolvedValueOnce(jsonResponse(401, {}))
      .mockReturnValueOnce(oldRefresh.promise)
      .mockResolvedValueOnce(jsonResponse(200, newPair))
      .mockResolvedValueOnce(jsonResponse(401, {}))
      .mockReturnValueOnce(newRefresh.promise)
      .mockResolvedValueOnce(jsonResponse(401, {}))
      .mockResolvedValueOnce(jsonResponse(200, { request: "new-a" }))
      .mockResolvedValueOnce(jsonResponse(200, { request: "new-b" }));

    const oldRequest = authedFetch("/api/v1/old");
    await vi.waitFor(() => expect(fetchMock()).toHaveBeenCalledTimes(2));
    await login({ email: "same-user@test", password: "pw" });
    const newFirst = authedFetch("/api/v1/new-a");
    await vi.waitFor(() => expect(fetchMock()).toHaveBeenCalledTimes(5));

    oldRefresh.resolve(jsonResponse(200, oldRotated));
    await expect(oldRequest).resolves.toMatchObject({ status: 401 });
    const newSecond = authedFetch("/api/v1/new-b");
    await vi.waitFor(() => expect(fetchMock()).toHaveBeenCalledTimes(6));
    newRefresh.resolve(jsonResponse(200, newRotated));

    await expect(Promise.all([newFirst, newSecond])).resolves.toMatchObject([
      { status: 200 },
      { status: 200 },
    ]);
    expect(fetchMock().mock.calls.filter(([url]) => url === "/api/v1/refresh")).toHaveLength(2);
    expect(getToken()).toBe(newRotated.token);
  });

  test("logout cannot be undone by an older successful refresh", async () => {
    persistSession(SESSION);
    const refreshResponse = deferred<Response>();
    fetchMock()
      .mockResolvedValueOnce(jsonResponse(401, {}))
      .mockReturnValueOnce(refreshResponse.promise);

    const request = authedFetch("/api/v1/graph-layout", { method: "PUT", body: "{}" });
    await vi.waitFor(() => expect(fetchMock()).toHaveBeenCalledTimes(2));
    fetchMock().mockRejectedValueOnce(new Error("offline"));
    await logout();
    refreshResponse.resolve(
      jsonResponse(200, { ...SESSION, token: "access-2", refreshToken: "refresh-2" }),
    );

    await expect(request).resolves.toMatchObject({ status: 401 });
    expect(getToken()).toBeNull();
    expect(getRefreshToken()).toBeNull();
    expect(fetchMock()).toHaveBeenCalledTimes(3);
  });

  test("an old 401 cannot refresh after a new login", async () => {
    persistSession(SESSION);
    const oldResponse = deferred<Response>();
    fetchMock()
      .mockReturnValueOnce(oldResponse.promise)
      .mockResolvedValueOnce(jsonResponse(200, NEXT_SESSION));

    const request = authedFetch("/api/v1/thing");
    await vi.waitFor(() => expect(fetchMock()).toHaveBeenCalledTimes(1));
    await login({ email: "next@test", password: "pw" });
    oldResponse.resolve(jsonResponse(401, {}));

    await expect(request).resolves.toMatchObject({ status: 401 });
    expect(getToken()).toBe("next-access");
    expect(getRefreshToken()).toBe("next-refresh");
    expect(fetchMock()).toHaveBeenCalledTimes(2);
  });

  test("an old rejected refresh cannot clear a new login", async () => {
    persistSession(SESSION);
    const refreshResponse = deferred<Response>();
    fetchMock()
      .mockResolvedValueOnce(jsonResponse(401, {}))
      .mockReturnValueOnce(refreshResponse.promise)
      .mockResolvedValueOnce(jsonResponse(200, NEXT_SESSION));

    const request = authedFetch("/api/v1/thing");
    await vi.waitFor(() => expect(fetchMock()).toHaveBeenCalledTimes(2));
    await login({ email: "next@test", password: "pw" });
    refreshResponse.resolve(jsonResponse(401, {}));

    await expect(request).resolves.toMatchObject({ status: 401 });
    expect(getToken()).toBe("next-access");
    expect(getRefreshToken()).toBe("next-refresh");
    expect(fetchMock()).toHaveBeenCalledTimes(3);
  });

  test("an old parsed refresh response cannot replace or replay a new login", async () => {
    persistSession(SESSION);
    const refreshBody = deferred<typeof SESSION>();
    const heldRefresh = jsonResponse(200, {});
    const json = vi.spyOn(heldRefresh, "json").mockImplementation(() => refreshBody.promise);
    fetchMock()
      .mockResolvedValueOnce(jsonResponse(401, {}))
      .mockResolvedValueOnce(heldRefresh)
      .mockResolvedValueOnce(jsonResponse(200, NEXT_SESSION));

    const request = authedFetch("/api/v1/thing");
    await vi.waitFor(() => expect(json).toHaveBeenCalledOnce());
    await login({ email: "next@test", password: "pw" });
    refreshBody.resolve({ ...SESSION, token: "old-access-2", refreshToken: "old-refresh-2" });

    await expect(request).resolves.toMatchObject({ status: 401 });
    expect(getToken()).toBe("next-access");
    expect(getRefreshToken()).toBe("next-refresh");
    expect(fetchMock()).toHaveBeenCalledTimes(3);
  });

  test("jsonRequest parses the body and voidRequest throws ApiError on failure", async () => {
    persistSession(SESSION);
    fetchMock()
      .mockResolvedValueOnce(jsonResponse(200, { hello: "world" }))
      .mockResolvedValueOnce(jsonResponse(404, { title: "Not Found", status: 404, detail: "gone" }));

    await expect(jsonRequest<{ hello: string }>("/api/v1/thing")).resolves.toEqual({ hello: "world" });
    await expect(voidRequest("/api/v1/thing", { method: "PUT", body: "{}" })).rejects.toMatchObject({
      status: 404,
      detail: "gone",
    });
  });
});

describe("auth flows", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  const fetchMock = () => fetch as unknown as ReturnType<typeof vi.fn>;

  test("login persists the session on 200", async () => {
    fetchMock().mockResolvedValueOnce(jsonResponse(200, SESSION));
    const data = await login({ email: "a@test", password: "pw" });
    expect(isMfaChallenge(data)).toBe(false);
    if (!isMfaChallenge(data)) expect(data.token).toBe("access-1");
    expect(getToken()).toBe("access-1");
    expect(isAdmin()).toBe(true);
  });

  test("an MFA challenge starts no session; verifyMfa persists the pair", async () => {
    fetchMock().mockResolvedValueOnce(
      jsonResponse(200, { mfaRequired: true, challengeId: "ch-1", expiresAt: 99 }),
    );
    const data = await login({ email: "a@test", password: "pw" });
    expect(isMfaChallenge(data)).toBe(true);
    expect(getToken()).toBeNull();

    fetchMock().mockResolvedValueOnce(jsonResponse(200, SESSION));
    const tokens = await verifyMfa("ch-1", "123456");
    expect(tokens.token).toBe("access-1");
    expect(getToken()).toBe("access-1");
    const [url, init] = fetchMock().mock.calls.at(-1)!;
    expect(url).toBe("/api/v1/login/mfa");
    expect(JSON.parse((init as { body: string }).body)).toEqual({ challengeId: "ch-1", code: "123456" });
  });

  test("login throws ApiError with the response body on 401", async () => {
    fetchMock().mockResolvedValueOnce(jsonResponse(401, { title: "Unauthorized", status: 401 }));
    await expect(login({ email: "a@test", password: "bad" })).rejects.toBeInstanceOf(ApiError);
    expect(getToken()).toBeNull();
  });

  test("logout revokes server-side and clears the session even when the revoke fails", async () => {
    persistSession(SESSION);
    fetchMock().mockRejectedValueOnce(new Error("offline"));
    await logout();
    expect(getToken()).toBeNull();
    expect(getRefreshToken()).toBeNull();
  });

  test("logout without a stored token is a no-op fetch-wise", async () => {
    localStorage.setItem(TOKEN_KEY, "");
    clearSession();
    await logout();
    expect(fetchMock()).not.toHaveBeenCalled();
  });
});
