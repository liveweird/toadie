import { describe, expect, test } from "vitest";
import { renderWithProviders, screen } from "../test/render";
import Home from "./Home";

describe("Home", () => {
  test("renders the placeholder card", () => {
    renderWithProviders(<Home />);
    expect(screen.getByRole("heading", { level: 2, name: "Home" })).toBeInTheDocument();
    expect(screen.getByText(/catalog-info\.yaml/)).toBeInTheDocument();
  });
});
