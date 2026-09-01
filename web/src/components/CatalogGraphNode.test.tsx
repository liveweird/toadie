import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import type { NodeProps } from "@xyflow/react";

// Handle needs a live React Flow store — stub it (the canvas itself is e2e-tested).
vi.mock("@xyflow/react", () => ({
  Handle: () => null,
  Position: { Left: "left", Right: "right" },
}));

import CatalogGraphNode from "./CatalogGraphNode";
import type { GraphNode } from "../api/catalogFiles";
import type { LaidOutNode } from "../utils/graphLayout";
import { renderWithProviders, screen } from "../test/render";

function nodeProps(overrides: Partial<GraphNode> = {}): NodeProps<LaidOutNode> {
  return {
    data: {
      apiNode: {
        id: `component:default/x`,
        kind: "component",
        namespace: "default",
        name: "svc-x",
        title: null,
        type: "service",
        tags: [],
        fileId: 7,
        status: "STORED",
        ...overrides,
      },
    },
  } as unknown as NodeProps<LaidOutNode>;
}

describe("CatalogGraphNode", () => {
  test.each([
    ["STORED", 7, "component"],
    ["MISSING", null, "component"],
    ["MISSING", null, "group"],
  ] as const)("renders a %s node with its kind badge", (status, fileId, kind) => {
    renderWithProviders(<CatalogGraphNode {...nodeProps({ status, fileId, kind })} />);
    expect(screen.getByText("svc-x")).toBeInTheDocument();
    expect(screen.getByText(kind)).toBeInTheDocument();
    expect(screen.getByLabelText("Open svc-x")).toBeInTheDocument();
  });

  test("the second line is spec.type, not the namespace", () => {
    renderWithProviders(<CatalogGraphNode {...nodeProps({ type: "library" })} />);
    expect(screen.getByText("library")).toBeInTheDocument();
    // The namespace moved into the tooltip — it must not spend a line on the node face.
    expect(screen.queryByText("default")).not.toBeInTheDocument();
  });

  test("a node without a type (a User, or a MISSING node) shows no second line", () => {
    // `type` absent is the User case and every MISSING node; the box keeps its
    // height, so dagre never sees the difference.
    renderWithProviders(<CatalogGraphNode {...nodeProps({ type: null, kind: "user" })} />);
    expect(screen.getByText("svc-x")).toBeInTheDocument();
    expect(screen.getByText("user")).toBeInTheDocument();
  });

  test("hovering the name opens a tooltip with namespace, title and tags", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <CatalogGraphNode
        {...nodeProps({ namespace: "external", title: "Service X", tags: ["java", "quarkus"] })}
      />,
    );

    await user.hover(screen.getByText("svc-x"));
    expect(await screen.findByText("external")).toBeInTheDocument();
    expect(screen.getByText("Service X")).toBeInTheDocument();
    expect(screen.getByText("java")).toBeInTheDocument();
    expect(screen.getByText("quarkus")).toBeInTheDocument();
  });

  test("the tooltip of a virtual node carries only what a virtual node has", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <CatalogGraphNode {...nodeProps({ status: "MISSING", fileId: null, title: null, type: null })} />,
    );

    await user.hover(screen.getByText("svc-x"));
    expect(await screen.findByText("default")).toBeInTheDocument();
    // No document behind it, so no title and no tag badges to render.
    expect(screen.queryByText("Service X")).not.toBeInTheDocument();
  });

  test("a node whose payload omits tags renders the tooltip without a tag row", async () => {
    // `tags` is optional on the wire (the spec doesn't require it) — the component must not
    // assume the array is there.
    const user = userEvent.setup();
    renderWithProviders(<CatalogGraphNode {...nodeProps({ tags: undefined, title: "Service X" })} />);

    await user.hover(screen.getByText("svc-x"));
    expect(await screen.findByText("Service X")).toBeInTheDocument();
  });

  test("Enter and Space on a stored node fire a click, so the page can navigate", async () => {
    const user = userEvent.setup();
    const onClick = vi.fn();
    // The handler re-dispatches as a DOM click, which bubbles to React Flow's node wrapper —
    // stand in for that wrapper here.
    renderWithProviders(
      <div onClick={onClick}>
        <CatalogGraphNode {...nodeProps()} />
      </div>,
    );

    screen.getByLabelText("Open svc-x").focus();
    await user.keyboard("{Enter}");
    expect(onClick).toHaveBeenCalledTimes(1);
    await user.keyboard(" ");
    expect(onClick).toHaveBeenCalledTimes(2);
  });

  test("a virtual node is not a keyboard target — there is nothing to open", () => {
    renderWithProviders(<CatalogGraphNode {...nodeProps({ status: "MISSING", fileId: null })} />);
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });
});
