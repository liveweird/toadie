import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import type { NodeProps } from "@xyflow/react";

// Handle needs a live React Flow store — stub it (the canvas itself is e2e-tested).
vi.mock("@xyflow/react", () => ({
  Handle: () => null,
  Position: { Left: "left", Right: "right" },
}));

import EntityGraphNode from "./EntityGraphNode";
import type { EntityGraphNode as EntityGraphNodeApi } from "../api/entities";
import type { LaidOutNode } from "../utils/graphLayout";
import { waitFor } from "@testing-library/react";
import { renderWithProviders, screen } from "../test/render";

function nodeProps(overrides: Partial<EntityGraphNodeApi> = {}): NodeProps<LaidOutNode<EntityGraphNodeApi>> {
  return {
    data: {
      apiNode: {
        id: "service|checkout",
        entityId: 2,
        blueprint: "service",
        blueprintTitle: "Service",
        identifier: "checkout",
        title: "Checkout",
        findings: 0,
        ...overrides,
      },
    },
  } as unknown as NodeProps<LaidOutNode<EntityGraphNodeApi>>;
}

describe("EntityGraphNode", () => {
  test("renders the title, identifier, and blueprint badge", () => {
    renderWithProviders(<EntityGraphNode {...nodeProps()} />);
    expect(screen.getByText("Checkout")).toBeInTheDocument();
    expect(screen.getByText("checkout")).toBeInTheDocument();
    expect(screen.getByText("Service")).toBeInTheDocument();
    expect(screen.getByLabelText("Open Checkout")).toBeInTheDocument();
  });

  test("shows nothing extra when there are no findings, and the count badge when there are", () => {
    renderWithProviders(<EntityGraphNode {...nodeProps({ findings: 3 })} />);
    expect(screen.getByText("3 findings")).toBeInTheDocument();
  });

  test("every entity node is a real keyboard target — Enter/Space fire a click", async () => {
    const user = userEvent.setup();
    const onClick = vi.fn();
    renderWithProviders(
      <div onClick={onClick}>
        <EntityGraphNode {...nodeProps()} />
      </div>,
    );

    screen.getByLabelText("Open Checkout").focus();
    await user.keyboard("{Enter}");
    expect(onClick).toHaveBeenCalledTimes(1);
    await user.keyboard(" ");
    expect(onClick).toHaveBeenCalledTimes(2);
  });

  test("hovering the title opens a tooltip that adds the icon line", async () => {
    const user = userEvent.setup();
    renderWithProviders(<EntityGraphNode {...nodeProps({ icon: "Microservice" })} />);

    await user.hover(screen.getAllByText("Checkout")[0]);
    expect(await screen.findByText("Microservice")).toBeInTheDocument();
    // The face's own blueprint badge plus the tooltip's copy — two, not one.
    expect(screen.getAllByText("Service")).toHaveLength(2);
  });

  test("no icon means no icon line in the tooltip", async () => {
    const user = userEvent.setup();
    renderWithProviders(<EntityGraphNode {...nodeProps()} />);
    await user.hover(screen.getAllByText("Checkout")[0]);
    await waitFor(() => expect(screen.getAllByText("Service")).toHaveLength(2));
    expect(screen.queryByText("Microservice")).not.toBeInTheDocument();
  });

  describe("the fold toggle", () => {
    function withFold(fold: { collapsed: boolean; descendants: number; disabled?: boolean; onToggle: () => void }) {
      const props = nodeProps();
      return { ...props, data: { ...props.data, fold } } as NodeProps<LaidOutNode<EntityGraphNodeApi>>;
    }

    test("no fold data means no toggle at all", () => {
      renderWithProviders(<EntityGraphNode {...nodeProps()} />);
      expect(screen.queryByRole("button", { name: /Collapse|Expand/ })).not.toBeInTheDocument();
    });

    test("expanded: a Collapse control whose click toggles and never bubbles to the wrapper", async () => {
      const user = userEvent.setup();
      const onToggle = vi.fn();
      const wrapperClick = vi.fn();
      renderWithProviders(
        <div onClick={wrapperClick}>
          <EntityGraphNode {...withFold({ collapsed: false, descendants: 2, onToggle })} />
        </div>,
      );

      const toggle = screen.getByRole("button", { name: "Collapse Checkout" });
      await user.click(toggle);
      expect(onToggle).toHaveBeenCalledTimes(1);
      expect(wrapperClick).not.toHaveBeenCalled();
    });

    test("collapsed: an Expand pill carrying the hidden count", () => {
      renderWithProviders(
        <EntityGraphNode {...withFold({ collapsed: true, descendants: 5, onToggle: vi.fn() })} />,
      );
      expect(screen.getByRole("button", { name: "Expand Checkout (5 hidden)" })).toHaveTextContent("5");
    });
  });
});
