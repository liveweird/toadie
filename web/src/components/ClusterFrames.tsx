import { ViewportPortal } from "@xyflow/react";
import type { ClusterFrame } from "../utils/graphLayout";
import classes from "../theme.module.css";

/**
 * The regions drawn behind the nodes, one per cluster (a namespace on the Render page, a
 * blueprint on the Entity graph page — v1.25.0's rename from `NamespaceFrames.tsx` once the
 * shaping in `utils/graphLayout.ts` generalized to `clusterFrames`).
 *
 * They live in React Flow's VIEWPORT PORTAL rather than being nodes themselves — a frame is
 * decoration, not an entity: it must not be selectable, draggable, hit-testable, or counted
 * anywhere nodes are counted. The portal already sits inside the transformed viewport, so the
 * frames pan and zoom with the canvas for free, and `z-index: -1` puts them behind the nodes
 * and edges (the portal div is rendered last but carries no stacking context of its own).
 *
 * Making them React Flow PARENT nodes would have been the other route, and is the one to
 * avoid: a parent turns its members' positions parent-RELATIVE, which would silently
 * reinterpret every absolute position already stored in the per-user layout document.
 */
export default function ClusterFrames({ frames }: { frames: ClusterFrame[] }) {
  if (frames.length === 0) return null;
  return (
    <ViewportPortal>
      {frames.map((frame) => (
        <div
          key={frame.key}
          className={classes.namespaceFrame}
          style={{
            position: "absolute",
            transform: `translate(${frame.x}px, ${frame.y}px)`,
            width: frame.width,
            height: frame.height,
            zIndex: -1,
          }}
        >
          <span className={classes.namespaceFrameLabel}>{frame.label}</span>
        </div>
      ))}
    </ViewportPortal>
  );
}
