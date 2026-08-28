import { memo } from "react";
import { Badge, Text } from "@mantine/core";
import { Handle, Position, type NodeProps } from "@xyflow/react";
import { useTranslation } from "react-i18next";
import type { LaidOutNode } from "../utils/graphLayout";
import { GRAPH_NODE_HEIGHT, GRAPH_NODE_WIDTH } from "../utils/graphLayout";

// Status → border/background via Mantine CSS vars only, so light/dark both work untouched.
const STATUS_STYLE: Record<string, React.CSSProperties> = {
  STORED: {
    border: "1.5px solid var(--mantine-color-toadie-7)",
    background: "var(--mantine-color-body)",
  },
  MISSING: {
    border: "1.5px dashed var(--mantine-color-red-6)",
    background: "var(--mantine-color-body)",
  },
  EXTERNAL: {
    border: "1.5px dashed var(--mantine-color-gray-5)",
    background: "var(--mantine-color-default-hover)",
  },
};

/** The Render page's node: name + namespace, kind badge, styled by resolution status. */
function CatalogGraphNode({ data }: NodeProps<LaidOutNode>) {
  const { t } = useTranslation();
  const node = data.apiNode;
  // A stored node navigates to its file, so it must be a real keyboard target: button role,
  // focusable, and Enter/Space re-dispatched as a DOM click — which bubbles to React Flow's
  // node wrapper and fires the page's onNodeClick. Virtual (missing) nodes stay plain.
  const interactive = node.fileId != null;
  return (
    <div
      role={interactive ? "button" : undefined}
      tabIndex={interactive ? 0 : undefined}
      onKeyDown={
        interactive
          ? (event) => {
              if (event.key === "Enter" || event.key === " ") {
                event.preventDefault();
                event.currentTarget.click();
              }
            }
          : undefined
      }
      style={{
        position: "relative",
        width: GRAPH_NODE_WIDTH,
        height: GRAPH_NODE_HEIGHT,
        borderRadius: "var(--mantine-radius-md)",
        padding: "8px 12px",
        overflow: "hidden",
        cursor: interactive ? "pointer" : "default",
        ...STATUS_STYLE[node.status],
      }}
      aria-label={t("render.nodeAria", { name: node.name })}
    >
      <Handle type="target" position={Position.Left} />
      <Text size="sm" fw={600} truncate>
        {node.name}
      </Text>
      <Text size="xs" c="dimmed" truncate component="span" display="block">
        {node.namespace}
      </Text>
      <Badge
        size="xs"
        variant={node.status === "STORED" ? "light" : "outline"}
        color={node.status === "MISSING" ? "red" : node.status === "EXTERNAL" ? "gray" : undefined}
        style={{ position: "absolute", top: 8, right: 8 }}
      >
        {node.kind}
      </Badge>
      <Handle type="source" position={Position.Right} />
    </div>
  );
}

export default memo(CatalogGraphNode);
