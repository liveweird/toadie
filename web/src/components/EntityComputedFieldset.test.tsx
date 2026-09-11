import { describe, expect, test } from "vitest";
import { screen } from "@testing-library/react";
import type { ComputedDefinition } from "../utils/computedProperties";
import EntityComputedFieldset from "./EntityComputedFieldset";
import { renderWithProviders } from "../test/render";

const DEFINITIONS: ComputedDefinition[] = [
  { id: "domain_title", kind: "mirror", title: "Domain title" },
  { id: "risk", kind: "calculation", title: "Risk", type: "string", colorized: true, colors: { high: "red" } },
  { id: "service_count", kind: "aggregation", title: "Service count" },
];

describe("EntityComputedFieldset", () => {
  test("renders as a named group 'Computed'", () => {
    renderWithProviders(<EntityComputedFieldset definitions={DEFINITIONS} values={{}} />);
    expect(screen.getByRole("group", { name: "Computed" })).toBeInTheDocument();
  });

  test("renders one row per definition with its title, kind badge, value, and hint", () => {
    renderWithProviders(
      <EntityComputedFieldset
        definitions={DEFINITIONS}
        values={{ domain_title: "Commerce", risk: "high", service_count: 3 }}
      />,
    );

    expect(screen.getByText("Domain title")).toBeInTheDocument();
    expect(screen.getByText("Mirror")).toBeInTheDocument();
    expect(screen.getByText("Commerce")).toBeInTheDocument();

    expect(screen.getByText("Risk")).toBeInTheDocument();
    expect(screen.getByText("Calculation")).toBeInTheDocument();
    expect(screen.getByText("high")).toBeInTheDocument();

    expect(screen.getByText("Service count")).toBeInTheDocument();
    expect(screen.getByText("Aggregation")).toBeInTheDocument();
    expect(screen.getByText("3")).toBeInTheDocument();

    expect(screen.getAllByText("Computed automatically on the server — not editable here.")).toHaveLength(3);
  });

  test("an absent value renders 'Not available' for that row", () => {
    renderWithProviders(<EntityComputedFieldset definitions={DEFINITIONS} values={{}} />);
    expect(screen.getAllByText("Not available")).toHaveLength(3);
  });

  test("no definitions renders an empty (still named) group", () => {
    renderWithProviders(<EntityComputedFieldset definitions={[]} values={{}} />);
    expect(screen.getByRole("group", { name: "Computed" })).toBeInTheDocument();
  });
});
