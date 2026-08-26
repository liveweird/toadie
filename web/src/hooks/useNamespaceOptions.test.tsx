import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { waitFor } from "@testing-library/react";
import { useNamespaceOptions } from "./useNamespaceOptions";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

function Probe({ current }: { current?: string }) {
  const { options, defaultNamespace, loading, error } = useNamespaceOptions(current);
  return (
    <div data-testid="probe" data-loading={loading} data-error={error} data-default={defaultNamespace}>
      {options.join(",")}
    </div>
  );
}

describe("useNamespaceOptions", () => {
  beforeEach(() => {
    localStorage.setItem("toadie.auth.token", "fake-token");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("maps the dictionary's active entries to options in order", async () => {
    vi.stubGlobal("fetch", vi.fn(() =>
      Promise.resolve(jsonResponse(200, {
        items: [
          { id: 1, value: "default", isDefault: false },
          { id: 2, value: "team-a", isDefault: true },
        ],
      })),
    ));
    const { getByTestId } = renderWithProviders(<Probe />);
    await waitFor(() => expect(getByTestId("probe")).toHaveTextContent("default,team-a"));
    // The flagged entry surfaces as defaultNamespace — whatever its value is.
    expect(getByTestId("probe")).toHaveAttribute("data-default", "team-a");
  });

  test("appends a current value no longer among the active entries (folded)", async () => {
    vi.stubGlobal("fetch", vi.fn(() =>
      Promise.resolve(jsonResponse(200, { items: [{ id: 1, value: "default", isDefault: true }] })),
    ));
    const { getByTestId } = renderWithProviders(<Probe current="  Removed-NS " />);
    await waitFor(() => expect(getByTestId("probe")).toHaveTextContent("default,removed-ns"));
  });

  test("a failed load reports error with empty options", async () => {
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(jsonResponse(500, { title: "x", status: 500 }))));
    const { getByTestId } = renderWithProviders(<Probe />);
    await waitFor(() => expect(getByTestId("probe")).toHaveAttribute("data-error", "true"));
    expect(getByTestId("probe")).toHaveTextContent("");
  });
});
