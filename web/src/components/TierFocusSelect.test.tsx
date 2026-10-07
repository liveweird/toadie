import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen } from "../test/render";
import TierFocusSelect from "./TierFocusSelect";

describe("TierFocusSelect", () => {
  test("shows the All tiers placeholder when cleared and offers the four ranges, each with its dot", async () => {
    const user = userEvent.setup();
    const { container } = renderWithProviders(<TierFocusSelect value={null} onChange={vi.fn()} />);
    expect(screen.getByPlaceholderText("All tiers")).toBeInTheDocument();

    await user.click(screen.getByRole("combobox", { name: "Focus" }));
    for (const label of ["Tier 1", "Tiers 1–2", "Tiers 1–3", "Tiers 1–4"]) {
      expect(await screen.findByRole("option", { name: label })).toBeInTheDocument();
    }
    expect(container.ownerDocument.querySelectorAll('[role="option"] [data-tier]')).toHaveLength(4);
  });

  test("picking an option reports the tier as a number", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<TierFocusSelect value={null} onChange={onChange} />);
    await user.click(screen.getByRole("combobox", { name: "Focus" }));
    await user.click(await screen.findByRole("option", { name: "Tiers 1–3" }));
    expect(onChange).toHaveBeenCalledWith(3);
  });

  test("a set focus shows its range with a leading dot and clears to null", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    const { container } = renderWithProviders(<TierFocusSelect value={2} onChange={onChange} />);
    expect(screen.getByRole("combobox", { name: "Focus" })).toHaveValue("Tiers 1–2");
    expect(container.querySelector('.mantine-Select-section [data-tier="2"]')).not.toBeNull();

    await user.click(screen.getByLabelText("Clear focus"));
    expect(onChange).toHaveBeenCalledWith(null);
  });
});
