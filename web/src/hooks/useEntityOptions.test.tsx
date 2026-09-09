import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import type { ReactNode } from "react";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useEntityOptions } from "./useEntityOptions";
import { jsonResponse } from "../test/http";

type FetchMock = ReturnType<typeof vi.fn>;

function entityItem(id: number) {
  return { id, blueprint: "service", identifier: `svc-${id}`, title: `Service ${id}`, properties: {}, relations: {}, findings: [] };
}

function createWrapper() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return ({ children }: { children: ReactNode }) => <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}

describe("useEntityOptions", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("toadie.auth.token", "fake-token");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("pools every page of the list endpoint, scoped to the blueprint, until the total is reached", async () => {
    const total = 150;
    const pageOne = Array.from({ length: 100 }, (_, i) => entityItem(i + 1));
    const pageTwo = Array.from({ length: 50 }, (_, i) => entityItem(101 + i));
    mockFetch.mockImplementation((url: string) => {
      const page = new URL(url, "http://test").searchParams.get("page");
      return Promise.resolve(jsonResponse(200, { items: page === "1" ? pageOne : pageTwo, page: Number(page), pageSize: 100, total }));
    });

    const { result } = renderHook(() => useEntityOptions("service"), { wrapper: createWrapper() });
    expect(result.current.loading).toBe(true);
    expect(result.current.options).toEqual([]);

    await waitFor(() => expect(result.current.options).toHaveLength(total));
    const urls = mockFetch.mock.calls.map(([url]) => url as string);
    expect(urls).toEqual([
      "/api/v1/entities?blueprint=service&page=1&pageSize=100&sort=identifier",
      "/api/v1/entities?blueprint=service&page=2&pageSize=100&sort=identifier",
    ]);
  });

  test("a blank blueprint never fetches", () => {
    const { result } = renderHook(() => useEntityOptions(""), { wrapper: createWrapper() });
    expect(result.current.loading).toBe(false);
    expect(result.current.options).toEqual([]);
    expect(mockFetch).not.toHaveBeenCalled();
  });

  test("a single short page ends the pool loop immediately", async () => {
    mockFetch.mockResolvedValue(jsonResponse(200, { items: [entityItem(1)], page: 1, pageSize: 100, total: 1 }));
    const { result } = renderHook(() => useEntityOptions("service"), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.options).toHaveLength(1));
    expect(mockFetch).toHaveBeenCalledTimes(1);
  });
});
