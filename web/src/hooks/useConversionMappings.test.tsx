import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { waitFor } from "@testing-library/react";
import { useConversionMappings } from "./useConversionMappings";
import { conversionMappingResponse } from "../test/fixtures";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

function Probe() {
  const { mappings, error } = useConversionMappings();
  return (
    <div data-testid="probe" data-error={error}>
      {mappings.map((mapping) => mapping.name).join(",")}
    </div>
  );
}

describe("useConversionMappings", () => {
  beforeEach(() => {
    localStorage.setItem("toadie.auth.token", "fake-token");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("returns the registry's mappings in payload order", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(() =>
        Promise.resolve(
          jsonResponse(200, {
            items: [conversionMappingResponse({ id: 1, name: "Alpha" }), conversionMappingResponse({ id: 2, name: "Beta" })],
          }),
        ),
      ),
    );
    const { getByTestId } = renderWithProviders(<Probe />);
    await waitFor(() => expect(getByTestId("probe")).toHaveTextContent("Alpha,Beta"));
    expect(getByTestId("probe")).toHaveAttribute("data-error", "false");
  });

  test("a failed load surfaces as error with an empty list", async () => {
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(jsonResponse(500, { title: "x", status: 500 }))));
    const { getByTestId } = renderWithProviders(<Probe />);
    await waitFor(() => expect(getByTestId("probe")).toHaveAttribute("data-error", "true"));
  });
});
