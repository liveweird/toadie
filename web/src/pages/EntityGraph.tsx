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
import { getEntityGraph, type EntityGraphNode as EntityGraphNodeApi } from "../api/entities";
import ClusterFrames from "../components/ClusterFrames";
import EntityGraphNode from "../components/EntityGraphNode";
import EntityGraphToolbar from "../components/EntityGraphToolbar";
import EntityQueryBar from "../components/EntityQueryBar";
import EmptyState from "../components/EmptyState";
import HierarchyPicker from "../components/HierarchyPicker";
import {
  applyManualPositions,
  clusterFrames,
  COLLAPSED_FACE_STYLE,
  FOLDED_EDGE_STYLE,
  layoutGraph,
  STATUS_STYLE,
  type ClusterAccessor,
  type GraphPositions,
  type LaidOutNode,
} from "../utils/graphLayout";
import { foldGraph } from "../utils/graphFold";
import {
  buildEntityHierarchy,
  effectiveHierarchyId,
  filterEntityGraph,
  OWNERSHIP_EDGE_STYLE,
  relationsOf,
  toFoldable,
} from "../utils/entityGraph";
import { useEntityGraphFilterState } from "../hooks/useEntityGraphFilterState";
import { useEntityQuery } from "../hooks/useEntityQuery";
import { useQueryDiagnostics } from "../hooks/useQueryDiagnostics";
import { useHierarchies } from "../hooks/useHierarchies";
import { useBlueprints } from "../hooks/useBlueprints";
import { useStoredState, isString } from "../hooks/useStoredState";
import { loadErrorMessage, saveErrorMessage } from "../utils/saveError";
import { editEntityPath } from "../utils/entityLinks";
import { OWNERSHIP_RELATION } from "../utils/systemBlueprints";
import { queryProblemDiagnostics } from "../utils/queryDiagnostics";
import LoadingBlock from "../components/LoadingBlock";
import PageHeader from "../components/PageHeader";
import classes from "../theme.module.css";
import { useGraphLayout } from "../hooks/useGraphLayout";
import { useSessionUserId } from "../auth";

const NODE_TYPES = { entity: EntityGraphNode };

/** One cluster per blueprint identifier, rendered as `entity` nodes. */
const ENTITY_CLUSTER: ClusterAccessor<EntityGraphNodeApi> = {
  keyOf: (n) => n.blueprint,
  nodeType: "entity",
};

const LEGEND: { key: "entity" | "collapsed"; style: React.CSSProperties }[] = [
  { key: "entity", style: { border: STATUS_STYLE.STORED.border } },
  { key: "collapsed", style: { border: STATUS_STYLE.STORED.border, ...COLLAPSED_FACE_STYLE.STORED } },
];

type LayoutMode = "auto" | "manual";

const EMPTY_POSITIONS: GraphPositions = {};
const EMPTY_COLLAPSED: string[] = [];

function toggleCollapsed(current: { collapsed: string[] }, id: string) {
  return current.collapsed.includes(id)
    ? current.collapsed.filter((entry) => entry !== id)
    : [...current.collapsed, id];
}

/**
 * The Entity graph (Port migration phase 3, v1.25.0; parallel hierarchies) — the Render page's
 * shell over `GET /api/v1/entities/graph`: blueprint/search filters select which entities are
 * shown (an edge is drawn only when both ends are shown, the catalog rule), relation chips fold
 * edges client-side, containment/thick-edge styling follow ONE admin-curated hierarchy at a
 * time (`HierarchyPicker` + `buildEntityHierarchy`, persisted per view under
 * `entityGraph.hierarchy`) — an edge belonging to a DIFFERENT hierarchy still draws, just as an
 * ordinary relation edge — and blueprint frames cluster nodes the way namespace frames do.
 * The per-user persisted `collapsed` list is ONE list shared across every hierarchy: a node
 * folded while viewing one hierarchy stays folded when the picker switches to another it also
 * has descendants in (there is no per-hierarchy collapsed set).
 */
export default function EntityGraph() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const colorScheme = useComputedColorScheme("light");
  const filters = useEntityGraphFilterState("entityGraph");
  const { hierarchies } = useHierarchies();
  const { blueprints } = useBlueprints();
  const [storedHierarchyId, setStoredHierarchyId] = useStoredState("entityGraph.hierarchy", "", isString);
  const hierarchyId = effectiveHierarchyId(storedHierarchyId, hierarchies.map((h) => h.value));

  const query = useEntityQuery();
  const { diagnostics: liveDiagnostics } = useQueryDiagnostics(query.draft);

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ["entities", "graph", filters.values, query.applied],
    queryFn: () => getEntityGraph({ ...filters.values, query: query.applied || undefined }),
    placeholderData: keepPreviousData,
  });

  // The last RUN query's own diagnostics (a real EntityQueryInvalid 400) win over the live
  // typing-check ones — they're authoritative for the text that was actually applied, and
  // their presence also means the generic load-failed Alert below stays suppressed.
  // A refused run carries `diagnostics`; they describe the APPLIED text, so the bar shows them
  // only while the draft still equals it (afterwards the live check takes over — the run's
  // positions would land on the wrong characters), but the generic load-failed alert stays
  // suppressed either way: the failure is the query, and the bar is its error surface.
  const refusedRun = isError ? queryProblemDiagnostics(error) : [];
  const runDiagnostics = query.draft === query.applied ? refusedRun : [];
  const diagnostics = runDiagnostics.length > 0 ? runDiagnostics : liveDiagnostics;
  const completionSchema = useMemo(() => ({ blueprints, hierarchies: hierarchies.map((h) => h.value) }), [blueprints, hierarchies]);
  const appliedCount = query.applied && data ? data.nodes.length : undefined;

  // Every relation starts ON — the fold chips are a separate, unpersisted dimension from the
  // filters above (the Render page's own posture); new relations therefore always start shown.
  const [disabled, setDisabled] = useState<Set<string>>(new Set());
  const relations = useMemo(() => (data ? relationsOf(data) : []), [data]);

  const userId = useSessionUserId();
  const layout = useGraphLayout(userId, "entityGraph");
  const updateLayout = layout.update;
  const layoutReady = layout.phase === "ready" && layout.document != null;
  const mode: LayoutMode = layout.document?.mode === "manual" ? "manual" : "auto";
  const positions: GraphPositions = layout.document?.positions ?? EMPTY_POSITIONS;
  const collapsed: string[] = layout.document?.collapsed ?? EMPTY_COLLAPSED;

  const toggleRef = useRef<(id: string) => void>(() => {});
  useEffect(() => {
    toggleRef.current = (id: string) => {
      updateLayout((current) => ({ ...current, collapsed: toggleCollapsed(current, id) }));
    };
  }, [updateLayout]);

  // Containment for the fold comes from the FULL payload (never the relation-chip-filtered
  // one), so an entity stays collapsible with any chip off — the Render page's rule — along
  // the SELECTED hierarchy only; other hierarchies' edges draw as ordinary relations.
  const forest = useMemo(() => (data ? buildEntityHierarchy(data, hierarchyId) : []), [data, hierarchyId]);
  const titleByBlueprint = useMemo(
    () => new Map((data?.nodes ?? []).map((n) => [n.blueprint, n.blueprintTitle])),
    [data],
  );
  const baseLayout = useMemo(() => {
    if (!data) return { nodes: [] as LaidOutNode<EntityGraphNodeApi>[], edges: [] as Edge[], anyCollapsed: false };
    const filtered = filterEntityGraph(data, disabled);
    const hierarchyRelations = new Set(
      data.edges.filter((e) => e.hierarchies.includes(hierarchyId)).map((e) => e.relation),
    );
    const folded = foldGraph(toFoldable(filtered), forest, new Set(collapsed));
    const laidOut = layoutGraph(folded, ENTITY_CLUSTER);
    // Hierarchy edges draw solid and thicker, labelled by relation id — folding can still dash
    // one that stands in for a hidden relation, so the fold's own style wins when both apply.
    const edges = laidOut.edges
      .map((e) =>
        hierarchyRelations.has(e.label as string) && !e.style
          ? { ...e, style: { strokeWidth: 2 } }
          : e,
      )
      // Ownership ($team) edges draw dotted gray — the fold's own dash still wins when an
      // ownership edge is ALSO a stand-in for a hidden relation (e.style spread last).
      .map((e) =>
        (e.data as { field?: string } | undefined)?.field === OWNERSHIP_RELATION
          ? { ...e, style: { ...OWNERSHIP_EDGE_STYLE, ...(e.style ?? {}) } }
          : e,
      );
    const nodes = laidOut.nodes.map((n) => {
      const info = folded.info.get(n.id);
      return info ? {
        ...n,
        data: {
          ...n.data,
          fold: { ...info, disabled: !layoutReady, onToggle: () => toggleRef.current(n.id) },
        },
      } : n;
    });
    const anyCollapsed = [...folded.info.values()].some((info) => info.collapsed);
    return { nodes, edges, anyCollapsed };
  }, [data, disabled, forest, collapsed, layoutReady, hierarchyId]);

  const [nodes, setNodes] = useNodesState<LaidOutNode<EntityGraphNodeApi>>([]);
  const [edges, setEdges] = useEdgesState<Edge>([]);

  useEffect(() => {
    setNodes(mode === "manual" ? applyManualPositions(baseLayout.nodes, positions) : baseLayout.nodes);
    setEdges(baseLayout.edges);
  }, [mode, baseLayout, positions, setNodes, setEdges]);

  const [rfInstance, setRfInstance] = useState<ReactFlowInstance<LaidOutNode<EntityGraphNodeApi>, Edge> | null>(null);
  const structureKey = useMemo(
    () => baseLayout.nodes.map((n) => n.id).sort((a, b) => a.localeCompare(b)).join("|"),
    [baseLayout.nodes],
  );
  useEffect(() => {
    void rfInstance?.fitView();
  }, [rfInstance, structureKey]);

  const frames = useMemo(
    () =>
      clusterFrames(nodes, ENTITY_CLUSTER.keyOf).map((f) => ({
        ...f,
        label: titleByBlueprint.get(f.key) ?? f.label,
      })),
    [nodes, titleByBlueprint],
  );

  function onNodesChange(changes: NodeChange<LaidOutNode<EntityGraphNodeApi>>[]) {
    setNodes((current) => applyNodeChanges(changes, current));
    if (mode !== "manual" || !layoutReady) return;
    const endedPositions: GraphPositions = {};
    let dragEnded = false;
    for (const change of changes) {
      if (change.type !== "position") continue;
      if (change.position) endedPositions[change.id] = { x: change.position.x, y: change.position.y };
      if (change.dragging === false) dragEnded = true;
    }
    if (dragEnded) {
      layout.update((current) => ({
        ...current,
        positions: { ...current.positions, ...endedPositions },
      }), true);
    }
  }

  const draggedRef = useRef(false);
  function onNodeDragStart() {
    draggedRef.current = true;
  }
  function onNodeDragStop() {
    window.setTimeout(() => {
      draggedRef.current = false;
    }, 0);
  }

  function onNodeClick(_event: React.MouseEvent, node: LaidOutNode<EntityGraphNodeApi>) {
    if (draggedRef.current) return;
    navigate(editEntityPath(node.data.apiNode.entityId));
  }

  return (
    <Stack gap="md" className={classes.fillPage}>
      <PageHeader
        title={t("entityGraph.title")}
        toolbar={
          <EntityGraphToolbar
            viewKey="entityGraph"
            filters={filters}
            query={
              <EntityQueryBar
                value={query.draft}
                onChange={query.setDraft}
                onRun={query.run}
                onClear={query.clear}
                diagnostics={diagnostics}
                completionSchema={completionSchema}
                appliedCount={appliedCount}
              />
            }
          >
            <HierarchyPicker value={hierarchyId} onChange={setStoredHierarchyId} />
            <Chip.Group
              multiple
              value={relations.filter((r) => !disabled.has(r))}
              onChange={(values) => setDisabled(new Set(relations.filter((r) => !values.includes(r))))}
            >
              <Group gap={6} role="group" aria-label={t("entityGraph.relationsLabel")}>
                {relations.map((relation) => (
                  <Chip key={relation} value={relation} size="xs">
                    {relation}
                  </Chip>
                ))}
              </Group>
            </Chip.Group>
            <Group gap="xs" ml="auto" wrap="wrap">
              <SegmentedControl
                size="xs"
                value={mode}
                disabled={!layoutReady}
                onChange={(value) => layout.update((current) => ({ ...current, mode: value as LayoutMode }))}
                data={[
                  { value: "auto", label: t("entityGraph.layoutMode.auto") },
                  { value: "manual", label: t("entityGraph.layoutMode.manual") },
                ]}
                aria-label={t("entityGraph.layoutMode.label")}
              />
              {mode === "manual" && (
                <Button
                  variant="default"
                  size="xs"
                  disabled={!layoutReady}
                  onClick={() => layout.update((current) => ({ ...current, positions: {} }))}
                >
                  {t("entityGraph.resetLayout")}
                </Button>
              )}
              {baseLayout.anyCollapsed && (
                <Button
                  variant="default"
                  size="xs"
                  disabled={!layoutReady}
                  onClick={() => layout.update((current) => ({ ...current, collapsed: [] }))}
                >
                  {t("entityGraph.expandAll")}
                </Button>
              )}
              <Popover position="bottom-end" shadow="md" withArrow>
                <Popover.Target>
                  <Button variant="subtle" size="xs" color="gray" leftSection={<IconInfoCircle size={14} />}>
                    {t("entityGraph.legend.title")}
                  </Button>
                </Popover.Target>
                <Popover.Dropdown>
                  <Stack gap="xs">
                    {LEGEND.map(({ key, style }) => (
                      <Group key={key} gap={8} wrap="nowrap">
                        <span
                          style={{ width: 14, height: 14, borderRadius: 4, display: "inline-block", flexShrink: 0, ...style }}
                        />
                        <Text size="xs">{t(`entityGraph.legend.${key}`)}</Text>
                      </Group>
                    ))}
                    <Group gap={8} wrap="nowrap">
                      <svg width={14} height={8} aria-hidden="true" style={{ display: "inline-block", flexShrink: 0 }}>
                        <line x1={0} y1={4} x2={14} y2={4} stroke="currentColor" strokeWidth={2} />
                      </svg>
                      <Text size="xs">{t("entityGraph.legend.hierarchyEdge")}</Text>
                    </Group>
                    <Group gap={8} wrap="nowrap">
                      <svg width={14} height={8} aria-hidden="true" style={{ display: "inline-block", flexShrink: 0 }}>
                        <line x1={0} y1={4} x2={14} y2={4} stroke="currentColor" strokeWidth={1.5} style={FOLDED_EDGE_STYLE} />
                      </svg>
                      <Text size="xs">{t("entityGraph.legend.folded")}</Text>
                    </Group>
                    <Group gap={8} wrap="nowrap">
                      <svg width={14} height={8} aria-hidden="true" style={{ display: "inline-block", flexShrink: 0 }}>
                        <line x1={0} y1={4} x2={14} y2={4} strokeWidth={1.5} style={OWNERSHIP_EDGE_STYLE} />
                      </svg>
                      <Text size="xs">{t("entityGraph.legend.ownershipEdge")}</Text>
                    </Group>
                  </Stack>
                </Popover.Dropdown>
              </Popover>
            </Group>
          </EntityGraphToolbar>
        }
      />

      {layout.phase === "loading" && (
        <Text role="status" aria-label={t("entityGraph.layout.loading")} size="sm" c="dimmed" style={{ flexShrink: 0 }}>
          {t("entityGraph.layout.loading")}
        </Text>
      )}
      {layout.phase === "loadError" && (
        <Alert color="red" variant="light" title={t("entityGraph.layout.loadFailed")} style={{ flexShrink: 0 }}>
          <Stack gap="xs" align="flex-start">
            <Text size="sm">{loadErrorMessage(layout.loadError, t)}</Text>
            <Button variant="default" size="xs" onClick={layout.retryLoad}>
              {t("entityGraph.layout.retryLoad")}
            </Button>
          </Stack>
        </Alert>
      )}
      {layout.saveError != null && (
        <Alert color="red" variant="light" title={t("entityGraph.layout.saveFailed")} style={{ flexShrink: 0 }}>
          <Stack gap="xs" align="flex-start">
            <Text size="sm">{saveErrorMessage(layout.saveError, t, {
              failedStatus: "common.error.saveFailedStatus",
              failed: "common.error.saveFailedNetwork",
            })}</Text>
            <Button variant="default" size="xs" onClick={layout.retrySave}>
              {t("entityGraph.layout.retrySave")}
            </Button>
          </Stack>
        </Alert>
      )}
      {!layout.saveError && layout.saving && (
        <Text role="status" aria-label={t("entityGraph.layout.saving")} size="sm" c="dimmed" style={{ flexShrink: 0 }}>
          {t("entityGraph.layout.saving")}
        </Text>
      )}
      {!layout.saveError && !layout.saving && layout.pending && (
        <Text role="status" aria-label={t("entityGraph.layout.pending")} size="sm" c="dimmed" style={{ flexShrink: 0 }}>
          {t("entityGraph.layout.pending")}
        </Text>
      )}

      {isError && refusedRun.length === 0 && (
        <Alert color="red" variant="light" title={t("entityGraph.loadFailed")} style={{ flexShrink: 0 }}>
          {loadErrorMessage(error, t)}
        </Alert>
      )}

      {isLoading && !data ? (
        <LoadingBlock />
      ) : !isLoading && !isError && nodes.length === 0 ? (
        <EmptyState icon={IconTopologyStar3} label={t("entityGraph.empty")} />
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
            minZoom={0.2}
            nodesDraggable={layoutReady && mode === "manual"}
            nodesConnectable={false}
            edgesFocusable={false}
            proOptions={{ hideAttribution: false }}
          >
            <ClusterFrames frames={frames} />
            <Background />
            <Controls showInteractive={false} />
          </ReactFlow>
        </Paper>
      )}
    </Stack>
  );
}
