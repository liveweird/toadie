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
import { useDebouncedValue } from "@mantine/hooks";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { Background, Controls, ReactFlow } from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import { IconTopologyStar3 } from "@tabler/icons-react";
import { getCatalogGraph } from "../api/catalogFiles";
import CatalogGraphNode from "../components/CatalogGraphNode";
import ClearableTextInput from "../components/ClearableTextInput";
import EmptyState from "../components/EmptyState";
import {
  filterGraph,
  layoutGraph,
  RELATION_FAMILIES,
  type LaidOutNode,
  type RelationFamily,
} from "../utils/graphLayout";
import { isString, useStoredState } from "../hooks/useStoredState";
import { loadErrorMessage } from "../utils/saveError";

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
  const [namespaceFilter, setNamespaceFilter] = useStoredState(
    "renderGraph.filter.namespace",
    "",
    isString,
  );
  // Deliberately not persisted: all relations on is the right starting view.
  const [enabled, setEnabled] = useState<RelationFamily[]>([...RELATION_FAMILIES]);

  const [debouncedNamespace] = useDebouncedValue(namespaceFilter, 300);

  const { data, isLoading, isError, error } = useQuery({
    // Under the "catalogFiles" prefix so every catalog mutation's invalidation refreshes it.
    queryKey: ["catalogFiles", "graph", debouncedNamespace],
    queryFn: () => getCatalogGraph(debouncedNamespace.trim() || undefined),
    placeholderData: keepPreviousData,
  });

  const { nodes, edges } = useMemo(() => {
    if (!data) return { nodes: [], edges: [] };
    return layoutGraph(filterGraph(data, enabled));
  }, [data, enabled]);

  function onNodeClick(_event: React.MouseEvent, node: LaidOutNode) {
    const fileId = node.data.apiNode.fileId;
    if (fileId != null) navigate(`/catalog-files/${fileId}/edit`);
  }

  return (
    <Stack gap="md" h="100%">
      <Title order={2}>{t("render.title")}</Title>

      <Group align="flex-end" gap="lg">
        <ClearableTextInput
          label={t("catalog.field.namespace")}
          value={namespaceFilter}
          onChange={setNamespaceFilter}
          clearLabel={t("common.filter.clearNamespace")}
          placeholder={t("common.filter.exact")}
        />
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
