import { memo } from "react";
import { Badge, Group, Stack, Text, Tooltip } from "@mantine/core";
import { Handle, Position, type NodeProps } from "@xyflow/react";
import { useTranslation } from "react-i18next";
import KindTierDot from "./KindTierDot";
import type { LaidOutNode } from "../utils/graphLayout";
import { GRAPH_NODE_HEIGHT, GRAPH_NODE_WIDTH, STATUS_STYLE } from "../utils/graphLayout";
import type { GraphNode } from "../api/catalogFiles";


/**
 * What the fixed-width node cannot show: the namespace it lost to `spec.type`, the title, and
 * the tags. Virtual nodes have no document behind them, so they contribute name + namespace
 * only — the conditionals are the difference, not a fallback.
 */
function NodeTooltipLabel({ node }: { node: GraphNode }) {
  const tags = node.tags ?? [];
  return (
    <Stack gap={4}>
      <Text size="sm" fw={600}>
        {node.name}
      </Text>
      <Text size="xs" c="dimmed">
        {node.namespace}
      </Text>
      {node.title && <Text size="sm">{node.title}</Text>}
      {tags.length > 0 && (
        <Group gap={4}>
          {tags.map((tag) => (
            <Badge key={tag} variant="light" size="sm">
              {tag}
            </Badge>
          ))}
        </Group>
      )}
    </Stack>
  );
}

/** The Render page's node: name + spec.type, kind badge, styled by resolution status. */
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
      {/* The name owns the whole first line. The kind badge used to float over it
          (`position: absolute`), so long names ran underneath instead of ellipsizing; putting
          the badge on the SECOND line rather than beside the name is what keeps the name
          readable — a `2 COMPONENT` badge is over half the 200px box, which would leave room
          for about eight characters. Truncation stays CSS `text-overflow`, never a JS slice:
          the full name must remain one intact text node for the e2e locators. */}
      <Tooltip label={<NodeTooltipLabel node={node} />} multiline w={240} withArrow position="top">
        <Text size="sm" fw={600} truncate>
          {node.name}
        </Text>
      </Tooltip>
      {/* Kind, then spec.type — blank for a User (no type in its spec) and for virtual nodes
          (no document at all). The box keeps its height either way, so dagre never sees the
          difference. `minWidth: 0` is load-bearing on the type: without it a flex item refuses
          to shrink below its content width and `truncate` silently no-ops. */}
      <Group gap={6} wrap="nowrap" align="center" mt={2}>
        <Badge
          size="xs"
          variant={node.status === "STORED" ? "light" : "outline"}
          color={node.status === "MISSING" ? "red" : undefined}
          style={{ flex: "0 0 auto" }}
          leftSection={<KindTierDot kind={node.kind} />}
        >
          {node.kind}
        </Badge>
        <Text size="xs" c="dimmed" truncate style={{ flex: 1, minWidth: 0 }}>
          {node.type}
        </Text>
      </Group>
      <Handle type="source" position={Position.Right} />
    </div>
  );
}

export default memo(CatalogGraphNode);
