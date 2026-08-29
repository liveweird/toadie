import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { fireEvent, screen } from "@testing-library/react";
import { useState } from "react";
import NamespaceFilterSelect from "./NamespaceFilterSelect";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const TOKEN_KEY = "toadie.auth.token";

type FetchMock = ReturnType<typeof vi.fn>;

const NAMESPACE_ENTRIES = {
  items: [
    { id: 1, value: "default", isDefault: true },
    { id: 2, value: "team-a", isDefault: false },
  ],
};

function Harness({ initial = "" }: { initial?: string }) {
  const [value, setValue] = useState(initial);
  return (
    <>
      <NamespaceFilterSelect value={value} onChange={setValue} />
      <output data-testid="value">{value}</output>
    </>
  );
}

describe("NamespaceFilterSelect", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem(TOKEN_KEY, "fake-token");
    mockFetch.mockImplementation((url: string) =>
      url.startsWith("/api/v1/dictionaries/namespaces")
        ? Promise.resolve(jsonResponse(200, NAMESPACE_ENTRIES))
        : Promise.resolve(jsonResponse(404, {})),
    );
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("offers the dictionary values and picking one propagates it", async () => {
    renderWithProviders(<Harness />);

    fireEvent.click(screen.getByLabelText("Namespace", { selector: "input" }));
    fireEvent.click(await screen.findByRole("option", { name: "team-a" }));

    expect(screen.getByTestId("value")).toHaveTextContent("team-a");
  });

  test("the clear button empties the filter", async () => {
    renderWithProviders(<Harness initial="team-a" />);

    fireEvent.click(await screen.findByLabelText("Clear namespace filter"));

    expect(screen.getByTestId("value")).toBeEmptyDOMElement();
  });

  test("a persisted value no longer in the dictionary is still offered", async () => {
    renderWithProviders(<Harness initial="vanished" />);

    fireEvent.click(screen.getByLabelText("Namespace", { selector: "input" }));

    // The searchable input holds "vanished", so the dropdown is narrowed to it — the
    // assertion is that the appended stale value is offered (and displays) at all.
    expect(await screen.findByRole("option", { name: "vanished" })).toBeInTheDocument();
  });
});
