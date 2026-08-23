import { describe, expect, test, vi } from "vitest";
import type { NodeProps } from "@xyflow/react";

// Handle needs a live React Flow store — stub it (the canvas itself is e2e-tested).
vi.mock("@xyflow/react", () => ({
  Handle: () => null,
  Position: { Left: "left", Right: "right" },
}));

import CatalogGraphNode from "./CatalogGraphNode";
import type { LaidOutNode } from "../utils/graphLayout";
import { renderWithProviders, screen } from "../test/render";

function nodeProps(status: "STORED" | "MISSING" | "EXTERNAL", fileId: number | null): NodeProps<LaidOutNode> {
  return {
    data: {
      apiNode: {
        id: `component:default/x`,
        kind: status === "EXTERNAL" ? "group" : "component",
        namespace: "default",
        name: "svc-x",
        title: null,
        fileId,
        status,
      },
    },
  } as unknown as NodeProps<LaidOutNode>;
}

describe("CatalogGraphNode", () => {
  test.each([
    ["STORED", 7, "component"],
    ["MISSING", null, "component"],
    ["EXTERNAL", null, "group"],
  ] as const)("renders a %s node with its kind badge", (status, fileId, kind) => {
    renderWithProviders(<CatalogGraphNode {...nodeProps(status, fileId)} />);
    expect(screen.getByText("svc-x")).toBeInTheDocument();
    expect(screen.getByText("default")).toBeInTheDocument();
    expect(screen.getByText(kind)).toBeInTheDocument();
    expect(screen.getByLabelText("Open svc-x")).toBeInTheDocument();
  });
});
