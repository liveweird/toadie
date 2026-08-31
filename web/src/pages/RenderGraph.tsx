import { useEffect, useMemo, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useNavigate } from "react-router-dom";
import {
  Alert,
  Button,
  Center,
  Chip,
  Group,
  Loader,
  Paper,
  SegmentedControl,
  Stack,
  Text,
  Title,
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
import { IconTopologyStar3 } from "@tabler/icons-react";
import { getCatalogGraph } from "../api/catalogFiles";
import { getUserId } from "../api/session";
import { getGraphLayout, setGraphLayout } from "../api/users";
import CatalogFileFilterControls from "../components/CatalogFileFilterControls";
import CatalogGraphNode from "../components/CatalogGraphNode";
import CatalogKindPills from "../components/CatalogKindPills";
import EmptyState from "../components/EmptyState";
import FilterPanel from "../components/FilterPanel";
import LensPicker from "../components/LensPicker";
import NamespaceFrames from "../components/NamespaceFrames";
import {
  applyManualPositions,
  filterGraph,
  layoutGraph,
  namespaceFrames,
  RELATION_FAMILIES,
  STATUS_STYLE,
  type GraphPositions,
  type LaidOutNode,
  type RelationFamily,
} from "../utils/graphLayout";
import { useCatalogFileFilterState } from "../hooks/useCatalogFileFilterState";
import { loadErrorMessage } from "../utils/saveError";
import { editCatalogFilePath } from "../utils/catalogFileLinks";

const NODE_TYPES = { catalog: CatalogGraphNode };

// Swatches borrow the node's own borders (STATUS_STYLE), so the legend cannot lie.
const LEGEND: { key: "stored" | "missing" | "external"; style: React.CSSProperties }[] = [
  { key: "stored", style: { border: STATUS_STYLE.STORED.border } },
  { key: "missing", style: { border: STATUS_STYLE.MISSING.border } },
  { key: "external", style: { border: STATUS_STYLE.EXTERNAL.border } },
];

type LayoutMode = "auto" | "manual";
type LayoutState = { mode: LayoutMode; positions: GraphPositions };

/** Stable fallback — a fresh `{}` in deps would retrigger the layout sync effect every render. */
const EMPTY_POSITIONS: GraphPositions = {};

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

  // The per-user layout document (V19): server truth until the first local interaction,
  // then `local` wins — every mutation writes local state AND fire-and-forget PUTs the
  // FULL merged document (wholesale replace on the wire, merge in the client — positions
  // of filtered-out nodes must survive a save).
  const userId = getUserId();
  const layoutQuery = useQuery({
    queryKey: ["graphLayout", userId],
    queryFn: () => getGraphLayout(userId!),
    enabled: userId != null,
  });
  const [local, setLocal] = useState<LayoutState | null>(null);
  const mode: LayoutMode = local?.mode ?? (layoutQuery.data?.mode === "manual" ? "manual" : "auto");
  const positions: GraphPositions = local?.positions ?? layoutQuery.data?.positions ?? EMPTY_POSITIONS;

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

  const noKinds = filters.noKinds;
  const baseLayout = useMemo(() => {
    if (!data || noKinds) return { nodes: [] as LaidOutNode[], edges: [] as Edge[] };
    return layoutGraph(filterGraph(data, enabled));
  }, [data, enabled, noKinds]);

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
    if (dragEnded) persistLayout({ mode, positions: next }, true);
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
    <Stack gap="md" h="100%">
      <Title order={2}>{t("render.title")}</Title>

      <FilterPanel
        activeFilterCount={filters.activeFilterCount}
        storageKey="renderGraph"
        aside={<LensPicker values={filters.values} controls={filters.controls} />}
      >
        <CatalogFileFilterControls controls={filters.controls} />
      </FilterPanel>

      <CatalogKindPills kinds={filters.controls.kinds} setKinds={filters.controls.setKinds} />

      {/* Which RELATIONSHIP families draw edges — captioned so the pills never read as
          entity kinds (the Kind filter pills live in the panel above). */}
      <Stack gap={4}>
        <Text size="sm" fw={500} component="label">
          {t("render.relationsLabel")}
        </Text>
        <Chip.Group
          multiple
          value={enabled}
          onChange={(values) => setEnabled(values as RelationFamily[])}
        >
          <Group gap="xs" role="group" aria-label={t("render.relationsLabel")}>
            {RELATION_FAMILIES.map((family) => (
              <Chip key={family} value={family} size="xs">
                {t(`render.relation.${family}`)}
              </Chip>
            ))}
          </Group>
        </Chip.Group>
      </Stack>

      <Group gap="sm">
        <Text size="sm" fw={500} component="label">
          {t("render.layoutMode.label")}
        </Text>
        <SegmentedControl
          size="xs"
          value={mode}
          onChange={(value) => persistLayout({ mode: value as LayoutMode, positions }, false)}
          data={[
            { value: "auto", label: t("render.layoutMode.auto") },
            { value: "manual", label: t("render.layoutMode.manual") },
          ]}
          aria-label={t("render.layoutMode.label")}
        />
        {mode === "manual" && (
          <Button
            variant="default"
            size="xs"
            onClick={() => persistLayout({ mode: "manual", positions: {} }, false)}
          >
            {t("render.resetLayout")}
          </Button>
        )}
      </Group>

      <Group gap="lg">
        {LEGEND.map(({ key, style }) => (
          <Group key={key} gap={6}>
            <span
              style={{ width: 14, height: 14, borderRadius: 4, display: "inline-block", ...style }}
            />
            <Text size="xs" c="dimmed">
              {t(`render.legend.${key}`)}
            </Text>
          </Group>
        ))}
      </Group>

      {isError && (
        <Alert color="red" variant="light" title={t("render.loadFailed")}>
          {loadErrorMessage(error, t)}
        </Alert>
      )}

      {isLoading && !data ? (
        <Center py="xl">
          <Loader size="sm" />
        </Center>
      ) : !isLoading && !isError && nodes.length === 0 ? (
        <EmptyState
          icon={<IconTopologyStar3 size={32} stroke={1.2} color="var(--mantine-color-dimmed)" />}
          label={t("render.empty")}
        />
      ) : (
        <Paper withBorder radius="md" style={{ height: "calc(100vh - 320px)", minHeight: 360 }}>
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
