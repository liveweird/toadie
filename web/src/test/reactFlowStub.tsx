/* eslint-disable react-refresh/only-export-components */
// -- test scaffolding: mocked helpers/components share one file (the test/render.tsx idiom);
// fast-refresh is irrelevant under vitest.
import { useState } from "react";

/**
 * The `@xyflow/react` mock shared by the Render, Entity graph, and Entity hierarchy page
 * tests (extracted from `pages/RenderGraph.test.tsx` in v1.25.0, where it originated). React
 * Flow needs real DOM measurement (ResizeObserver, bounding boxes) that happy-dom can't give,
 * so the canvas is stubbed to a list of node buttons; the real rendering is e2e's job. The
 * pure shaping (`filterGraph`/`layoutGraph`/`foldGraph`/`entityGraph`) is covered by their own
 * unit tests. The stub ignores props it doesn't render — every prop a page relies on must be
 * surfaced here explicitly (draggability as a data attribute, positions as spans, drags as
 * buttons that replay React Flow's drag event sequence, click-after-drag included).
 *
 * A node's face text is `name ?? identifier` and its bracketed second field is
 * `status ?? blueprint` — the catalog graph's `GraphNode` carries the first pair, the entity
 * graph's `EntityGraphNode` the second, so ONE stub renders both without changing the
 * catalog Render page's existing text-based assertions.
 *
 * Usage: `vi.mock("@xyflow/react", () => import("../test/reactFlowStub"));` — the dynamic
 * import's resolved module IS the mock (its named exports match what `@xyflow/react` callers
 * use), so no wrapper object is needed at the call site.
 */

type StubApiNode = {
  name?: string;
  status?: string;
  identifier?: string;
  blueprint?: string;
};

type StubNodeData = {
  apiNode: StubApiNode;
  fold?: {
    collapsed: boolean;
    descendants: number;
    disabled?: boolean;
    onToggle: () => void;
  };
};

type StubNode = { id: string; position: { x: number; y: number }; data: StubNodeData };

function faceLabel(apiNode: StubApiNode): string {
  const primary = apiNode.name ?? apiNode.identifier ?? "";
  const secondary = apiNode.status ?? apiNode.blueprint ?? "";
  return `${primary} [${secondary}]`;
}

export function applyNodeChanges<T extends { id: string; position?: { x: number; y: number } }>(
  changes: { type: string; id?: string; position?: { x: number; y: number } }[],
  nodes: T[],
): T[] {
  let next = nodes;
  for (const change of changes) {
    if (change.type !== "position" || !change.id || !change.position) continue;
    next = next.map((n) => (n.id === change.id ? { ...n, position: change.position! } : n));
  }
  return next;
}

export function useNodesState<T>(initial: T[]) {
  const [nodes, setNodes] = useState(initial);
  return [nodes, setNodes, () => {}] as const;
}

export function useEdgesState<T>(initial: T[]) {
  const [edges, setEdges] = useState(initial);
  return [edges, setEdges, () => {}] as const;
}

export function ReactFlow({
  nodes,
  edges,
  nodesDraggable,
  onNodesChange,
  onNodeDragStart,
  onNodeDragStop,
  onNodeClick,
  children,
}: {
  nodes: StubNode[];
  edges: { id: string; label?: unknown; style?: { strokeDasharray?: unknown } }[];
  nodesDraggable?: boolean;
  onNodesChange?: (changes: unknown[]) => void;
  onNodeDragStart?: () => void;
  onNodeDragStop?: () => void;
  onNodeClick?: (event: unknown, node: StubNode) => void;
  children?: React.ReactNode;
}) {
  return (
    <div data-testid="flow" data-draggable={String(nodesDraggable ?? true)}>
      {/* The canvas overlays (cluster frames, Background, Controls) are children. */}
      {children}
      {/* Edges by id with their label — the fold's re-attribution is asserted on these; the
          dash attribute surfaces an edge's `style.strokeDasharray` (folded/ownership edges). */}
      {edges.map((e) => (
        <span key={e.id} data-testid={`edge:${e.id}`} data-dash={String(e.style?.strokeDasharray ?? "")}>
          {String(e.label ?? "")}
        </span>
      ))}
      {nodes.map((n) => (
        <div key={n.id}>
          <button type="button" onClick={(e) => onNodeClick?.(e, n)}>
            {faceLabel(n.data.apiNode)}
          </button>
          {/* The custom node's fold toggle, as the page hands it over in node data. */}
          {n.data.fold && (
            <button
              type="button"
              aria-label={
                n.data.fold.collapsed
                  ? `Expand ${n.data.apiNode.name ?? n.data.apiNode.identifier} (${n.data.fold.descendants} hidden)`
                  : `Collapse ${n.data.apiNode.name ?? n.data.apiNode.identifier}`
              }
              disabled={n.data.fold.disabled}
              onClick={n.data.fold.onToggle}
            >
              fold
            </button>
          )}
          <span data-testid={`pos:${n.id}`}>{`${n.position.x},${n.position.y}`}</span>
          <button
            type="button"
            data-testid={`drag:${n.id}`}
            onClick={(e) => {
              onNodeDragStart?.();
              onNodesChange?.([
                { type: "position", id: n.id, position: { x: 111, y: 222 }, dragging: true },
              ]);
              onNodesChange?.([
                { type: "position", id: n.id, position: { x: 111, y: 222 }, dragging: false },
              ]);
              onNodeDragStop?.();
              // React Flow fires the click after a drag gesture too — the page must swallow it.
              onNodeClick?.(e, n);
            }}
          >
            drag {n.id}
          </button>
        </div>
      ))}
      {/* A multi-select drag ends SEVERAL nodes in ONE changes batch — the page must
          accumulate them into a single persisted map, not last-write-wins. */}
      <button
        type="button"
        data-testid="drag-multi"
        onClick={() => {
          onNodeDragStart?.();
          onNodesChange?.([
            { type: "position", id: nodes[0]?.id, position: { x: 11, y: 12 }, dragging: false },
            { type: "position", id: nodes[1]?.id, position: { x: 21, y: 22 }, dragging: false },
          ]);
          onNodeDragStop?.();
        }}
      >
        drag multi
      </button>
    </div>
  );
}

export function Background() {
  return null;
}

export function Controls() {
  return null;
}

export function Handle() {
  return null;
}

export const Position = { Left: "left", Right: "right" };

// The cluster frames render through the viewport portal; here it is just a passthrough so
// the frames show up as ordinary DOM and can be asserted on.
export function ViewportPortal({ children }: { children: React.ReactNode }) {
  return <div>{children}</div>;
}
