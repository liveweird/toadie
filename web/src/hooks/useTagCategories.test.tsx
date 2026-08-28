import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { waitFor } from "@testing-library/react";
import { useTagCategories } from "./useTagCategories";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

function Probe() {
  const { categories, loading, error } = useTagCategories();
  return (
    <div data-testid="probe" data-loading={loading} data-error={error}>
      {categories.map((category) => category.name).join(",")}
    </div>
  );
}

describe("useTagCategories", () => {
  beforeEach(() => {
    localStorage.setItem("toadie.auth.token", "fake-token");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("returns the registry's categories", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(() =>
        Promise.resolve(
          jsonResponse(200, {
            items: [
              { id: 1, name: "Languages", tags: ["java"], kinds: ["Component"] },
              { id: 2, name: "Teams", tags: ["core"], kinds: ["Group"] },
            ],
          }),
        ),
      ),
    );
    const { getByTestId } = renderWithProviders(<Probe />);
    await waitFor(() => expect(getByTestId("probe")).toHaveTextContent("Languages,Teams"));
    expect(getByTestId("probe")).toHaveAttribute("data-error", "false");
  });

  test("a failed load surfaces as error with an empty list", async () => {
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(jsonResponse(500, { title: "x", status: 500 }))));
    const { getByTestId } = renderWithProviders(<Probe />);
    await waitFor(() => expect(getByTestId("probe")).toHaveAttribute("data-error", "true"));
    expect(getByTestId("probe")).toHaveTextContent("");
  });
});
