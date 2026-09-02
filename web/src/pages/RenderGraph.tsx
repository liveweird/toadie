import { useEffect, useMemo, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useNavigate } from "react-router-dom";
import {
  Alert,
  Button,
  Chip,
  Group,
  Paper,
  Popover,
  SegmentedControl,
  Stack,
  Text,
  useComputedColorScheme,
} from "@mantine/core";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import {
  applyNodeChanges,
  Background,
  Controls,
  ReactFlow,
  useEdgesState,
  useNodesState,
  type Edge,
  type NodeChange,
  type ReactFlowInstance,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import { IconInfoCircle, IconTopologyStar3 } from "@tabler/icons-react";
import { getCatalogGraph } from "../api/catalogFiles";
import { getUserId } from "../api/session";
import { getGraphLayout, setGraphLayout } from "../api/users";
import CatalogGraphNode from "../components/CatalogGraphNode";
import CatalogToolbar from "../components/CatalogToolbar";
import EmptyState from "../components/EmptyState";
import NamespaceFrames from "../components/NamespaceFrames";
import {
  applyManualPositions,
  COLLAPSED_FACE_STYLE,
  filterGraph,
  FOLDED_EDGE_STYLE,
  layoutGraph,
  namespaceFrames,
  RELATION_FAMILIES,
  STATUS_STYLE,
  type GraphPositions,
  type LaidOutNode,
  type RelationFamily,
} from "../utils/graphLayout";
import { foldGraph } from "../utils/graphFold";
import { buildHierarchy } from "../utils/hierarchy";
import { useCatalogFileFilterState } from "../hooks/useCatalogFileFilterState";
import { loadErrorMessage } from "../utils/saveError";
import { editCatalogFilePath } from "../utils/catalogFileLinks";
import LoadingBlock from "../components/LoadingBlock";
import PageHeader from "../components/PageHeader";
import classes from "../theme.module.css";

const NODE_TYPES = { catalog: CatalogGraphNode };

// Swatches borrow the node's own borders and shadows (STATUS_STYLE, COLLAPSED_FACE_STYLE),
// so the legend cannot lie.
const LEGEND: { key: "stored" | "missing" | "collapsed"; style: React.CSSProperties }[] = [
  { key: "stored", style: { border: STATUS_STYLE.STORED.border } },
  { key: "missing", style: { border: STATUS_STYLE.MISSING.border } },
  { key: "collapsed", style: { border: STATUS_STYLE.STORED.border, ...COLLAPSED_FACE_STYLE.STORED } },
];

type LayoutMode = "auto" | "manual";
/** The per-user layout document as the page holds it — every PUT sends the whole triple. */
type LayoutState = { mode: LayoutMode; positions: GraphPositions; collapsed: string[] };

/** Stable fallbacks — a fresh `{}`/`[]` in deps would retrigger the layout sync effect every render. */
const EMPTY_POSITIONS: GraphPositions = {};
const EMPTY_COLLAPSED: string[] = [];

/** Drag saves are debounced so a repositioning session is one PUT, not one per node. */
const LAYOUT_SAVE_DEBOUNCE_MS = 600;

export default function RenderGraph() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const colorScheme = useComputedColorScheme("light");
  // The Files list's full filter set (per-view persisted under renderGraph.filter.*).
  const filters = useCatalogFileFilterState("renderGraph");
  // Deliberately not persisted: all relations on is the right starting view.
  const [enabled, setEnabled] = useState<RelationFamily[]>([...RELATION_FAMILIES]);

  const { data, isLoading, isError, error } = useQuery({
    // Under the "catalogFiles" prefix so every catalog mutation's invalidation refreshes it.
    queryKey: ["catalogFiles", "graph", filters.values],
    queryFn: () => getCatalogGraph(filters.values),
    placeholderData: keepPreviousData,
    // Every kind pill off = show nothing — never fetch (the API can't say match-nothing).
    enabled: !filters.noKinds,
  });

  // The per-user layout document (V19 + V24): server truth until the first local
  // interaction, then `local` wins — every mutation writes local state AND fire-and-forget
  // PUTs the FULL merged document (wholesale replace on the wire, merge in the client —
  // positions and collapsed ids of filtered-out nodes must survive a save).
  const userId = getUserId();
  const layoutQuery = useQuery({
    queryKey: ["graphLayout", userId],
    queryFn: () => getGraphLayout(userId!),
    enabled: userId != null,
  });
  const [local, setLocal] = useState<LayoutState | null>(null);
  const mode: LayoutMode = local?.mode ?? (layoutQuery.data?.mode === "manual" ? "manual" : "auto");
  const positions: GraphPositions = local?.positions ?? layoutQuery.data?.positions ?? EMPTY_POSITIONS;
  const collapsed: string[] = local?.collapsed ?? layoutQuery.data?.collapsed ?? EMPTY_COLLAPSED;
  // The current triple, readable from callbacks baked into node data without making the
  // dagre memo below depend on positions (which change on every drag stop). Synced in an
  // effect — never during render — which lands before any click could read it.
  const layoutRef = useRef<LayoutState>({ mode, positions, collapsed });
  useEffect(() => {
    layoutRef.current = { mode, positions, collapsed };
  }, [mode, positions, collapsed]);

  const saveTimer = useRef<number | undefined>(undefined);
  function persistLayout(next: LayoutState, debounce: boolean) {
    setLocal(next);
    if (userId == null) return;
    const put = () =>
      setGraphLayout(userId, next).catch((err: unknown) => {
        // Fire-and-forget (the LanguageSwitcher precedent): the canvas already moved.
        console.error("Failed to save the graph layout", err);
      });
    window.clearTimeout(saveTimer.current);
    if (debounce) saveTimer.current = window.setTimeout(put, LAYOUT_SAVE_DEBOUNCE_MS);
    else put();
  }

  // A fold toggle persists at once, like a mode switch — a collapse is a deliberate act, not
  // a gesture to coalesce. Reached through a ref so the node data's callbacks stay stable.
  const toggleRef = useRef<(id: string) => void>(() => {});
  useEffect(() => {
    toggleRef.current = (id: string) => {
      const current = layoutRef.current;
      const next = current.collapsed.includes(id)
        ? current.collapsed.filter((entry) => entry !== id)
        : [...current.collapsed, id];
      persistLayout({ ...current, collapsed: next }, false);
    };
  });

  const noKinds = filters.noKinds;
  // Containment for the fold is the Hierarchy's, over the FULL payload — never the
  // chip-filtered graph, so a System stays collapsible with "Part of system" switched off.
  const forest = useMemo(() => (data && !noKinds ? buildHierarchy(data) : []), [data, noKinds]);
  const baseLayout = useMemo(() => {
    if (!data || noKinds) return { nodes: [] as LaidOutNode[], edges: [] as Edge[], anyCollapsed: false };
    // Chips first, fold second: a MISSING child a chip pruned is neither counted nor hidden.
    const folded = foldGraph(filterGraph(data, enabled), forest, new Set(collapsed));
    const laidOut = layoutGraph(folded);
    const nodes = laidOut.nodes.map((n) => {
      const info = folded.info.get(n.id);
      return info ? { ...n, data: { ...n.data, fold: { ...info, onToggle: () => toggleRef.current(n.id) } } } : n;
    });
    const anyCollapsed = [...folded.info.values()].some((info) => info.collapsed);
    return { nodes, edges: laidOut.edges, anyCollapsed };
  }, [data, enabled, noKinds, forest, collapsed]);

  const [nodes, setNodes] = useNodesState<LaidOutNode>([]);
  const [edges, setEdges] = useEdgesState<Edge>([]);

  // Rebuild the canvas only when the graph structure (baseLayout), the layout mode, or the
  // SAVED positions change — never on every drag frame (mid-drag movement lives inside
  // useNodesState via applyNodeChanges; rebuilding here mid-gesture was the flicker).
  useEffect(() => {
    setNodes(mode === "manual" ? applyManualPositions(baseLayout.nodes, positions) : baseLayout.nodes);
    setEdges(baseLayout.edges);
  }, [mode, baseLayout, positions, setNodes, setEdges]);

  // Refit the viewport when the node SET changes (filters, pills, relation chips): the
  // `fitView` prop fires only at init, so a later filter would leave the new layout under
  // the old graph's pan/zoom — nodes off-canvas. Keyed by the sorted ids, NOT positions,
  // so drags, mode switches, and Reset never yank the viewport around.
  const [rfInstance, setRfInstance] = useState<ReactFlowInstance<LaidOutNode, Edge> | null>(null);
  const structureKey = useMemo(
    () => baseLayout.nodes.map((n) => n.id).sort((a, b) => a.localeCompare(b)).join("|"),
    [baseLayout.nodes],
  );
  useEffect(() => {
    void rfInstance?.fitView();
  }, [rfInstance, structureKey]);

  // Frames come off the LIVE node array, not off baseLayout: mid-drag movement lands in
  // `nodes` through applyNodeChanges, so a dragged node stretches its namespace's box as it
  // moves, which is the whole of Manual mode's re-fitting.
  const frames = useMemo(() => namespaceFrames(nodes), [nodes]);

  // Live drag: applyNodeChanges keeps the gesture fluent — mid-drag frames never touch
  // `positions` (writing them re-ran dagre and wholesale-replaced the node array
  // mid-gesture, blanking the canvas). Only a drag end persists, accumulated across the
  // whole batch: a multi-select drag ends several nodes in ONE changes array.
  function onNodesChange(changes: NodeChange<LaidOutNode>[]) {
    setNodes((current) => applyNodeChanges(changes, current));
    if (mode !== "manual") return;
    let next = positions;
    let dragEnded = false;
    for (const change of changes) {
      if (change.type !== "position") continue;
      if (change.position) next = { ...next, [change.id]: { x: change.position.x, y: change.position.y } };
      if (change.dragging === false) dragEnded = true;
    }
    if (dragEnded) persistLayout({ mode, positions: next, collapsed }, true);
  }

  // A drag on a stored node must never navigate — React Flow can fire onNodeClick after a
  // drag, so the ref swallows the click that belongs to a drag gesture.
  const draggedRef = useRef(false);
  function onNodeDragStart() {
    draggedRef.current = true;
  }
  function onNodeDragStop() {
    window.setTimeout(() => {
      draggedRef.current = false;
    }, 0);
  }

  function onNodeClick(_event: React.MouseEvent, node: LaidOutNode) {
    if (draggedRef.current) return;
    const fileId = node.data.apiNode.fileId;
    if (fileId != null) navigate(editCatalogFilePath(fileId));
  }

  return (
    <Stack gap="md" className={classes.fillPage}>
      <PageHeader
        title={t("render.title")}
        toolbar={
          <CatalogToolbar viewKey="renderGraph" filters={filters}>
            {/* Which RELATIONSHIP families draw edges — the group's aria-label names it. */}
            <Chip.Group
              multiple
              value={enabled}
              onChange={(values) => setEnabled(values as RelationFamily[])}
            >
              <Group gap={6} role="group" aria-label={t("render.relationsLabel")}>
                {RELATION_FAMILIES.map((family) => (
                  <Chip key={family} value={family} size="xs">
                    {t(`render.relation.${family}`)}
                  </Chip>
                ))}
              </Group>
            </Chip.Group>
            <Group gap="xs" ml="auto" wrap="wrap">
              <SegmentedControl
                size="xs"
                value={mode}
                onChange={(value) =>
                  persistLayout({ mode: value as LayoutMode, positions, collapsed }, false)
                }
                data={[
                  { value: "auto", label: t("render.layoutMode.auto") },
                  { value: "manual", label: t("render.layoutMode.manual") },
                ]}
                aria-label={t("render.layoutMode.label")}
              />
              {/* Reset layout clears POSITIONS only — the fold is its own dimension with its
                  own reset, so straightening a dragged canvas never unfolds it. */}
              {mode === "manual" && (
                <Button
                  variant="default"
                  size="xs"
                  onClick={() => persistLayout({ mode: "manual", positions: {}, collapsed }, false)}
                >
                  {t("render.resetLayout")}
                </Button>
              )}
              {/* Expand all clears the WHOLE list, stale ids of filtered-out nodes included,
                  so nothing resurfaces collapsed later. Shown only while a drawn node is
                  collapsed — a list holding nothing but stale ids has no visible state. */}
              {baseLayout.anyCollapsed && (
                <Button
                  variant="default"
                  size="xs"
                  onClick={() => persistLayout({ mode, positions, collapsed: [] }, false)}
                >
                  {t("render.expandAll")}
                </Button>
              )}
              <Popover position="bottom-end" shadow="md" withArrow>
                <Popover.Target>
                  <Button variant="subtle" size="xs" color="gray" leftSection={<IconInfoCircle size={14} />}>
                    {t("render.legend.title")}
                  </Button>
                </Popover.Target>
                <Popover.Dropdown>
                  <Stack gap="xs">
                    {LEGEND.map(({ key, style }) => (
                      <Group key={key} gap={8} wrap="nowrap">
                        <span
                          style={{ width: 14, height: 14, borderRadius: 4, display: "inline-block", flexShrink: 0, ...style }}
                        />
                        <Text size="xs">{t(`render.legend.${key}`)}</Text>
                      </Group>
                    ))}
                    <Group gap={8} wrap="nowrap">
                      {/* The folded-edge swatch draws with the edge's own dash pattern. */}
                      <svg width={14} height={8} aria-hidden="true" style={{ display: "inline-block", flexShrink: 0 }}>
                        <line x1={0} y1={4} x2={14} y2={4} stroke="currentColor" strokeWidth={1.5} style={FOLDED_EDGE_STYLE} />
                      </svg>
                      <Text size="xs">{t("render.legend.folded")}</Text>
                    </Group>
                  </Stack>
                </Popover.Dropdown>
              </Popover>
            </Group>
          </CatalogToolbar>
        }
      />

      {isError && (
        <Alert color="red" variant="light" title={t("render.loadFailed")}>
          {loadErrorMessage(error, t)}
        </Alert>
      )}

      {isLoading && !data ? (
        <LoadingBlock />
      ) : !isLoading && !isError && nodes.length === 0 ? (
        <EmptyState
          icon={IconTopologyStar3}
          label={t("render.empty")}
        />
      ) : (
        <Paper withBorder radius="md" className={classes.fillPageCanvas}>
          <ReactFlow
            nodes={nodes}
            edges={edges}
            nodeTypes={NODE_TYPES}
            onInit={setRfInstance}
            onNodesChange={onNodesChange}
            onNodeDragStart={onNodeDragStart}
            onNodeDragStop={onNodeDragStop}
            onNodeClick={onNodeClick}
            colorMode={colorScheme}
            fitView
            // React Flow's default floor is 0.5, which fitView silently clamps to — a large
            // workspace (or a namespace-clustered one, which dagre lays out taller) then
            // spills off the canvas with no way to see it whole.
            minZoom={0.2}
            nodesDraggable={mode === "manual"}
            nodesConnectable={false}
            edgesFocusable={false}
            proOptions={{ hideAttribution: false }}
          >
            <NamespaceFrames frames={frames} />
            <Background />
            <Controls showInteractive={false} />
          </ReactFlow>
        </Paper>
      )}
    </Stack>
  );
}
