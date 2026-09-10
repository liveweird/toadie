import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import type { ReactNode } from "react";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { jsonResponse } from "../test/http";
import { useEntities } from "./useEntities";

type FetchMock = ReturnType<typeof vi.fn>;

function createWrapper() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
}

describe("useEntities", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("toadie.auth.token", "fake-token");
    mockFetch.mockResolvedValue(jsonResponse(200, { items: [], page: 1, pageSize: 20, total: 0 }));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("without a blueprint the query stays disabled", () => {
    renderHook(() => useEntities({ page: 1, pageSize: 20 }), { wrapper: createWrapper() });
    expect(mockFetch).not.toHaveBeenCalled();
  });

  test("passes team through to the list request", async () => {
    renderHook(() => useEntities({ blueprint: "service", team: "platform", page: 1, pageSize: 20 }), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(mockFetch).toHaveBeenCalled());
    const [url] = mockFetch.mock.calls[0];
    expect(url).toBe("/api/v1/entities?blueprint=service&team=platform&page=1&pageSize=20");
  });
});
