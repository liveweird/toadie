import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import {
  ActionIcon,
  Alert,
  Anchor,
  Badge,
  Box,
  Button,
  Checkbox,
  Group,
  Paper,
  Stack,
  Text,
  Tooltip,
} from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
import { useQuery } from "@tanstack/react-query";
import {
  IconChevronDown,
  IconChevronRight,
  IconChevronsDown,
  IconChevronsUp,
  IconCode,
  IconFilter,
  IconSitemap,
} from "@tabler/icons-react";
import { Link as RouterLink } from "react-router-dom";
import {
  getEntityGraph,
  MAX_BULK_CHANGE_ENTITIES,
  type EntityBulkResult,
  type EntityGraphNode,
} from "../api/entities";
import type { Blueprint } from "../api/blueprints";
import BlueprintPickPills from "../components/BlueprintPickPills";
import CollapsibleHeader, { type HeaderSection } from "../components/CollapsibleHeader";
import EmptyState from "../components/EmptyState";
import EntityBulkChangeModal, { type BulkTarget } from "../components/EntityBulkChangeModal";
import EntityFindingsBadge from "../components/EntityFindingsBadge";
import EntityGraphFilterControls from "../components/EntityGraphFilterControls";
import EntityQueryBar from "../components/EntityQueryBar";
import HierarchyPicker from "../components/HierarchyPicker";
import LoadingBlock from "../components/LoadingBlock";
import TierDot from "../components/TierDot";
import VirtualTreeList from "../components/VirtualTreeList";
import { useBlueprintParam, useQParam, useTeamParam } from "../hooks/useBlueprintParam";
import { useBlueprints } from "../hooks/useBlueprints";
import { useEntityQuery } from "../hooks/useEntityQuery";
import { useHierarchies } from "../hooks/useHierarchies";
import { useQueryDiagnostics } from "../hooks/useQueryDiagnostics";
import { isBoolean, isString, useStoredState } from "../hooks/useStoredState";
import {
  ancestorBlueprints,
  checkState,
  pruneToBlueprint,
  targetsUnder,
  withSelection,
} from "../utils/entityBulk";
import { buildEntityHierarchy, effectiveHierarchyId } from "../utils/entityGraph";
import { editEntityPath } from "../utils/entityLinks";
import type { HierarchyNode } from "../utils/hierarchy";
import { queryProblemDiagnostics } from "../utils/queryDiagnostics";
import { loadErrorMessage } from "../utils/saveError";
import { blueprintTierLookup, type Tier } from "../utils/tiers";
import { branchKeys, flattenTree, type TreeRow } from "../utils/treeRows";
import classes from "../theme.module.css";

/** One tree row: a TARGET (an entity of the picked blueprint) selects itself; a CONTEXT row (an
 *  ancestor along the selected hierarchy, never changed) is a tri-state over every target
 *  beneath it — its whole subtree, collapsed or not. */
function BulkRow({
  row,
  blueprint,
  selected,
  onSelect,
  onToggle,
  tierOf,
}: {
  row: TreeRow<EntityGraphNode>;
  blueprint: string;
  selected: ReadonlySet<number>;
  onSelect: (ids: readonly number[], on: boolean) => void;
  onToggle: (key: string) => void;
  tierOf: (identifier: string) => Tier | null;
}) {
  const { t } = useTranslation();
  const { node } = row.item;
  const isTarget = node.blueprint === blueprint;
  const nodeTier = tierOf(node.blueprint);
  const beneath = isTarget ? [node.entityId] : targetsUnder(row.item.children, blueprint).map((n) => n.entityId);
  const state = checkState(beneath, selected);

  return (
    <Group gap="xs" wrap="nowrap" py={2} px={4} className={classes.treeRow}>
      {row.hasChildren ? (
        <ActionIcon
          size="xs"
          aria-label={t("entityBulk.toggleAria", { name: node.title })}
          aria-expanded={!row.collapsed}
          onClick={() => onToggle(row.key)}
        >
          {row.collapsed ? <IconChevronRight size={14} /> : <IconChevronDown size={14} />}
        </ActionIcon>
      ) : (
        <Box w={18} style={{ flexShrink: 0 }} />
      )}
      <Checkbox
        size="xs"
        checked={state === "all"}
        indeterminate={state === "some"}
        aria-label={
          isTarget
            ? t("entityBulk.selectAria", { name: node.title })
            : t("entityBulk.selectGroupAria", { name: node.title, count: beneath.length })
        }
        onChange={() => onSelect(beneath, state !== "all")}
      />
      <Group gap="xs" wrap="wrap" className={classes.treeIdentity}>
        <Badge
          variant="light"
          color="gray"
          size="xs"
          leftSection={nodeTier === null ? undefined : <TierDot tier={nodeTier} />}
        >
          {node.blueprintTitle}
        </Badge>
        <Text size="sm" fw={isTarget ? 500 : 400} c={isTarget ? undefined : "dimmed"} className={classes.treeIdentityText}>
          {node.title}
        </Text>
        {isTarget ? (
          <Anchor
            component={RouterLink}
            to={editEntityPath(node.entityId)}
            size="xs"
            ff="monospace"
            aria-label={t("common.action.editAria", { name: node.identifier })}
            className={classes.treeIdentityText}
          >
            {node.identifier}
          </Anchor>
        ) : (
          <Text size="xs" ff="monospace" c="dimmed" className={classes.treeIdentityText}>
            {node.identifier}
          </Text>
        )}
        {isTarget && <EntityFindingsBadge findings={node.findings} />}
      </Group>
    </Group>
  );
}

/**
 * The selection surface for ONE blueprint: the select-all header, the (windowed) tree and the
 * sticky action bar. Mounted with `key={blueprint}` by the page, so switching the pill starts
 * from an empty selection. The selection holds entity ids; what counts is its intersection with
 * the targets currently SHOWN (derived, never an effect), so narrowing a filter silently drops
 * hidden entities from the count and from the request — and it SURVIVES the tree going empty
 * (a no-match search, a transient load error, a refetch after the change emptied a filter or
 * query): this component stays mounted for the loading and empty states, which render inside it,
 * and with it the open dialog (its result must not vanish under the user).
 */
function BulkSelection({
  blueprint,
  roots,
  rows,
  loading,
  emptyState,
  onToggle,
  tierOf,
}: {
  blueprint: Blueprint;
  roots: HierarchyNode<EntityGraphNode>[];
  rows: TreeRow<EntityGraphNode>[];
  /** The first graph load for this blueprint is still running. */
  loading: boolean;
  /** What stands in for the tree while it is empty (no match, a failed load) — rendered INSIDE
   *  this component so the selection and an open dialog outlive the tree going away. */
  emptyState: ReactNode;
  onToggle: (key: string) => void;
  tierOf: (identifier: string) => Tier | null;
}) {
  const { t } = useTranslation();
  const [selected, setSelected] = useState<ReadonlySet<number>>(new Set());
  const [modalTargets, setModalTargets] = useState<BulkTarget[] | null>(null);

  const shown = useMemo(() => targetsUnder(roots, blueprint.identifier), [roots, blueprint.identifier]);
  const shownIds = useMemo(() => shown.map((node) => node.entityId), [shown]);
  const chosen = shown.filter((node) => selected.has(node.entityId));
  const overCap = chosen.length > MAX_BULK_CHANGE_ENTITIES;
  const allState = checkState(shownIds, selected);

  function select(ids: readonly number[], on: boolean) {
    setSelected((current) => withSelection(current, ids, on));
  }

  function applied(results: readonly EntityBulkResult[]) {
    // Done rows (changed, or already as wanted) leave the selection; the failed ones stay for a retry.
    const done = results.filter((result) => result.status === "UPDATED" || result.status === "UNCHANGED");
    setSelected((current) => withSelection(current, done.map((result) => result.id), false));
  }

  return (
    <>
      {loading ? (
        <LoadingBlock py="md" />
      ) : rows.length === 0 ? (
        emptyState
      ) : (
        <Paper withBorder p="lg" radius="md">
          <Stack gap="sm">
            <Group gap="sm" wrap="nowrap">
              <Checkbox
                size="xs"
                checked={allState === "all"}
                indeterminate={allState === "some"}
                aria-label={t("entityBulk.selectAllAria", { count: shownIds.length })}
                onChange={() => select(shownIds, allState !== "all")}
              />
              <Text size="sm" c="dimmed">
                {t("entityBulk.shown", { count: shownIds.length })}
              </Text>
            </Group>
            <Box className={classes.treeSurface}>
              <VirtualTreeList
                rows={rows}
                renderRow={(row) => (
                  <BulkRow
                    row={row}
                    blueprint={blueprint.identifier}
                    selected={selected}
                    onSelect={select}
                    onToggle={onToggle}
                    tierOf={tierOf}
                  />
                )}
              />
            </Box>
            <Group justify="space-between" gap="sm" className={classes.stickyActions}>
              <Stack gap={0}>
                <Text size="sm" fw={500}>
                  {t("entityBulk.selected", { count: chosen.length })}
                </Text>
                {overCap && (
                  <Text size="xs" c="red" role="alert">
                    {t("entityBulk.overCap", { max: MAX_BULK_CHANGE_ENTITIES })}
                  </Text>
                )}
              </Stack>
              <Button
                disabled={chosen.length === 0 || overCap}
                onClick={() =>
                  setModalTargets(
                    chosen.map((node) => ({ id: node.entityId, identifier: node.identifier, title: node.title })),
                  )
                }
              >
                {t("entityBulk.confirm")}
              </Button>
            </Group>
          </Stack>
        </Paper>
      )}
      {modalTargets !== null && (
        <EntityBulkChangeModal
          blueprint={blueprint}
          targets={modalTargets}
          onClose={() => setModalTargets(null)}
          onApplied={applied}
        />
      )}
    </>
  );
}

/**
 * Bulk change (2.38.0, `/entities/bulk-change`, "Bulk change" in `web/CLAUDE.md`): pick ONE
 * blueprint (`?blueprint=`, the `BlueprintPickPills` row), tick entities in a tree of that
 * blueprint's entities with their ancestors along the selected hierarchy as non-editable
 * context rows (a context checkbox selects every target beneath it), then Confirm opens the
 * modal that changes one field of the selection. Scope filters ride the URL like the Entities
 * list (`?q=`, `?team=`); the query bar is the canvases' shared one. Any authenticated user —
 * no admin gate, like every entity feature.
 */
export default function EntityBulkChange() {
  const { t } = useTranslation();
  const { blueprint, setBlueprint } = useBlueprintParam();
  const { team, setTeam } = useTeamParam();
  const { q, setQ } = useQParam();
  const [debouncedQ] = useDebouncedValue(q, 300);
  const { blueprints, loading: blueprintsLoading } = useBlueprints();
  const tierOf = useMemo(() => blueprintTierLookup(blueprints), [blueprints]);
  const { hierarchies } = useHierarchies();
  const [storedHierarchyId, setStoredHierarchyId] = useStoredState("entityBulk.hierarchy", "", isString);
  const hierarchyId = effectiveHierarchyId(storedHierarchyId, hierarchies.map((h) => h.value));
  const [filtersOpen, setFiltersOpen] = useStoredState("entityBulk.filtersOpen", false, isBoolean);
  const [collapsed, setCollapsed] = useState<ReadonlySet<string>>(new Set());

  const query = useEntityQuery();
  const { diagnostics: liveDiagnostics } = useQueryDiagnostics(query.draft);

  const selectedBlueprint = blueprint ? blueprints.find((b) => b.identifier === blueprint) : undefined;
  const target = selectedBlueprint?.identifier ?? "";
  const ancestors = useMemo(
    () => (target ? ancestorBlueprints(target, blueprints, hierarchyId) : []),
    [target, blueprints, hierarchyId],
  );

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ["entities", "graph", "bulk", target, ancestors, debouncedQ, team, query.applied],
    queryFn: () =>
      getEntityGraph({
        blueprints: [target, ...ancestors],
        q: debouncedQ || undefined,
        team: team || undefined,
        query: query.applied || undefined,
      }),
    enabled: target !== "",
    // Keep the previous tree while a filter settles — but never another blueprint's.
    placeholderData: (previous, previousQuery) => (previousQuery?.queryKey[3] === target ? previous : undefined),
  });
  const graph = target === "" ? undefined : data;

  const refusedRun = isError ? queryProblemDiagnostics(error) : [];
  const runDiagnostics = query.draft === query.applied ? refusedRun : [];
  const diagnostics = runDiagnostics.length > 0 ? runDiagnostics : liveDiagnostics;
  const completionSchema = useMemo(
    () => ({ blueprints, hierarchies: hierarchies.map((h) => h.value) }),
    [blueprints, hierarchies],
  );

  // A refused run reopens the Query section on the RISING edge only, so an explicit Close sticks.
  const wasForcedOpen = useRef(false);
  const forcedOpen = runDiagnostics.length > 0;
  const { setOpen: setQueryOpen } = query;
  useEffect(() => {
    const rising = forcedOpen && !wasForcedOpen.current;
    wasForcedOpen.current = forcedOpen;
    if (rising) setQueryOpen(true);
  }, [forcedOpen, setQueryOpen]);

  const roots = useMemo(
    () => (graph ? pruneToBlueprint(buildEntityHierarchy(graph, hierarchyId), target) : []),
    [graph, hierarchyId, target],
  );
  const rows = useMemo(() => flattenTree(roots, collapsed, ""), [roots, collapsed]);
  const shownCount = useMemo(() => targetsUnder(roots, target).length, [roots, target]);

  function toggle(key: string) {
    setCollapsed((current) => {
      const next = new Set(current);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  }

  const filterControls = { q, setQ, team: team ?? "", setTeam: (value: string) => setTeam(value || null) };
  const sections: HeaderSection[] = [
    {
      id: "entityBulk-filters",
      icon: <IconFilter size={16} />,
      label: t("common.filter.title"),
      open: filtersOpen,
      onOpenChange: setFiltersOpen,
      count: (q.trim() ? 1 : 0) + (team ? 1 : 0),
      frame: "paper",
      content: (
        <Group align="flex-end" gap="sm">
          <EntityGraphFilterControls controls={filterControls} showFocus={false} />
        </Group>
      ),
    },
    {
      id: "entityBulk-query",
      icon: <IconCode size={16} />,
      label: t("entityQuery.toggle"),
      open: query.open,
      onOpenChange: query.setOpen,
      extra:
        query.applied && graph ? (
          <Badge data-testid="entityQuery-applied" variant="light" color="gray" size="sm" tt="none" style={{ flexShrink: 0 }}>
            {t("entityQuery.applied")} · {t("entityQuery.appliedCount", { count: shownCount })}
          </Badge>
        ) : undefined,
      frame: "paper",
      content: (
        <EntityQueryBar
          value={query.draft}
          onChange={query.setDraft}
          onRun={query.run}
          onClear={query.clear}
          diagnostics={diagnostics}
          completionSchema={completionSchema}
          applied={query.applied}
          draft={query.draft}
          onPick={query.runText}
        />
      ),
    },
  ];

  let body: ReactNode;
  if (blueprintsLoading) body = <LoadingBlock py="md" />;
  else if (!selectedBlueprint) body = <EmptyState icon={IconSitemap} label={t("entityBulk.pickBlueprint")} />;
  else {
    body = (
      <BulkSelection
        key={selectedBlueprint.identifier}
        blueprint={selectedBlueprint}
        roots={roots}
        rows={rows}
        loading={isLoading && !graph}
        emptyState={isError ? null : <EmptyState icon={IconSitemap} label={t("entityBulk.empty")} />}
        onToggle={toggle}
        tierOf={tierOf}
      />
    );
  }

  return (
    <Stack gap="md">
      <CollapsibleHeader title={t("entityBulk.title")} sections={sections}>
        <HierarchyPicker value={hierarchyId} onChange={setStoredHierarchyId} />
        <Tooltip label={t("entityBulk.expandAll")}>
          <ActionIcon variant="default" size="md" aria-label={t("entityBulk.expandAll")} onClick={() => setCollapsed(new Set())}>
            <IconChevronsDown size={16} />
          </ActionIcon>
        </Tooltip>
        <Tooltip label={t("entityBulk.collapseAll")}>
          <ActionIcon
            variant="default"
            size="md"
            aria-label={t("entityBulk.collapseAll")}
            onClick={() => setCollapsed(new Set(branchKeys(roots, "", [])))}
          >
            <IconChevronsUp size={16} />
          </ActionIcon>
        </Tooltip>
      </CollapsibleHeader>

      <BlueprintPickPills
        blueprints={blueprints.map((b) => b.identifier)}
        value={selectedBlueprint ? target : null}
        onChange={setBlueprint}
        tierOf={tierOf}
      />

      {isError && refusedRun.length === 0 && (
        <Alert color="red" variant="light" title={t("entityBulk.loadFailed")}>
          {loadErrorMessage(error, t)}
        </Alert>
      )}

      {body}
    </Stack>
  );
}
