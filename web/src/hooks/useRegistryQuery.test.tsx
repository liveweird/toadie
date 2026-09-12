import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { waitFor } from "@testing-library/react";
import { useRegistryQuery } from "./useRegistryQuery";
import { renderWithProviders } from "../test/render";

type Thing = { id: number; name: string };

function fetchThings(): Promise<Thing[]> {
  return Promise.resolve([
    { id: 1, name: "alpha" },
    { id: 2, name: "beta" },
  ]);
}

function fetchThingsFailing(): Promise<Thing[]> {
  return Promise.reject(new Error("boom"));
}

function Probe({ fetcher }: { fetcher: () => Promise<Thing[]> }) {
  const { things, loading, error, loadError } = useRegistryQuery(["things"], fetcher, "things");
  return (
    <div
      data-testid="probe"
      data-loading={loading}
      data-error={error}
      data-load-error={loadError === null ? "null" : "present"}
    >
      {things.map((thing) => thing.name).join(",")}
    </div>
  );
}

describe("useRegistryQuery", () => {
  beforeEach(() => {
    localStorage.setItem("toadie.auth.token", "fake-token");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("resolves loading then the fetched items", async () => {
    const { getByTestId } = renderWithProviders(<Probe fetcher={fetchThings} />);
    expect(getByTestId("probe")).toHaveAttribute("data-loading", "true");
    await waitFor(() => expect(getByTestId("probe")).toHaveTextContent("alpha,beta"));
    expect(getByTestId("probe")).toHaveAttribute("data-error", "false");
    expect(getByTestId("probe")).toHaveAttribute("data-load-error", "null");
  });

  test("a failed fetch surfaces as error with an empty list and the raw failure", async () => {
    const { getByTestId } = renderWithProviders(<Probe fetcher={fetchThingsFailing} />);
    await waitFor(() => expect(getByTestId("probe")).toHaveAttribute("data-error", "true"));
    expect(getByTestId("probe")).toHaveTextContent("");
    expect(getByTestId("probe")).toHaveAttribute("data-load-error", "present");
  });
});
