import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen } from "../test/render";
import TierSelect from "./TierSelect";

describe("TierSelect", () => {
  test("an untiered value reads No tier with no dot; the aria label overrides the visible one", () => {
    const { container } = renderWithProviders(
      <TierSelect label="Tier" ariaLabel="Tier for name" value={null} onChange={vi.fn()} />,
    );
    expect(screen.getByRole("combobox", { name: "Tier for name" })).toHaveValue("No tier");
    expect(container.querySelector(".mantine-Select-section [data-tier]")).toBeNull();
  });

  test("picks a tier as a number and No tier as null", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    const { container } = renderWithProviders(<TierSelect label="Tier" value={2} onChange={onChange} />);
    expect(screen.getByRole("combobox", { name: "Tier" })).toHaveValue("Tier 2");
    expect(container.querySelector('.mantine-Select-section [data-tier="2"]')).not.toBeNull();

    await user.click(screen.getByRole("combobox", { name: "Tier" }));
    await user.click(await screen.findByRole("option", { name: "Tier 4" }));
    expect(onChange).toHaveBeenLastCalledWith(4);

    await user.click(screen.getByRole("combobox", { name: "Tier" }));
    await user.click(await screen.findByRole("option", { name: "No tier" }));
    expect(onChange).toHaveBeenLastCalledWith(null);
  });
});
