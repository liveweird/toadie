import { memo } from "react";
import { Badge, Group, Stack, Text, Tooltip } from "@mantine/core";
import { Handle, Position, type NodeProps } from "@xyflow/react";
import { useTranslation } from "react-i18next";
import type { EntityGraphNode as EntityGraphNodeApi } from "../api/entities";
import type { LaidOutNode } from "../utils/graphLayout";
import { COLLAPSED_FACE_STYLE, GRAPH_NODE_HEIGHT, GRAPH_NODE_WIDTH, STATUS_STYLE } from "../utils/graphLayout";
import EntityFindingsBadge from "./EntityFindingsBadge";
import GraphFoldToggle from "./GraphFoldToggle";

/** What the fixed-width node cannot show: the identifier, blueprint, and icon. */
function NodeTooltipLabel({ node }: { node: EntityGraphNodeApi }) {
  return (
    <Stack gap={4}>
      <Text size="sm" fw={600}>
        {node.title}
      </Text>
      <Text size="xs" ff="monospace">
        {node.identifier}
      </Text>
      <Text size="xs" c="dimmed">
        {node.blueprintTitle}
      </Text>
      {node.icon && (
        <Text size="xs" c="dimmed">
          {node.icon}
        </Text>
      )}
    </Stack>
  );
}

/**
 * The Entity graph page's node (v1.25.0): title (truncated, with the tooltip above), the
 * identifier in monospace as ONE intact text node (the e2e locators' `getByText(identifier,
 * { exact: true })`), a neutral blueprint badge, and `EntityFindingsBadge`. Every entity node
 * navigates to its editor (`editEntityPath`), so — unlike the catalog graph's MISSING nodes —
 * there is no non-interactive variant: every drawn entity node is `role="button"`. The fold
 * pill (`GraphFoldToggle`, shared with `CatalogGraphNode`) is a SIBLING of the face, never a
 * child — the same axe `nested-interactive` rule.
 */
function EntityGraphNode({ data }: NodeProps<LaidOutNode<EntityGraphNodeApi>>) {
  const { t } = useTranslation();
  const node = data.apiNode;
  const fold = data.fold;
  return (
    <div style={{ position: "relative", width: GRAPH_NODE_WIDTH, height: GRAPH_NODE_HEIGHT }}>
      <Handle type="target" position={Position.Left} />
      <div
        role="button"
        tabIndex={0}
        onKeyDown={(event) => {
          if (event.key === "Enter" || event.key === " ") {
            event.preventDefault();
            event.currentTarget.click();
          }
        }}
        style={{
          width: "100%",
          height: "100%",
          borderRadius: "var(--mantine-radius-md)",
          padding: fold ? "8px 36px 8px 12px" : "8px 12px",
          overflow: "hidden",
          cursor: "pointer",
          ...STATUS_STYLE.STORED,
          ...(fold?.collapsed ? COLLAPSED_FACE_STYLE.STORED : {}),
        }}
        aria-label={t("entityGraph.nodeAria", { name: node.title })}
      >
        <Tooltip label={<NodeTooltipLabel node={node} />} multiline w={240} withArrow position="top">
          <Text size="sm" fw={600} truncate>
            {node.title}
          </Text>
        </Tooltip>
        <Group gap={6} wrap="nowrap" align="center" mt={2}>
          <Text size="xs" ff="monospace" truncate style={{ flex: 1, minWidth: 0 }}>
            {node.identifier}
          </Text>
          <Badge variant="light" color="gray" size="xs" style={{ flex: "0 0 auto" }}>
            {node.blueprintTitle}
          </Badge>
          <EntityFindingsBadge findings={node.findings} />
        </Group>
      </div>
      {fold && <GraphFoldToggle name={node.title} fold={fold} />}
      <Handle type="source" position={Position.Right} />
    </div>
  );
}

export default memo(EntityGraphNode);
