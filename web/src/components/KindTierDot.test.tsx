import { describe, expect, test } from "vitest";
import KindTierDot, { renderKindOption } from "./KindTierDot";
import { renderWithProviders } from "../test/render";
import { ENTITY_KINDS, KIND_TIERS, kindTier } from "../utils/catalogFileForm";

describe("kindTier", () => {
  test.each([
    ["Domain", 1],
    ["System", 1],
    ["Group", 1],
    ["Component", 2],
    ["Resource", 3],
    ["API", 3],
    ["User", 4],
  ] as const)("%s is tier %d", (kind, tier) => {
    expect(kindTier(kind)).toBe(tier);
  });

  test("folds case - the graph endpoint spells kinds lowercase", () => {
    expect(kindTier("component")).toBe(2);
    expect(kindTier("api")).toBe(3);
    expect(kindTier("DOMAIN")).toBe(1);
  });

  test("unknown kinds have no tier", () => {
    expect(kindTier("Template")).toBeUndefined();
    expect(kindTier("")).toBeUndefined();
  });

  test("every kind has a tier", () => {
    for (const kind of ENTITY_KINDS) {
      expect(KIND_TIERS[kind]).toBeGreaterThanOrEqual(1);
      expect(KIND_TIERS[kind]).toBeLessThanOrEqual(4);
    }
  });
});

describe("KindTierDot", () => {
  test("renders the numeral, hidden from the accessibility tree, with the tier tooltip", () => {
    const { container } = renderWithProviders(<KindTierDot kind="Domain" />);
    const dot = container.querySelector("[data-tier]");
    expect(dot).not.toBeNull();
    expect(dot).toHaveAttribute("data-tier", "1");
    expect(dot).toHaveAttribute("aria-hidden", "true");
    expect(dot).toHaveAttribute("title", "Tier 1 — fill lower tiers first");
    // The numeral is CSS ::before content over data-tier, NOT a text node — text locators
    // everywhere (getByText exact, Playwright included) must keep seeing the bare kind.
    expect(dot).toHaveTextContent("");
  });

  test("lowercase kinds get their tier too", () => {
    const { container } = renderWithProviders(<KindTierDot kind="user" />);
    expect(container.querySelector("[data-tier]")).toHaveAttribute("data-tier", "4");
  });

  test("renders nothing for an unknown kind", () => {
    const { container } = renderWithProviders(<KindTierDot kind="Location" />);
    expect(container.querySelector("[data-tier]")).toBeNull();
  });
});

describe("renderKindOption", () => {
  test("prefixes the kind with its dot and re-renders the selected checkmark", () => {
    const { container } = renderWithProviders(
      <>{renderKindOption({ option: { value: "Component", label: "Component" }, checked: true })}</>,
    );
    expect(container.querySelector('[data-tier="2"]')).not.toBeNull();
    expect(container).toHaveTextContent("Component");
    expect(container.querySelector("svg")).not.toBeNull();
  });

  test("no checkmark when the option is not selected", () => {
    const { container } = renderWithProviders(
      <>{renderKindOption({ option: { value: "User", label: "User" } })}</>,
    );
    expect(container.querySelector('[data-tier="4"]')).not.toBeNull();
    expect(container.querySelector("svg")).toBeNull();
  });
});
