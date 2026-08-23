import { describe, expect, test } from "vitest";
import { renderWithProviders, screen } from "../test/render";
import CharCount from "./CharCount";
import { shouldShowCharCount } from "../utils/charCount";

describe("CharCount", () => {
  test("always mode renders the counter", () => {
    renderWithProviders(<CharCount current={3} max={100} mode="always" />);
    expect(screen.getByText("3 / 100")).toBeInTheDocument();
  });

  test("nearLimit mode hides the counter until 80% of the limit", () => {
    const { rerender } = renderWithProviders(<CharCount current={79} max={100} mode="nearLimit" />);
    expect(screen.queryByText("79 / 100")).not.toBeInTheDocument();
    rerender(<CharCount current={80} max={100} mode="nearLimit" />);
    expect(screen.getByText("80 / 100")).toBeInTheDocument();
  });

  test("an over-limit count renders red (programmatic pushes only)", () => {
    renderWithProviders(<CharCount current={101} max={100} />);
    expect(screen.getByText("101 / 100")).toHaveAttribute("style", expect.stringContaining("red"));
  });
});

describe("shouldShowCharCount", () => {
  test("mirrors the component's visibility rule", () => {
    expect(shouldShowCharCount(1, 100, "always")).toBe(true);
    expect(shouldShowCharCount(79, 100, "nearLimit")).toBe(false);
    expect(shouldShowCharCount(80, 100, "nearLimit")).toBe(true);
  });
});
