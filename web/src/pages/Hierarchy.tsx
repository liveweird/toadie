import { useEffect, useMemo, useState, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import {
  ActionIcon,
  Alert,
  Badge,
  Box,
  Button,
  Center,
  CloseButton,
  Group,
  Loader,
  Paper,
  Stack,
  Text,
  Title,
} from "@mantine/core";
import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { IconChevronDown, IconChevronRight, IconPin, IconSitemap } from "@tabler/icons-react";
import { deleteCatalogFile, getCatalogGraph, type GraphNode } from "../api/catalogFiles";
import CatalogFileFilterControls from "../components/CatalogFileFilterControls";
import CatalogFileNameLink from "../components/CatalogFileNameLink";
import CatalogFileOperations from "../components/CatalogFileOperations";
import OverwriteWithYamlModal, { type OverwriteTarget } from "../components/OverwriteWithYamlModal";
import CatalogKindPills from "../components/CatalogKindPills";
import ConfirmDeleteModal from "../components/ConfirmDeleteModal";
import EmptyState from "../components/EmptyState";
import FilterPanel from "../components/FilterPanel";
import KindTierDot from "../components/KindTierDot";
import LensPicker from "../components/LensPicker";
import { useCatalogDownloads } from "../hooks/useCatalogDownloads";
import { useCatalogFileFilterState } from "../hooks/useCatalogFileFilterState";
import { useDeleteConfirm } from "../hooks/useDeleteConfirm";
import { useStoredState, isString } from "../hooks/useStoredState";
import { buildHierarchy, findPlacement, type HierarchyNode } from "../utils/hierarchy";
import { loadErrorMessage } from "../utils/saveError";

/** The delete confirm's target — the tree row's identity, shaped like a list row. */
interface DeleteTarget {
  id: number;
  name: string;
  namespace: string;
}

/** Collapse-state keys are PATHS, not node ids — a User under two Groups folds independently. */
function pathKey(path: string, node: GraphNode): string {
  return `${path}/${node.id}`;
}

function branchKeys(items: HierarchyNode[], path: string, into: string[]): string[] {
  for (const item of items) {
    const key = pathKey(path, item.node);
    if (item.children.length > 0) {
      into.push(key);
      branchKeys(item.children, key, into);
    }
  }
  return into;
}

function TreeItem({
  item,
  path,
  collapsed,
  onToggle,
  operations,
}: {
  item: HierarchyNode;
  path: string;
  collapsed: ReadonlySet<string>;
  onToggle: (key: string) => void;
  operations: (node: GraphNode) => ReactNode;
}) {
  const { t } = useTranslation();
  const { node, children } = item;
  const key = pathKey(path, node);
  const isCollapsed = collapsed.has(key);
  const virtual = node.status !== "STORED";
  return (
    <Box>
      <Group gap="xs" wrap="nowrap" py={4}>
        {children.length > 0 ? (
          <ActionIcon
            variant="subtle"
            color="gray"
            size="sm"
            aria-label={t("hierarchy.toggleAria", { name: node.name })}
            aria-expanded={!isCollapsed}
            onClick={() => onToggle(key)}
          >
            {isCollapsed ? <IconChevronRight size={16} /> : <IconChevronDown size={16} />}
          </ActionIcon>
        ) : (
          <Box w={22} />
        )}
        <Badge variant="light" size="sm" color={virtual ? "gray" : undefined} leftSection={<KindTierDot kind={node.kind} />}>
          {node.kind}
        </Badge>
        {/* Placeholders have no stored entity to open — the same `fileId == null` test the
            row's Operations menu applies — so they stay dimmed italic text. */}
        {node.fileId != null ? (
          <CatalogFileNameLink id={node.fileId} name={node.name} />
        ) : (
          <Text size="sm" fw={400} c="dimmed" fs="italic">
            {node.name}
          </Text>
        )}
        {node.title ? (
          <Text size="sm" c="dimmed" truncate>
            {node.title}
          </Text>
        ) : null}
        {node.status === "MISSING" && (
          <Badge variant="outline" size="sm" color="red">
            {t("hierarchy.badge.missing")}
          </Badge>
        )}
        {operations(node)}
      </Group>
      {children.length > 0 && !isCollapsed && (
        <Box pl={26} style={{ borderLeft: "1px solid var(--mantine-color-default-border)" }} ml={10}>
          {children.map((child) => (
            <TreeItem
              key={pathKey(key, child.node)}
              item={child}
              path={key}
              collapsed={collapsed}
              onToggle={onToggle}
              operations={operations}
            />
          ))}
        </Box>
      )}
    </Box>
  );
}

/**
 * The Hierarchy view at `/`: the workspace's entities as collapsible containment trees
 * (Domain ⊃ Systems ⊃ Components/APIs/Resources, subcomponents nested, Groups with their
 * member Users). Data is the Graph page's endpoint — same query key, same cache, refreshed
 * by every catalog mutation; the shaping lives in utils/hierarchy.ts. STORED rows carry the
 * Files list's Operations menu; MISSING placeholders render dimmed without one.
 */
export default function Hierarchy() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  // The Files list's full filter set (per-view persisted under hierarchy.filter.*) — the
  // graph endpoint declares the same params and answers the entities they SELECT, so the tree
  // nests only what is shown: filtered to one kind, its rows sit flat at the root.
  const filters = useCatalogFileFilterState("hierarchy");
  const [collapsed, setCollapsed] = useState<ReadonlySet<string>>(new Set());
  // The pinned node id ("" = not pinned). Persisted, unlike the collapse state beside it:
  // every row links into a file's editor, so a pin that died on navigation would have to be
  // re-set on each trip back — exactly during the focused work pinning is for.
  const [pinnedId, setPinnedId] = useStoredState("hierarchy.pinnedNodeId", "", isString);
  // The graph payload carries no sourceUrl, so the tree offers overwrite but not sync
  // (a permanently-greyed Sync item here would assert something we cannot know).
  const [overwriteTarget, setOverwriteTarget] = useState<OverwriteTarget | null>(null);
  const downloads = useCatalogDownloads();

  const { data, isPending, isError, error } = useQuery({
    queryKey: ["catalogFiles", "graph", filters.values],
    queryFn: () => getCatalogGraph(filters.values),
    placeholderData: keepPreviousData,
    // Every kind pill off = show nothing — never fetch (the API can't say match-nothing).
    enabled: !filters.noKinds,
  });

  const noKinds = filters.noKinds;
  const roots = useMemo(() => (data && !noKinds ? buildHierarchy(data) : []), [data, noKinds]);
  // A pin narrows the tree to one entity and its descendants, ON TOP of the filters. Rendering
  // the subtree at the path it already hangs at keeps the collapse keys stable across pinning.
  const placement = useMemo(
    () => (pinnedId ? findPlacement(roots, pinnedId) : null),
    [roots, pinnedId],
  );
  const visible = placement ? [placement.item] : roots;
  const basePath = placement?.path ?? "";

  // The pinned entity left the view — a narrowed filter, a kind pill off, the file deleted —
  // so the pin drops itself. Guarded on DATA (an effect firing mid-load would discard a
  // restored pin on every reload) and on kinds (all pills off renders nothing by design, with
  // the query disabled: that is not the entity being gone).
  useEffect(() => {
    if (pinnedId && data && !noKinds && !placement) setPinnedId("");
  }, [pinnedId, data, noKinds, placement, setPinnedId]);

  const deleteConfirm = useDeleteConfirm<DeleteTarget>({
    mutationFn: (row) => deleteCatalogFile(row.id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["catalogFiles"] }),
    successMessage: t("catalog.toast.deleted"),
  });

  function toggle(key: string) {
    setCollapsed((current) => {
      const next = new Set(current);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  }

  const operations = (node: GraphNode) => {
    const fileId = node.fileId;
    if (fileId == null) return null;
    return (
      <CatalogFileOperations
        id={fileId}
        name={node.name}
        downloading={downloads.downloadingId === fileId}
        onExport={() => void downloads.handleDownload({ id: fileId })}
        onOverwrite={() =>
          setOverwriteTarget({
            id: fileId,
            kind: node.kind,
            name: node.name,
            namespace: node.namespace,
          })
        }
        onDelete={() =>
          deleteConfirm.requestDelete({ id: fileId, name: node.name, namespace: node.namespace })
        }
        pin={{
          pinned: node.id === pinnedId,
          onToggle: () => setPinnedId(node.id === pinnedId ? "" : node.id),
        }}
      />
    );
  };

  return (
    <Stack gap="md">
      <Title order={2}>{t("hierarchy.title")}</Title>

      <FilterPanel
        activeFilterCount={filters.activeFilterCount}
        storageKey="hierarchy"
        aside={<LensPicker values={filters.values} controls={filters.controls} />}
      >
        <CatalogFileFilterControls controls={filters.controls} />
      </FilterPanel>

      <CatalogKindPills kinds={filters.controls.kinds} setKinds={filters.controls.setKinds} />

      <Group gap="lg">
        <Button variant="default" size="xs" onClick={() => setCollapsed(new Set())}>
          {t("hierarchy.expandAll")}
        </Button>
        <Button
          variant="default"
          size="xs"
          onClick={() => setCollapsed(new Set(branchKeys(visible, basePath, [])))}
        >
          {t("hierarchy.collapseAll")}
        </Button>
        {/* Gray: a pin is neutral state, not caution (the LensPicker's Modified badge). It
            deliberately stays out of the FilterPanel's active-filter count — that badge
            speaks for what the collapsed panel HIDES, and this says its piece in the open. */}
        {placement && (
          <Badge
            variant="light"
            color="gray"
            size="lg"
            // Badge uppercases by default; an entity name is shown as stored everywhere else.
            tt="none"
            leftSection={<IconPin size={12} />}
            rightSection={
              <CloseButton
                size="xs"
                variant="transparent"
                aria-label={t("hierarchy.pinned.clearAria", { name: placement.item.node.name })}
                onClick={() => setPinnedId("")}
              />
            }
          >
            {t("hierarchy.pinned.badge", { name: placement.item.node.name })}
          </Badge>
        )}
      </Group>

      {isError && (
        <Alert color="red" variant="light" title={t("hierarchy.loadFailed")}>
          {loadErrorMessage(error, t)}
        </Alert>
      )}
      {downloads.downloadError != null && (
        <Alert
          color="red"
          variant="light"
          title={t("catalog.downloadFailed")}
          withCloseButton
          closeButtonLabel={t("common.action.close")}
          onClose={downloads.dismissDownloadError}
        >
          {loadErrorMessage(downloads.downloadError, t)}
        </Alert>
      )}

      <Paper withBorder p="md">
        {/* A disabled (noKinds) query stays pending forever — fall through to the empty state. */}
        {isPending && !data && !noKinds ? (
          <Center py="md">
            <Loader size="sm" />
          </Center>
        ) : visible.length > 0 ? (
          visible.map((root) => (
            <TreeItem
              key={pathKey(basePath, root.node)}
              item={root}
              path={basePath}
              collapsed={collapsed}
              onToggle={toggle}
              operations={operations}
            />
          ))
        ) : !isError ? (
          <EmptyState
            icon={<IconSitemap size={32} stroke={1.2} color="var(--mantine-color-dimmed)" />}
            label={t("hierarchy.empty")}
          />
        ) : null}
      </Paper>

      <OverwriteWithYamlModal file={overwriteTarget} onClose={() => setOverwriteTarget(null)} />

      <ConfirmDeleteModal
        confirm={deleteConfirm}
        title={t("catalog.deleteModalTitle")}
        errorTitle={t("catalog.deleteFailed")}
        body={(target) => (
          <>
            {t("catalog.deleteTitle", { name: target.name, namespace: target.namespace })}{" "}
            {t("catalog.deleteUndone")}
          </>
        )}
      />
    </Stack>
  );
}
