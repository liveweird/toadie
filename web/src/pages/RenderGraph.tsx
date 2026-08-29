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
  Background,
  Controls,
  ReactFlow,
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
import {
  applyManualPositions,
  filterGraph,
  layoutGraph,
  RELATION_FAMILIES,
  type GraphPositions,
  type LaidOutNode,
  type RelationFamily,
} from "../utils/graphLayout";
import { useCatalogFileFilterState } from "../hooks/useCatalogFileFilterState";
import { loadErrorMessage } from "../utils/saveError";
import { editCatalogFilePath } from "../utils/catalogFileLinks";

const NODE_TYPES = { catalog: CatalogGraphNode };

const LEGEND: { key: "stored" | "missing" | "external"; style: React.CSSProperties }[] = [
  { key: "stored", style: { border: "1.5px solid var(--mantine-color-toadie-7)" } },
  { key: "missing", style: { border: "1.5px dashed var(--mantine-color-red-6)" } },
  { key: "external", style: { border: "1.5px dashed var(--mantine-color-gray-5)" } },
];

type LayoutMode = "auto" | "manual";
type LayoutState = { mode: LayoutMode; positions: GraphPositions };

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
  const positions: GraphPositions = local?.positions ?? layoutQuery.data?.positions ?? {};

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
  const { nodes, edges } = useMemo(() => {
    if (!data || noKinds) return { nodes: [], edges: [] };
    const laid = layoutGraph(filterGraph(data, enabled));
    if (mode !== "manual") return laid;
    return { ...laid, nodes: applyManualPositions(laid.nodes, positions) };
  }, [data, enabled, noKinds, mode, positions]);

  // Refit the viewport when the node SET changes (filters, pills, relation chips): the
  // `fitView` prop fires only at init, so a later filter would leave the new layout under
  // the old graph's pan/zoom — nodes off-canvas. Keyed by the sorted ids, NOT positions,
  // so drags, mode switches, and Reset never yank the viewport around.
  const [rfInstance, setRfInstance] = useState<ReactFlowInstance<LaidOutNode, Edge> | null>(null);
  const fitKey = useMemo(() => nodes.map((n) => n.id).sort((a, b) => a.localeCompare(b)).join("|"), [nodes]);
  useEffect(() => {
    void rfInstance?.fitView();
  }, [rfInstance, fitKey]);

  // Live drag movement: React Flow's controlled-nodes contract — position changes flow
  // back through here and re-derive the nodes array; the drag-end change (dragging=false)
  // is what persists.
  function onNodesChange(changes: NodeChange<LaidOutNode>[]) {
    if (mode !== "manual") return;
    let next = positions;
    let dragEnded = false;
    for (const change of changes) {
      if (change.type !== "position") continue;
      if (change.position) next = { ...next, [change.id]: { x: change.position.x, y: change.position.y } };
      if (change.dragging === false) dragEnded = true;
    }
    if (dragEnded) persistLayout({ mode, positions: next }, true);
    else if (next !== positions) setLocal({ mode, positions: next });
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

      <FilterPanel activeFilterCount={filters.activeFilterCount} storageKey="renderGraph">
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
            nodesDraggable={mode === "manual"}
            nodesConnectable={false}
            edgesFocusable={false}
            proOptions={{ hideAttribution: false }}
          >
            <Background />
            <Controls showInteractive={false} />
          </ReactFlow>
        </Paper>
      )}
    </Stack>
  );
}
