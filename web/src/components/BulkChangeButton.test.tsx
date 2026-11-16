import { describe, expect, test } from "vitest";
import { renderWithProviders, screen } from "../test/render";
import BulkChangeButton from "./BulkChangeButton";

describe("BulkChangeButton", () => {
  test("links to the bulk-change page carrying blueprint, search and team", () => {
    renderWithProviders(<BulkChangeButton blueprint="service" q="pay" team="platform" />);
    expect(screen.getByRole("link", { name: "Bulk change" })).toHaveAttribute(
      "href",
      "/entities/bulk-change?blueprint=service&q=pay&team=platform",
    );
  });

  test("without a scope it links to the bare page", () => {
    renderWithProviders(<BulkChangeButton />);
    expect(screen.getByRole("link", { name: "Bulk change" })).toHaveAttribute("href", "/entities/bulk-change");
  });
});
