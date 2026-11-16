import { describe, expect, test } from "vitest";
import { renderWithProviders, screen, within } from "../test/render";
import type { EntityBulkResult } from "../api/entities";
import EntityBulkResults from "./EntityBulkResults";

const ROWS: EntityBulkResult[] = [
  { id: 1, identifier: "checkout", title: "Checkout", status: "UPDATED", updatedAt: 5, before: "beta", after: "live" },
  { id: 2, identifier: "billing", title: "Billing", status: "UNCHANGED", updatedAt: 6, before: "live", after: "live" },
  { id: 3, identifier: "search", title: "Search", status: "INVALID", message: "properties.stage: not allowed", before: undefined, after: "x" },
  { id: 4, status: "STALE", message: "the entity no longer exists" },
  { id: 5, identifier: "long", status: "UPDATED", valuesOmitted: true },
  { id: 6, identifier: "list", status: "UPDATED", before: ["a"], after: ["a", "b"] },
];

describe("EntityBulkResults", () => {
  test("the dry-run reads as predictions and summarizes the counts", () => {
    renderWithProviders(<EntityBulkResults rows={ROWS} mode="check" />);
    expect(screen.getByText("Will be updated: 3, unchanged: 1, cannot be changed: 2.")).toBeInTheDocument();
    const table = screen.getByRole("table", { name: "Bulk change results" });
    expect(within(table).getAllByText("Will update")).toHaveLength(3);
    expect(within(table).getByText("Invalid")).toBeInTheDocument();
    expect(within(table).getByText("Stale")).toBeInTheDocument();
  });

  test("the real run reads as outcomes", () => {
    renderWithProviders(<EntityBulkResults rows={ROWS} mode="apply" />);
    expect(screen.getByText("Updated: 3, unchanged: 1, not changed: 2.")).toBeInTheDocument();
    expect(screen.getAllByText("Updated")).toHaveLength(3);
  });

  test("shows identity, before → after, withheld values and the server's message", () => {
    renderWithProviders(<EntityBulkResults rows={ROWS} mode="check" />);
    const checkoutRow = screen.getByText("checkout").closest("tr")!;
    expect(within(checkoutRow).getByText("beta")).toBeInTheDocument();
    expect(within(checkoutRow).getByText("live")).toBeInTheDocument();
    expect(screen.getByText("properties.stage: not allowed")).toBeInTheDocument();
    expect(screen.getByText("#4")).toBeInTheDocument();
    expect(screen.getByText("Values too long to show")).toBeInTheDocument();
    expect(screen.getByText("a")).toBeInTheDocument();
    expect(screen.getByText("a, b")).toBeInTheDocument();
  });
});
