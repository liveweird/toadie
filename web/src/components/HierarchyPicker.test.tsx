import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor } from "@testing-library/react";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";
import HierarchyPicker from "./HierarchyPicker";

function stubHierarchies(items: unknown[]) {
  vi.stubGlobal(
    "fetch",
    vi.fn(() => Promise.resolve(jsonResponse(200, { items }))),
  );
}

describe("HierarchyPicker", () => {
  beforeEach(() => {
    localStorage.setItem("toadie.auth.token", "fake-token");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("renders the dictionary values, in order, as options", async () => {
    stubHierarchies([
      { id: 1, value: "composition", isDefault: false },
      { id: 2, value: "cost-center", isDefault: false },
    ]);
    const onChange = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<HierarchyPicker value="composition" onChange={onChange} />);

    const picker = screen.getByLabelText("Hierarchy", { selector: "input" });
    await waitFor(() => expect(picker).not.toBeDisabled());
    await user.click(picker);
    const options = (await screen.findAllByRole("option")).map((option) => option.textContent);
    expect(options).toEqual(["composition", "cost-center"]);
  });

  test("picking an option calls onChange with its value", async () => {
    stubHierarchies([
      { id: 1, value: "composition", isDefault: false },
      { id: 2, value: "cost-center", isDefault: false },
    ]);
    const onChange = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<HierarchyPicker value="composition" onChange={onChange} />);

    await user.click(await screen.findByLabelText("Hierarchy", { selector: "input" }));
    await user.click(await screen.findByRole("option", { name: "cost-center" }));

    expect(onChange).toHaveBeenCalledWith("cost-center");
  });

  test("an empty dictionary disables the picker and shows the hint", async () => {
    stubHierarchies([]);
    renderWithProviders(<HierarchyPicker value="" onChange={vi.fn()} />);

    const picker = await screen.findByLabelText("Hierarchy", { selector: "input" });
    expect(picker).toBeDisabled();
    expect(screen.getByText("No hierarchies defined — add them on the Hierarchies page")).toBeInTheDocument();
  });
});
