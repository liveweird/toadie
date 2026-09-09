import { describe, expect, test } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "../test/render";
import EntityFindingsBadge from "./EntityFindingsBadge";
import type { EntityFinding } from "../api/entities";

describe("EntityFindingsBadge", () => {
  test("renders nothing for zero findings", () => {
    renderWithProviders(<EntityFindingsBadge findings={[]} />);
    expect(screen.queryByText(/finding/)).not.toBeInTheDocument();
  });

  test("renders the count for one finding", () => {
    const findings = [{ code: "REQUIRED_MISSING", field: "properties.name", message: "Required" }] as EntityFinding[];
    renderWithProviders(<EntityFindingsBadge findings={findings} />);
    expect(screen.getByText("1 finding")).toBeInTheDocument();
  });

  test("renders the plural count for several findings", () => {
    const findings = [
      { code: "REQUIRED_MISSING", field: "properties.name", message: "Required" },
      { code: "UNKNOWN_RELATION", field: "relations.gone", message: "Unknown" },
    ] as EntityFinding[];
    renderWithProviders(<EntityFindingsBadge findings={findings} />);
    expect(screen.getByText("2 findings")).toBeInTheDocument();
  });
});
