import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "../test/render";
import en from "../locales/en/common.json";
import pl from "../locales/pl/common.json";
import GraphFoldToggle from "./GraphFoldToggle";

/** axe's `label-content-name-mismatch` (WCAG 2.5.3) compares the visible text with the accessible
 *  name AFTER removing every parenthetical, so a count that only lives inside "(… hidden)" counts as
 *  missing. The same reduction, applied to the strings themselves. */
const withoutParentheticals = (text: string) => text.replace(/\([^()]*\)/g, "");

describe("GraphFoldToggle", () => {
  test.each([
    ["en", en.fold.expandAria],
    ["pl", pl.fold.expandAria],
  ])("the %s Expand name carries the visible count outside any parenthetical", (_lang, template) => {
    expect(withoutParentheticals(template)).toContain("{{count}}");
  });

  test("the collapsed pill shows the count and is named with it, count outside parentheses", async () => {
    const onToggle = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(
      <GraphFoldToggle name="Platform (core)" fold={{ collapsed: true, descendants: 39, onToggle }} />,
    );

    const pill = screen.getByRole("button", { name: "Expand Platform (core), 39 hidden" });
    expect(pill).toHaveTextContent("39");
    expect(withoutParentheticals(pill.getAttribute("aria-label") ?? "")).toContain("39");
    await user.click(pill);
    expect(onToggle).toHaveBeenCalledTimes(1);
  });

  test("the expanded toggle is a chevron named for the collapse action", () => {
    renderWithProviders(<GraphFoldToggle name="Platform" fold={{ collapsed: false, descendants: 3, onToggle: vi.fn() }} />);
    expect(screen.getByRole("button", { name: "Collapse Platform" })).toBeInTheDocument();
  });
});
