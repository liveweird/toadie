import { describe, expect, test } from "vitest";
import TierDot from "./TierDot";
import { renderWithProviders } from "../test/render";

describe("TierDot", () => {
  test("renders the numeral hidden from the accessibility tree, with the tier tooltip", () => {
    const { container } = renderWithProviders(<TierDot tier={3} />);
    const dot = container.querySelector("[data-tier]");
    expect(dot).toHaveAttribute("data-tier", "3");
    expect(dot).toHaveAttribute("aria-hidden", "true");
    expect(dot).toHaveAttribute("title", "Tier 3 — fill lower tiers first");
    // The numeral is CSS ::before content over data-tier, NOT a text node.
    expect(dot).toHaveTextContent("");
  });

  test.each([null, undefined, 0, 5, 1.5])("renders nothing for %s", (tier) => {
    const { container } = renderWithProviders(<TierDot tier={tier} />);
    expect(container.querySelector("[data-tier]")).toBeNull();
  });
});
