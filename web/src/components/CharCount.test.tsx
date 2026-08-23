import { describe, expect, test } from "vitest";
import { renderWithProviders, screen } from "../test/render";
import CharCount from "./CharCount";
import { charCountDescription, shouldShowCharCount } from "../utils/charCount";

describe("CharCount", () => {
  test("renders the counter", () => {
    renderWithProviders(<CharCount current={3} max={100} />);
    expect(screen.getByText("3 / 100")).toBeInTheDocument();
  });

  test("an over-limit count renders red (programmatic pushes only)", () => {
    renderWithProviders(<CharCount current={101} max={100} />);
    expect(screen.getByText("101 / 100")).toHaveAttribute("style", expect.stringContaining("red"));
  });
});

describe("charCountDescription", () => {
  test("nearLimit mode hides the counter until 80% of the limit", () => {
    expect(charCountDescription(79, 100)).toBeUndefined();
    const { container } = renderWithProviders(<>{charCountDescription(80, 100)}</>);
    expect(container).toHaveTextContent("80 / 100");
  });
});

describe("shouldShowCharCount", () => {
  test("the single visibility predicate", () => {
    expect(shouldShowCharCount(1, 100, "always")).toBe(true);
    expect(shouldShowCharCount(79, 100, "nearLimit")).toBe(false);
    expect(shouldShowCharCount(80, 100, "nearLimit")).toBe(true);
  });
});
