import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen } from "../test/render";
import BlueprintPickPills from "./BlueprintPickPills";

describe("BlueprintPickPills", () => {
  test("is one captioned single-choice group with the picked blueprint checked", () => {
    renderWithProviders(<BlueprintPickPills blueprints={["service", "team"]} value="team" onChange={vi.fn()} />);
    expect(screen.getByRole("group", { name: "Blueprint" })).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: "team" })).toBeChecked();
    expect(screen.getByRole("radio", { name: "service" })).not.toBeChecked();
  });

  test("nothing is checked while no blueprint is picked, and a click reports the identifier", async () => {
    const onChange = vi.fn();
    renderWithProviders(<BlueprintPickPills blueprints={["service", "team"]} value={null} onChange={onChange} />);
    expect(screen.getByRole("radio", { name: "service" })).not.toBeChecked();
    await userEvent.setup().click(screen.getByRole("radio", { name: "service" }));
    expect(onChange).toHaveBeenCalledWith("service");
  });
});
