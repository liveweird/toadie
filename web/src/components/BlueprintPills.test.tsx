import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen, within } from "../test/render";
import BlueprintPills from "./BlueprintPills";

describe("BlueprintPills", () => {
  test("renders every active blueprint as a checked chip inside a captioned group", () => {
    renderWithProviders(<BlueprintPills active={["team", "service"]} hidden={[]} onChange={vi.fn()} />);
    const group = screen.getByRole("group", { name: "Blueprints" });
    expect(within(group).getByText("Blueprints")).toBeInTheDocument();
    expect(within(group).getByRole("checkbox", { name: "team" })).toBeChecked();
    expect(within(group).getByRole("checkbox", { name: "service" })).toBeChecked();
  });

  test("a hidden blueprint's chip is unchecked", () => {
    renderWithProviders(<BlueprintPills active={["team", "service"]} hidden={["service"]} onChange={vi.fn()} />);
    expect(screen.getByRole("checkbox", { name: "team" })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: "service" })).not.toBeChecked();
  });

  test("toggling a chip off reports the new hidden set", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<BlueprintPills active={["team", "service"]} hidden={[]} onChange={onChange} />);

    await user.click(screen.getByText("service", { exact: true }));
    expect(onChange).toHaveBeenCalledWith(["service"]);
  });

  test("toggling a hidden chip back on reports the empty hidden set", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<BlueprintPills active={["team", "service"]} hidden={["service"]} onChange={onChange} />);

    await user.click(screen.getByText("service", { exact: true }));
    expect(onChange).toHaveBeenCalledWith([]);
  });
});
