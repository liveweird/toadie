import { describe, expect, test } from "vitest";
import KindBadge from "./KindBadge";
import { renderWithProviders, screen } from "../test/render";

describe("KindBadge", () => {
  test("renders the bare kind as its own text node beside the aria-hidden tier dot", () => {
    const { container } = renderWithProviders(<KindBadge kind="Component" />);
    expect(screen.getByText("Component", { exact: true })).toBeInTheDocument();
    const dot = container.querySelector("[data-tier]");
    expect(dot).toHaveAttribute("data-tier", "2");
    expect(dot).toHaveAttribute("aria-hidden", "true");
    expect(container.querySelector("[data-status]")).toBeNull();
  });

  test("a MISSING status is exposed for the red-outline variant", () => {
    const { container } = renderWithProviders(<KindBadge kind="system" status="MISSING" size="xs" />);
    expect(container.querySelector('[data-status="MISSING"]')).not.toBeNull();
    expect(screen.getByText("system")).toBeInTheDocument();
  });
});
