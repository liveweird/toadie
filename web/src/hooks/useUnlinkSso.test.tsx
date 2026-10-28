import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import type { ReactNode } from "react";
import { act, renderHook } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useUnlinkSso } from "./useUnlinkSso";
import type { UserResponse } from "../api/users";
import { jsonResponse } from "../test/http";
import { showSuccessToast } from "../utils/toast";

vi.mock("../utils/toast", () => ({ showSuccessToast: vi.fn() }));

type FetchMock = ReturnType<typeof vi.fn>;

const TARGET: UserResponse = { id: 2, name: "Bob Basic", email: "bob@example.com", roles: [], disabledFeatures: [], language: "en" as const, sso: true, hasPassword: false };

describe("useUnlinkSso", () => {
  let mockFetch: FetchMock;
  let queryClient: QueryClient;
  let invalidate: ReturnType<typeof vi.spyOn>;

  function wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
  }

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("toadie.auth.token", "fake-token");
    queryClient = new QueryClient({ defaultOptions: { mutations: { retry: false } } });
    invalidate = vi.spyOn(queryClient, "invalidateQueries");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
    localStorage.clear();
  });

  test("request arms the confirm state; clearTarget disarms it", () => {
    const { result } = renderHook(() => useUnlinkSso(), { wrapper });
    expect(result.current.target).toBeNull();

    act(() => result.current.request(TARGET));
    expect(result.current.target).toEqual(TARGET);
    expect(result.current.error).toBeNull();

    act(() => result.current.clearTarget());
    expect(result.current.target).toBeNull();
  });

  test("confirm DELETEs the sso link, toasts, refreshes the users list and closes", async () => {
    mockFetch.mockResolvedValue(new Response(null, { status: 204 }));
    const { result } = renderHook(() => useUnlinkSso(), { wrapper });

    act(() => result.current.request(TARGET));
    await act(() => result.current.confirm());

    const [url, init] = mockFetch.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/v1/users/2/sso");
    expect(init.method).toBe("DELETE");
    expect(showSuccessToast).toHaveBeenCalledWith("SSO identity unlinked");
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["users"] });
    expect(result.current.target).toBeNull();
    expect(result.current.pending).toBe(false);
    expect(result.current.error).toBeNull();
  });

  test("a 404 reads as already unlinked, refreshes the list and keeps the confirm open", async () => {
    mockFetch.mockResolvedValue(jsonResponse(404, { title: "Not Found", status: 404 }));
    const { result } = renderHook(() => useUnlinkSso(), { wrapper });

    act(() => result.current.request(TARGET));
    await act(() => result.current.confirm());

    expect(result.current.error).toBe(
      "This account has no linked SSO identity any more (or no longer exists).",
    );
    expect(result.current.target).toEqual(TARGET);
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["users"] });
    expect(showSuccessToast).not.toHaveBeenCalled();
  });

  test("a 409 asks for a password first, keeps the confirm open and leaves the list alone", async () => {
    mockFetch.mockResolvedValue(
      jsonResponse(409, { type: "urn:toadie:password-not-set", title: "Conflict", status: 409 }),
    );
    const { result } = renderHook(() => useUnlinkSso(), { wrapper });

    act(() => result.current.request(TARGET));
    await act(() => result.current.confirm());

    expect(result.current.error).toBe(
      "Set a password for this account first: an administrator or MFA-enabled account never re-links through single sign-on.",
    );
    expect(result.current.target).toEqual(TARGET);
    expect(invalidate).not.toHaveBeenCalled();
    expect(showSuccessToast).not.toHaveBeenCalled();
  });

  test("another failure surfaces the status-tagged message without refreshing", async () => {
    mockFetch.mockResolvedValue(jsonResponse(500, { title: "boom", status: 500 }));
    const { result } = renderHook(() => useUnlinkSso(), { wrapper });

    act(() => result.current.request(TARGET));
    await act(() => result.current.confirm());

    expect(result.current.error).toMatch(/500/);
    expect(result.current.target).toEqual(TARGET);
    expect(invalidate).not.toHaveBeenCalled();
    expect(result.current.pending).toBe(false);
  });

  test("a network failure surfaces the generic message", async () => {
    mockFetch.mockRejectedValue(new Error("offline"));
    const { result } = renderHook(() => useUnlinkSso(), { wrapper });

    act(() => result.current.request(TARGET));
    await act(() => result.current.confirm());

    expect(result.current.error).not.toBeNull();
    expect(result.current.target).toEqual(TARGET);
  });

  test("confirm without a target is a no-op", async () => {
    const { result } = renderHook(() => useUnlinkSso(), { wrapper });
    await act(() => result.current.confirm());
    expect(mockFetch).not.toHaveBeenCalled();
  });
});
