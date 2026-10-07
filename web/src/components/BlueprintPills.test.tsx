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

  test("each chip leads with its blueprint's tier dot and keeps the bare accessible name", () => {
    const tiers: Record<string, 1 | 2 | null> = { team: 1, service: null };
    const { container } = renderWithProviders(
      <BlueprintPills active={["team", "service"]} hidden={[]} onChange={vi.fn()} tierOf={(id) => tiers[id] ?? null} />,
    );
    expect(container.querySelectorAll("[data-tier]")).toHaveLength(1);
    expect(container.querySelector('[data-tier="1"]')).not.toBeNull();
    expect(screen.getByRole("checkbox", { name: "team" })).toBeChecked();
  });

  test("with no focus nothing is dimmed", () => {
    const { container } = renderWithProviders(
      <BlueprintPills active={["team", "service"]} hidden={[]} onChange={vi.fn()} tierOf={() => null} focus={null} />,
    );
    expect(container.querySelector("[data-out-of-focus]")).toBeNull();
  });

  test("blueprints outside the focus are dimmed with a tooltip but still toggle", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    const tiers: Record<string, 1 | 3 | null> = { team: 1, service: 3, scratch: null };
    const { container } = renderWithProviders(
      <BlueprintPills
        active={["team", "service", "scratch"]}
        hidden={[]}
        onChange={onChange}
        tierOf={(id) => tiers[id] ?? null}
        focus={1}
      />,
    );
    const dimmed = [...container.querySelectorAll("[data-out-of-focus]")];
    expect(dimmed).toHaveLength(2);
    expect(dimmed.every((el) => el.getAttribute("title") === "Outside the selected tier focus")).toBe(true);
    expect(screen.getByRole("checkbox", { name: "team" }).closest("[data-out-of-focus]")).toBeNull();

    await user.click(screen.getByText("service", { exact: true }));
    expect(onChange).toHaveBeenCalledWith(["service"]);
  });
});
