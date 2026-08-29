import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import type { ReactNode } from "react";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useCatalogIdentities } from "./useCatalogIdentities";
import { jsonResponse } from "../test/http";

type FetchMock = ReturnType<typeof vi.fn>;

function listItem(id: number) {
  return {
    id,
    kind: "Component",
    name: `service-${id}`,
    namespace: "default",
    title: null,
    type: null,
    lifecycle: null,
    owner: null,
    creatorName: "Alice",
    creatorDeleted: false,
    updatedAt: 0,
  };
}

function createWrapper() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
}

describe("useCatalogIdentities", () => {
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

  test("pools every page of the list endpoint until the server total is reached", async () => {
    const total = 150;
    const pageOne = Array.from({ length: 100 }, (_, i) => listItem(i + 1));
    const pageTwo = Array.from({ length: 50 }, (_, i) => listItem(101 + i));
    mockFetch.mockImplementation((url: string) => {
      const page = new URL(url, "http://test").searchParams.get("page");
      return Promise.resolve(
        jsonResponse(200, {
          items: page === "1" ? pageOne : pageTwo,
          page: Number(page),
          pageSize: 100,
          total,
        }),
      );
    });

    const { result } = renderHook(() => useCatalogIdentities(), { wrapper: createWrapper() });
    expect(result.current).toBeUndefined(); // loading — consumers degrade to free text

    await waitFor(() => expect(result.current).toHaveLength(total));
    expect(result.current![0].name).toBe("service-1");
    expect(result.current![149].name).toBe("service-150");

    const urls = mockFetch.mock.calls.map(([url]) => url as string);
    expect(urls).toEqual([
      "/api/v1/files?page=1&pageSize=100&sort=name",
      "/api/v1/files?page=2&pageSize=100&sort=name",
    ]);
  });

  test("a single short page ends the pool loop immediately", async () => {
    mockFetch.mockResolvedValue(
      jsonResponse(200, { items: [listItem(1)], page: 1, pageSize: 100, total: 1 }),
    );

    const { result } = renderHook(() => useCatalogIdentities(), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current).toHaveLength(1));
    expect(mockFetch).toHaveBeenCalledTimes(1);
  });
});
