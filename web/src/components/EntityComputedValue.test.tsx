import { describe, expect, test } from "vitest";
import { screen } from "@testing-library/react";
import type { ComputedDefinition } from "../utils/computedProperties";
import EntityComputedValue from "./EntityComputedValue";
import { renderWithProviders } from "../test/render";

function definition(overrides: Partial<ComputedDefinition> = {}): ComputedDefinition {
  return { id: "risk", kind: "calculation", title: "Risk", ...overrides };
}

describe("EntityComputedValue", () => {
  test("undefined shows the dimmed 'Not available' placeholder", () => {
    renderWithProviders(<EntityComputedValue value={undefined} definition={definition()} />);
    expect(screen.getByText("Not available")).toBeInTheDocument();
  });

  test("null shows the same placeholder as undefined", () => {
    renderWithProviders(<EntityComputedValue value={null} definition={definition()} />);
    expect(screen.getByText("Not available")).toBeInTheDocument();
  });

  test("true renders the True badge", () => {
    renderWithProviders(<EntityComputedValue value={true} definition={definition()} />);
    expect(screen.getByText("True")).toBeInTheDocument();
  });

  test("false renders the False badge", () => {
    renderWithProviders(<EntityComputedValue value={false} definition={definition()} />);
    expect(screen.getByText("False")).toBeInTheDocument();
  });

  test("a plain string renders as text, not a badge", () => {
    renderWithProviders(<EntityComputedValue value="checkout-service" definition={definition({ colorized: false })} />);
    expect(screen.getByText("checkout-service")).toBeInTheDocument();
  });

  test("a plain number renders as text", () => {
    renderWithProviders(<EntityComputedValue value={16} definition={definition({ type: "number" })} />);
    expect(screen.getByText("16")).toBeInTheDocument();
  });

  test("a colorized string with a matching colour renders a tinted badge", () => {
    const { container } = renderWithProviders(
      <EntityComputedValue value="high" definition={definition({ colorized: true, colors: { high: "red" } })} />,
    );
    expect(screen.getByText("high")).toBeInTheDocument();
    expect(container.querySelector(".mantine-Badge-root")).not.toBeNull();
  });

  test("a colorized value with no matching colour still renders as a badge", () => {
    const { container } = renderWithProviders(
      <EntityComputedValue value="unknown" definition={definition({ colorized: true, colors: { high: "red" } })} />,
    );
    expect(screen.getByText("unknown")).toBeInTheDocument();
    expect(container.querySelector(".mantine-Badge-root")).not.toBeNull();
  });

  test("an array of scalars renders as gray pills", () => {
    renderWithProviders(<EntityComputedValue value={["java", "kotlin"]} definition={definition({ kind: "mirror" })} />);
    expect(screen.getByText("java")).toBeInTheDocument();
    expect(screen.getByText("kotlin")).toBeInTheDocument();
  });

  test("an array containing an object renders that item as inline JSON code", () => {
    renderWithProviders(
      <EntityComputedValue value={[{ url: "https://example.com" }]} definition={definition({ kind: "mirror" })} />,
    );
    expect(screen.getByText('{"url":"https://example.com"}')).toBeInTheDocument();
  });

  test("an empty array renders no pills at all", () => {
    const { container } = renderWithProviders(<EntityComputedValue value={[]} definition={definition({ kind: "mirror" })} />);
    expect(container.querySelectorAll(".mantine-Badge-root, .mantine-Code-root")).toHaveLength(0);
  });

  test("a plain object renders as a pretty-printed JSON code block", () => {
    renderWithProviders(
      <EntityComputedValue value={{ url: "https://example.com", displayText: "Docs" }} definition={definition({ type: "object" })} />,
    );
    expect(screen.getByText(/"url": "https:\/\/example\.com"/)).toBeInTheDocument();
  });
});
