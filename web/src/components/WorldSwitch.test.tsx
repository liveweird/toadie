import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen } from "../test/render";
import WorldSwitch from "./WorldSwitch";

describe("WorldSwitch", () => {
  test("renders two radios, the current world checked", () => {
    renderWithProviders(<WorldSwitch world="port" onChange={vi.fn()} />);
    const group = screen.getByRole("radiogroup", { name: "Product world" });
    const backstage = screen.getByRole("radio", { name: "Backstage" });
    const port = screen.getByRole("radio", { name: "Port" });
    expect(group).toContainElement(backstage);
    expect(group).toContainElement(port);
    expect(port).toBeChecked();
    expect(backstage).not.toBeChecked();
  });

  test("clicking the other option calls onChange with it", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithProviders(<WorldSwitch world="port" onChange={onChange} />);
    await user.click(screen.getByRole("radio", { name: "Backstage" }));
    expect(onChange).toHaveBeenCalledWith("backstage");
  });
});
