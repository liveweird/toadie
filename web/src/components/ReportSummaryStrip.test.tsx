import { describe, expect, test, vi } from "vitest";
import { fireEvent, screen } from "@testing-library/react";
import { renderWithProviders as render } from "../test/render";
import ReportSummaryStrip from "./ReportSummaryStrip";
import classes from "../theme.module.css";

type FakeClass = "a" | "b";

function renderStrip(overrides: Partial<Parameters<typeof ReportSummaryStrip<FakeClass>>[0]> = {}) {
  const setClasses = vi.fn();
  render(
    <ReportSummaryStrip<FakeClass>
      tiles={[
        { value: 3, label: "Checked" },
        { value: 2, label: "Errors", color: "red" },
      ]}
      allClasses={["a", "b"]}
      selectedClasses={["a", "b"]}
      setClasses={setClasses}
      counts={{ a: 1, b: 0 }}
      classLabel={(c) => (c === "a" ? "Class A" : "Class B")}
      groupAriaLabel="Error types"
      {...overrides}
    />,
  );
  return { setClasses };
}

describe("ReportSummaryStrip", () => {
  test("renders one tile per entry, named by data-tile for tests", () => {
    renderStrip();
    const checked = document.querySelector('[data-tile="Checked"]')!;
    expect(checked.firstElementChild).toHaveTextContent("3");
    const errors = document.querySelector('[data-tile="Errors"]')!;
    expect(errors.firstElementChild).toHaveTextContent("2");
  });

  test("renders one chip per class with an aria-hidden count and a bare accessible name", () => {
    renderStrip();
    const group = screen.getByRole("group", { name: "Error types" });
    const chipA = screen.getByRole("checkbox", { name: "Class A" });
    expect(group).toContainElement(chipA);
    // The accessible NAME excludes the aria-hidden count (asserted via getByRole above); the
    // chip's visible text still carries both, checked on its root — a `label` is the input's
    // SIBLING here, not an ancestor, so `closest` can't reach it.
    expect(chipA.closest("[class*='Chip-root']")).toHaveTextContent("Class A1");
    expect(screen.getByRole("checkbox", { name: "Class B" })).toBeInTheDocument();
  });

  test("toggling a chip calls setClasses with the new selection", () => {
    const { setClasses } = renderStrip();
    fireEvent.click(screen.getByRole("checkbox", { name: "Class A" }));
    expect(setClasses).toHaveBeenCalledWith(["b"]);
  });

  test("a zero count is marked data-zero for styling", () => {
    renderStrip();
    const chipB = screen.getByRole("checkbox", { name: "Class B" }).closest("[class*='Chip-root']")!;
    const count = chipB.querySelector(`.${classes.chipCount}`)!;
    expect(count).toHaveAttribute("data-zero", "true");
  });
});
