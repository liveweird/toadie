import { ActionIcon, Button } from "@mantine/core";
import { IconChevronDown, IconChevronRight } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";
import type { NodeFold } from "../utils/graphLayout";

/**
 * The fold toggle of a node with something beneath it (extracted from `CatalogGraphNode.tsx`
 * in v1.25.0 to share it with `EntityGraphNode.tsx`). Expanded: a quiet chevron. Collapsed: a
 * FILLED pill carrying the hidden count — with the face's stacked-cards shadow, the state is
 * unmistakable at any zoom. It is a SIBLING of the face, never a child: the face is a
 * `role="button"` (it opens the editor), and a control nested inside an interactive role is
 * an axe `nested-interactive` failure the e2e accessibility scan would catch on `/graph` and
 * `/entity-graph`. `nodrag nopan` are React Flow's own opt-outs (otherwise a Manual-mode click
 * would start a drag), and the click must not bubble — anything inside the node wrapper fires
 * the page's `onNodeClick`, which navigates.
 */
export default function GraphFoldToggle({ name, fold }: { name: string; fold: NodeFold }) {
  const { t } = useTranslation();
  const onClick = (event: React.MouseEvent) => {
    event.stopPropagation();
    fold.onToggle();
  };
  const style = { position: "absolute" as const, top: 4, right: 4 };
  return fold.collapsed ? (
    <Button
      className="nodrag nopan"
      size="compact-xs"
      variant="filled"
      leftSection={<IconChevronRight size={12} />}
      style={style}
      aria-label={t("common.fold.expandAria", { name, count: fold.descendants })}
      disabled={fold.disabled}
      onClick={onClick}
    >
      {fold.descendants}
    </Button>
  ) : (
    <ActionIcon
      className="nodrag nopan"
      size="sm"
      variant="subtle"
      color="gray"
      style={style}
      aria-label={t("common.fold.collapseAria", { name })}
      disabled={fold.disabled}
      onClick={onClick}
    >
      <IconChevronDown size={14} />
    </ActionIcon>
  );
}
