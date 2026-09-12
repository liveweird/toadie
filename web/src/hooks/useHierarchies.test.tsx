import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { waitFor } from "@testing-library/react";
import { useHierarchies } from "./useHierarchies";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

function Probe() {
  const { hierarchies, loading, error } = useHierarchies();
  return (
    <div data-testid="probe" data-loading={loading} data-error={error}>
      {hierarchies.map((entry) => entry.value).join(",")}
    </div>
  );
}

describe("useHierarchies", () => {
  beforeEach(() => {
    localStorage.setItem("toadie.auth.token", "fake-token");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("returns the dictionary's active entries in payload order", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(() =>
        Promise.resolve(
          jsonResponse(200, {
            items: [
              { id: 1, value: "composition", isDefault: false },
              { id: 2, value: "org", isDefault: false },
            ],
          }),
        ),
      ),
    );
    const { getByTestId } = renderWithProviders(<Probe />);
    await waitFor(() => expect(getByTestId("probe")).toHaveTextContent("composition,org"));
    expect(getByTestId("probe")).toHaveAttribute("data-error", "false");
  });

  test("a failed load surfaces as error with an empty list", async () => {
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(jsonResponse(500, { title: "x", status: 500 }))));
    const { getByTestId } = renderWithProviders(<Probe />);
    await waitFor(() => expect(getByTestId("probe")).toHaveAttribute("data-error", "true"));
    expect(getByTestId("probe")).toHaveTextContent("");
  });
});
