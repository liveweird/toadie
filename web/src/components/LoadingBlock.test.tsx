import { describe, expect, test } from "vitest";
import LoadingBlock from "./LoadingBlock";
import { renderWithProviders, screen } from "../test/render";

describe("LoadingBlock", () => {
  test("renders one named spinner", () => {
    renderWithProviders(<LoadingBlock />);
    expect(screen.getByRole("status", { name: "Loading…" })).toBeInTheDocument();
  });
});
