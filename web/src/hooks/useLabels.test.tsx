import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { waitFor } from "@testing-library/react";
import { useLabels } from "./useLabels";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

function Probe() {
  const { labels, loading, error } = useLabels();
  return (
    <div data-testid="probe" data-loading={loading} data-error={error}>
      {labels.map((label) => label.key).join(",")}
    </div>
  );
}

describe("useLabels", () => {
  beforeEach(() => {
    localStorage.setItem("toadie.auth.token", "fake-token");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("returns the registry's labels", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(() =>
        Promise.resolve(
          jsonResponse(200, {
            items: [
              { id: 1, key: "example.com/tier", values: ["backend"], kinds: ["Component"] },
              { id: 2, key: "team", values: ["core"], kinds: ["Group"] },
            ],
          }),
        ),
      ),
    );
    const { getByTestId } = renderWithProviders(<Probe />);
    await waitFor(() => expect(getByTestId("probe")).toHaveTextContent("example.com/tier,team"));
    expect(getByTestId("probe")).toHaveAttribute("data-error", "false");
  });

  test("a failed load surfaces as error with an empty list", async () => {
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(jsonResponse(500, { title: "x", status: 500 }))));
    const { getByTestId } = renderWithProviders(<Probe />);
    await waitFor(() => expect(getByTestId("probe")).toHaveAttribute("data-error", "true"));
    expect(getByTestId("probe")).toHaveTextContent("");
  });
});
