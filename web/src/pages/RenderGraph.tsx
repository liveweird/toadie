import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { useNavigate } from "react-router-dom";
import {
  Alert,
  Center,
  Chip,
  Group,
  Loader,
  Paper,
  Stack,
  Text,
  Title,
  useComputedColorScheme,
} from "@mantine/core";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { Background, Controls, ReactFlow } from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import { IconTopologyStar3 } from "@tabler/icons-react";
import { getCatalogGraph } from "../api/catalogFiles";
import CatalogFileFilterControls from "../components/CatalogFileFilterControls";
import CatalogGraphNode from "../components/CatalogGraphNode";
import CatalogKindPills from "../components/CatalogKindPills";
import EmptyState from "../components/EmptyState";
import FilterPanel from "../components/FilterPanel";
import {
  filterGraph,
  layoutGraph,
  RELATION_FAMILIES,
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

  const noKinds = filters.noKinds;
  const { nodes, edges } = useMemo(() => {
    if (!data || noKinds) return { nodes: [], edges: [] };
    return layoutGraph(filterGraph(data, enabled));
  }, [data, enabled, noKinds]);

  function onNodeClick(_event: React.MouseEvent, node: LaidOutNode) {
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
            onNodeClick={onNodeClick}
            colorMode={colorScheme}
            fitView
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
