import { useEffect, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { ActionIcon, Alert, Anchor, Badge, Box, CloseButton, Group, Menu, Paper, Stack, Text, Tooltip } from "@mantine/core";
import { Link as RouterLink, useNavigate } from "react-router-dom";
import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { IconChevronDown, IconChevronRight, IconChevronsDown, IconChevronsUp, IconPin, IconSitemap } from "@tabler/icons-react";
import { deleteEntity, getEntityGraph, type EntityGraphNode } from "../api/entities";
import EntityFindingsBadge from "../components/EntityFindingsBadge";
import EntityGraphToolbar from "../components/EntityGraphToolbar";
import ConfirmDeleteModal from "../components/ConfirmDeleteModal";
import EmptyState from "../components/EmptyState";
import HierarchyPicker from "../components/HierarchyPicker";
import RowActionsMenu from "../components/RowActionsMenu";
import { useDeleteConfirm } from "../hooks/useDeleteConfirm";
import { useEntityGraphFilterState } from "../hooks/useEntityGraphFilterState";
import { useHierarchies } from "../hooks/useHierarchies";
import { useStoredState, isString } from "../hooks/useStoredState";
import { buildEntityHierarchy, effectiveHierarchyId } from "../utils/entityGraph";
import { editEntityPath } from "../utils/entityLinks";
import { entityDeleteErrorMessage } from "../utils/entityForm";
import { findPlacement, type HierarchyNode } from "../utils/hierarchy";
import { loadErrorMessage } from "../utils/saveError";
import LoadingBlock from "../components/LoadingBlock";
import PageHeader from "../components/PageHeader";
import classes from "../theme.module.css";

function pathKey(path: string, node: EntityGraphNode): string {
  return `${path}/${node.id}`;
}

function branchKeys(items: HierarchyNode<EntityGraphNode>[], path: string, into: string[]): string[] {
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
  onPin,
  pinnedId,
  onDelete,
}: {
  item: HierarchyNode<EntityGraphNode>;
  path: string;
  collapsed: ReadonlySet<string>;
  onToggle: (key: string) => void;
  onPin: (id: string) => void;
  pinnedId: string;
  onDelete: (node: EntityGraphNode) => void;
}) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { node, children } = item;
  const key = pathKey(path, node);
  const isCollapsed = collapsed.has(key);
  return (
    <Box>
      <Group gap="xs" wrap="nowrap" py={2} px={4} className={classes.treeRow}>
        {children.length > 0 ? (
          <ActionIcon
            size="xs"
            aria-label={t("entityHierarchy.toggleAria", { name: node.title })}
            aria-expanded={!isCollapsed}
            onClick={() => onToggle(key)}
          >
            {isCollapsed ? <IconChevronRight size={14} /> : <IconChevronDown size={14} />}
          </ActionIcon>
        ) : (
          <Box w={18} style={{ flexShrink: 0 }} />
        )}
        <Badge variant="light" color="gray" size="xs">
          {node.blueprintTitle}
        </Badge>
        <Text size="sm" fw={500}>
          {node.title}
        </Text>
        <Anchor
          component={RouterLink}
          to={editEntityPath(node.entityId)}
          size="xs"
          ff="monospace"
          aria-label={t("common.action.editAria", { name: node.identifier })}
        >
          {node.identifier}
        </Anchor>
        <EntityFindingsBadge findings={node.findings} />
        <Box ml="auto" style={{ flexShrink: 0 }}>
          <RowActionsMenu label={t("common.table.operationsAria", { name: node.title })}>
            <Menu.Item leftSection={<IconPin size={14} />} onClick={() => onPin(node.id)}>
              {node.id === pinnedId ? t("entityHierarchy.unpin") : t("entityHierarchy.pin")}
            </Menu.Item>
            <Menu.Item onClick={() => navigate(editEntityPath(node.entityId))}>
              {t("common.action.edit")}
            </Menu.Item>
            <Menu.Item color="red" onClick={() => onDelete(node)}>
              {t("common.action.delete")}
            </Menu.Item>
          </RowActionsMenu>
        </Box>
      </Group>
      {children.length > 0 && !isCollapsed && (
        <Box pl={22} ml={13} className={classes.treeBranch}>
          {children.map((child) => (
            <TreeItem
              key={pathKey(key, child.node)}
              item={child}
              path={key}
              collapsed={collapsed}
              onToggle={onToggle}
              onPin={onPin}
              pinnedId={pinnedId}
              onDelete={onDelete}
            />
          ))}
        </Box>
      )}
    </Box>
  );
}

/**
 * The Entity hierarchy (Port migration phase 3, v1.25.0; parallel hierarchies) — the
 * Hierarchy page's shell over the SAME `GET /api/v1/entities/graph` query the Entity graph
 * page uses: a forest built along ONE admin-curated hierarchy at a time
 * (`HierarchyPicker` + `utils/entityGraph.ts#buildEntityHierarchy`), persisted per view under
 * `entityHierarchy.hierarchy`. Every authenticated user gets Pin/Edit/Delete on every row —
 * entities are a shared workspace, like catalog files.
 */
export default function EntityHierarchy() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const filters = useEntityGraphFilterState("entityHierarchy");
  const [collapsed, setCollapsed] = useState<ReadonlySet<string>>(new Set());
  const [pinnedId, setPinnedId] = useStoredState("entityHierarchy.pinnedNodeId", "", isString);
  const { hierarchies } = useHierarchies();
  const [storedHierarchyId, setStoredHierarchyId] = useStoredState("entityHierarchy.hierarchy", "", isString);
  const hierarchyId = effectiveHierarchyId(storedHierarchyId, hierarchies.map((h) => h.value));

  const { data, isPending, isError, error } = useQuery({
    queryKey: ["entities", "graph", filters.values],
    queryFn: () => getEntityGraph(filters.values),
    placeholderData: keepPreviousData,
  });

  const roots = useMemo(() => (data ? buildEntityHierarchy(data, hierarchyId) : []), [data, hierarchyId]);
  const placement = useMemo(() => (pinnedId ? findPlacement(roots, pinnedId) : null), [roots, pinnedId]);
  const visible = placement ? [placement.item] : roots;
  const basePath = placement?.path ?? "";

  useEffect(() => {
    if (pinnedId && data && !placement) setPinnedId("");
  }, [pinnedId, data, placement, setPinnedId]);

  const deleteConfirm = useDeleteConfirm<EntityGraphNode>({
    mutationFn: (row) => deleteEntity(row.entityId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["entities"] }),
    successMessage: t("entities.toast.deleted"),
  });

  function toggle(key: string) {
    setCollapsed((current) => {
      const next = new Set(current);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  }

  return (
    <Stack gap="md">
      <PageHeader
        title={t("entityHierarchy.title")}
        toolbar={
          <EntityGraphToolbar viewKey="entityHierarchy" filters={filters}>
            <HierarchyPicker value={hierarchyId} onChange={setStoredHierarchyId} />
            <Tooltip label={t("entityHierarchy.expandAll")}>
              <ActionIcon variant="default" size="md" aria-label={t("entityHierarchy.expandAll")} onClick={() => setCollapsed(new Set())}>
                <IconChevronsDown size={16} />
              </ActionIcon>
            </Tooltip>
            <Tooltip label={t("entityHierarchy.collapseAll")}>
              <ActionIcon
                variant="default"
                size="md"
                aria-label={t("entityHierarchy.collapseAll")}
                onClick={() => setCollapsed(new Set(branchKeys(visible, basePath, [])))}
              >
                <IconChevronsUp size={16} />
              </ActionIcon>
            </Tooltip>
            {placement && (
              <Badge
                variant="light"
                color="gray"
                size="lg"
                tt="none"
                leftSection={<IconPin size={12} />}
                rightSection={
                  <CloseButton
                    size="xs"
                    variant="transparent"
                    aria-label={t("entityHierarchy.pinned.clearAria", { name: placement.item.node.title })}
                    onClick={() => setPinnedId("")}
                  />
                }
              >
                {t("entityHierarchy.pinned.badge", { name: placement.item.node.title })}
              </Badge>
            )}
          </EntityGraphToolbar>
        }
      />

      {isError && (
        <Alert color="red" variant="light" title={t("entityHierarchy.loadFailed")}>
          {loadErrorMessage(error, t)}
        </Alert>
      )}

      <Paper withBorder p="md">
        {isPending && !data ? (
          <LoadingBlock py="md" />
        ) : visible.length > 0 ? (
          visible.map((root) => (
            <TreeItem
              key={pathKey(basePath, root.node)}
              item={root}
              path={basePath}
              collapsed={collapsed}
              onToggle={toggle}
              onPin={(id) => setPinnedId(id === pinnedId ? "" : id)}
              pinnedId={pinnedId}
              onDelete={(node) => deleteConfirm.requestDelete(node)}
            />
          ))
        ) : !isError ? (
          <EmptyState icon={IconSitemap} label={t("entityHierarchy.empty")} />
        ) : null}
      </Paper>

      <ConfirmDeleteModal
        confirm={deleteConfirm}
        title={t("entities.deleteTitle")}
        errorTitle={t("entities.deleteFailed")}
        body={(target) => t("entities.deleteBody", { identifier: target.identifier })}
        errorMessage={(err) => entityDeleteErrorMessage(err, t)}
      />
    </Stack>
  );
}
