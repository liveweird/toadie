import { type ReactNode, useState } from "react";
import { describe, expect, test } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen } from "../test/render";
import CollapsibleHeader, { type HeaderSection } from "./CollapsibleHeader";

/** Two sections — one "plain" (a bare Group, the pills shape) and one "paper" (the bordered
 *  drawer shape, the Filters/Query shape) — driven by local state so tests can toggle them. */
function Host({ secondCount = 0, secondExtra }: { secondCount?: number; secondExtra?: ReactNode }) {
  const [firstOpen, setFirstOpen] = useState(false);
  const [secondOpen, setSecondOpen] = useState(false);
  const sections: HeaderSection[] = [
    {
      id: "plain-section",
      icon: <span aria-hidden="true" />,
      label: "First",
      open: firstOpen,
      onOpenChange: setFirstOpen,
      frame: "plain",
      content: <div>plain content</div>,
    },
    {
      id: "paper-section",
      icon: <span aria-hidden="true" />,
      label: "Second",
      open: secondOpen,
      onOpenChange: setSecondOpen,
      count: secondCount,
      extra: secondExtra,
      frame: "paper",
      content: <div>paper content</div>,
    },
  ];
  return (
    <CollapsibleHeader title="A page" sections={sections} aside={<span>aside control</span>}>
      <button type="button">child control</button>
    </CollapsibleHeader>
  );
}

describe("CollapsibleHeader", () => {
  test("renders the title, both toggles in order, aside, and children — collapsed by default with no toolbar row", () => {
    renderWithProviders(<Host />);
    expect(screen.getByRole("heading", { name: "A page" })).toBeInTheDocument();

    const toggles = screen.getAllByRole("button", { name: /^(First|Second)/ });
    expect(toggles.map((t) => t.textContent?.startsWith("First") ?? false)).toEqual([true, false]);

    const first = screen.getByRole("button", { name: "First" });
    const second = screen.getByRole("button", { name: "Second" });
    expect(first).toHaveAttribute("aria-expanded", "false");
    expect(first).not.toHaveAttribute("aria-controls");
    expect(second).toHaveAttribute("aria-expanded", "false");
    expect(second).not.toHaveAttribute("aria-controls");

    expect(screen.getByText("aside control")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "child control" })).toBeInTheDocument();

    // No section is open — no toolbar row at all.
    expect(screen.queryByText("plain content")).not.toBeInTheDocument();
    expect(screen.queryByText("paper content")).not.toBeInTheDocument();
  });

  test("a plain section renders bare with its id; a paper section renders inside a bordered Paper with its id", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Host />);

    await user.click(screen.getByRole("button", { name: "First" }));
    await user.click(screen.getByRole("button", { name: "Second" }));

    const plain = screen.getByText("plain content").closest("#plain-section");
    expect(plain).toBeInTheDocument();
    expect(plain?.classList.contains("mantine-Paper-root")).toBe(false);
    const paper = screen.getByText("paper content").closest("#paper-section");
    expect(paper).toBeInTheDocument();
    expect(paper?.classList.contains("mantine-Paper-root")).toBe(true);

    expect(screen.getByRole("button", { name: "First" })).toHaveAttribute("aria-controls", "plain-section");
    expect(screen.getByRole("button", { name: "Second" })).toHaveAttribute("aria-controls", "paper-section");
  });

  test("count badge is absent at 0 and present at 2; extra renders beside it", () => {
    const { rerender } = renderWithProviders(<Host secondCount={0} />);
    const second = screen.getByRole("button", { name: "Second" });
    expect(second.textContent).toBe("Second");

    rerender(<Host secondCount={2} secondExtra={<span>extra badge</span>} />);
    expect(screen.getByText("2")).toBeInTheDocument();
    expect(screen.getByText("extra badge")).toBeInTheDocument();
  });
});
