import { useState } from "react";
import { Alert, Anchor, Badge, Button, Group, Menu, Select, Stack, Table, Text } from "@mantine/core";
import { useQueryClient } from "@tanstack/react-query";
import type { TFunction } from "i18next";
import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import { IconBox, IconDownload, IconFileImport, IconPencil, IconPlus, IconRefresh, IconTrash } from "@tabler/icons-react";
import type { Blueprint } from "../api/blueprints";
import { deleteEntity, type Entity } from "../api/entities";
import ClearableTextInput from "../components/ClearableTextInput";
import ConfirmDeleteModal from "../components/ConfirmDeleteModal";
import EmptyState from "../components/EmptyState";
import EntityComputedValue from "../components/EntityComputedValue";
import EntityFindingsBadge from "../components/EntityFindingsBadge";
import FilterPanel from "../components/FilterPanel";
import PageHeader from "../components/PageHeader";
import PaginationBar from "../components/PaginationBar";
import RowActionsMenu from "../components/RowActionsMenu";
import SortHeader from "../components/SortHeader";
import SyncEntityModal from "../components/SyncEntityModal";
import SyncStateText, { syncStateSource } from "../components/SyncStateText";
import TableLoadingRow from "../components/TableLoadingRow";
import { useBlueprintParam, useQParam, useTeamParam } from "../hooks/useBlueprintParam";
import { useBlueprints } from "../hooks/useBlueprints";
import { useDeleteConfirm } from "../hooks/useDeleteConfirm";
import { useEntities } from "../hooks/useEntities";
import { useEntityOptions } from "../hooks/useEntityOptions";
import { usePagedSort } from "../hooks/usePagedSort";
import { previewComputedColumns, type ComputedDefinition } from "../utils/computedProperties";
import { entityDeleteErrorMessage, teamValuesOf } from "../utils/entityForm";
import { toSyncTarget, type EntitySyncTarget } from "../utils/entitySync";
import { editEntityPath, newEntityPath } from "../utils/entityLinks";
import { entityExportFileName, entityExportJson, downloadJson } from "../utils/ontologyExport";
import { ontologyImportPath } from "../utils/ontologyLinks";
import { formatDateTime, relativeTimeAgo } from "../utils/relativeTime";
import { loadErrorMessage } from "../utils/saveError";
import { TEAM_BLUEPRINT } from "../utils/systemBlueprints";

const SORT_FIELDS = ["identifier", "title", "updatedAt", "lastSyncedAt"] as const;
type SortField = (typeof SORT_FIELDS)[number];

const SETTINGS_KEY = "entities";
const MAX_COLUMN_PROPERTIES = 3;
const IDENTITY_COLUMN_WIDTH = 260;
const TEAM_COLUMN_WIDTH = 170;
const UPDATED_COLUMN_WIDTH = 140;
const LAST_SYNC_COLUMN_WIDTH = 140;
const OPERATIONS_COLUMN_WIDTH = 48;

// One preview column is either a plain schema property (string/number/boolean, rendered
// inline below) or a computed one (v1.27.0 — rendered through the shared `EntityComputedValue`,
// since its output shape isn't limited to the three schema scalar types). `enum` (schema only)
// drives the type-aware preview width below (2.8.1).
type PreviewColumn =
  | { kind: "schema"; id: string; label: string; type: "string" | "number" | "boolean"; enum: boolean }
  | { kind: "computed"; definition: ComputedDefinition };

/** Type-aware preview column width (2.8.1 — the compact-table pass): booleans and numbers are
 *  narrow regardless of source, a closed string enum is a bit wider, and any other string or
 *  untyped computed value (mirror/aggregation results aren't statically typed) gets the widest
 *  slot. */
function previewColumnWidth(column: PreviewColumn): number {
  if (column.kind === "schema") {
    switch (column.type) {
      case "boolean":
        return 110;
      case "number":
        return 120;
      default:
        return column.enum ? 130 : 170;
    }
  }
  switch (column.definition.type) {
    case "boolean":
      return 110;
    case "number":
      return 120;
    default:
      return 170;
  }
}

function previewColumnKey(column: PreviewColumn): string {
  return column.kind === "schema" ? `schema-${column.id}` : `computed-${column.definition.id}`;
}

function previewColumnLabel(column: PreviewColumn): string {
  return column.kind === "schema" ? column.label : column.definition.title;
}

/** A schema-property preview cell's value — booleans as a Badge, everything else as truncated
 *  text, a dash when unset (the pre-v1.27.0 behavior, unchanged). Computed columns render
 *  through `EntityComputedValue` directly at the call site instead, since "Not available" reads
 *  differently from "never set". */
function schemaPreviewCell(value: unknown, type: "string" | "number" | "boolean", t: TFunction) {
  if (value === undefined || value === null) return <Text c="dimmed">—</Text>;
  if (type === "boolean") {
    return (
      <Badge variant="light" color={value ? "green" : "gray"}>
        {value ? t("entities.field.true") : t("entities.field.false")}
      </Badge>
    );
  }
  return (
    <Text size="sm" truncate="end" maw="100%" title={String(value)}>
      {String(value)}
    </Text>
  );
}

function schemaPreviewColumns(blueprint: Blueprint): PreviewColumn[] {
  return Object.entries(blueprint.schema.properties)
    .filter(([, def]) => def.type === "string" || def.type === "number" || def.type === "boolean")
    .map(([id, def]) => ({
      kind: "schema" as const,
      id,
      label: def.title ?? id,
      type: def.type as "string" | "number" | "boolean",
      enum: Array.isArray(def.enum),
    }));
}

/**
 * The per-blueprint entities list under Port Ontology (`/entities`): pick a blueprint
 * (`?blueprint=`, `hooks/useBlueprintParam.ts`) to see its instances — no blueprint means
 * nothing is fetched, matching the CatalogFiles `noKinds` idiom (the page renders its own
 * "pick a blueprint" empty state instead of an unfiltered, meaningless page). Every
 * authenticated user gets New entity plus, per row, Edit/Export JSON/Delete through the row's
 * Operations kebab (`RowActionsMenu`) — entities are a shared workspace like catalog files,
 * with no admin gate anywhere in this feature.
 *
 * Columns (2.9.0, three previews after 2.8.1's compaction made room for the Last-sync column
 * below): identifier (a mono `Anchor` straight to the editor) with the title as a dimmed
 * second line and the findings badge beside it — the Files name-cell idiom — Team, the
 * selected blueprint's first three string/number/boolean/computed previews at type-aware
 * widths, Updated, Last sync (source references & HTTP re-sync, 2.9.0 — `SyncStateText`, the
 * catalog files' own column one level down), and the row's Operations kebab. The identifier
 * header's `SortHeader` carries a `secondary` slot so Title stays sortable from the same cell.
 */
export default function Entities() {
  const { t, i18n } = useTranslation();
  const queryClient = useQueryClient();
  const { blueprint, setBlueprint } = useBlueprintParam();
  const { team, setTeam } = useTeamParam();
  const { blueprints } = useBlueprints();
  const { options: teamOptions } = useEntityOptions(TEAM_BLUEPRINT);
  const selectedBlueprint = blueprint ? blueprints.find((b) => b.identifier === blueprint) : undefined;
  const { q, setQ } = useQParam();
  const [syncTarget, setSyncTarget] = useState<EntitySyncTarget | null>(null);

  const { page, setPage, pageSize, setPageSize, sortField, sortDir, sortParam, toggleSort } =
    usePagedSort<SortField>("identifier", [blueprint, team, q], {
      key: SETTINGS_KEY,
      sortFields: SORT_FIELDS,
    });

  const query = useEntities({
    blueprint: blueprint ?? undefined,
    team: team ?? undefined,
    q: q || undefined,
    page,
    pageSize,
    sort: sortParam,
  });
  const { data, isLoading, isError, error } = query;

  const deleteConfirm = useDeleteConfirm<Entity>({
    mutationFn: (row) => deleteEntity(row.id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["entities"] }),
    successMessage: t("entities.toast.deleted"),
  });

  const previewColumns: PreviewColumn[] = selectedBlueprint
    ? [
        ...schemaPreviewColumns(selectedBlueprint),
        ...previewComputedColumns(selectedBlueprint).map((definition) => ({ kind: "computed" as const, definition })),
      ].slice(0, MAX_COLUMN_PROPERTIES)
    : [];

  const total = data?.total ?? 0;
  const columnCount = 2 + previewColumns.length + 3;
  const tableMinWidth =
    IDENTITY_COLUMN_WIDTH +
    TEAM_COLUMN_WIDTH +
    previewColumns.reduce((sum, column) => sum + previewColumnWidth(column), 0) +
    UPDATED_COLUMN_WIDTH +
    LAST_SYNC_COLUMN_WIDTH +
    OPERATIONS_COLUMN_WIDTH;

  const knownTeams = new Set(teamOptions.map((e) => e.identifier));
  const teamSelectData = [
    ...teamOptions.map((e) => ({ value: e.identifier, label: `${e.identifier} — ${e.title}` })),
    ...(team && !knownTeams.has(team) ? [{ value: team, label: team }] : []),
  ];

  return (
    <Stack gap="md">
      <PageHeader
        title={t("entities.title")}
        description={t("entities.intro")}
        actions={
          <Group gap="sm">
            <Button component={RouterLink} to={ontologyImportPath} variant="default" leftSection={<IconFileImport size={16} />}>
              {t("ontology.importLink")}
            </Button>
            <Button
              component={RouterLink}
              to={blueprint ? newEntityPath(blueprint) : "#"}
              leftSection={<IconPlus size={16} />}
              disabled={!blueprint}
            >
              {t("entities.newEntity")}
            </Button>
          </Group>
        }
        toolbar={
          <FilterPanel
            storageKey="entities"
            activeFilterCount={(team ? 1 : 0) + (q ? 1 : 0)}
            aside={
              <Select
                aria-label={t("entities.field.blueprint")}
                placeholder={t("entities.pickBlueprint")}
                data={blueprints.map((b) => ({ value: b.identifier, label: b.identifier }))}
                value={blueprint}
                onChange={setBlueprint}
                searchable
                clearable
                w={280}
              />
            }
          >
            <Select
              label={t("entities.filter.team")}
              placeholder={t("entities.filter.anyTeam")}
              data={teamSelectData}
              value={team}
              onChange={setTeam}
              searchable
              clearable
              clearButtonProps={{ "aria-label": t("entities.filter.clearTeam") }}
              w={240}
            />
            <ClearableTextInput label={t("entities.filter.q")} value={q} onChange={setQ} clearLabel={t("entities.filter.clearQ")} />
          </FilterPanel>
        }
      />

      {isError && (
        <Alert color="red" variant="light" title={t("entities.loadFailed")}>
          {loadErrorMessage(error, t)}
        </Alert>
      )}

      <Table.ScrollContainer minWidth={tableMinWidth}>
        <Table layout="fixed">
          <Table.Thead>
            <Table.Tr>
              <SortHeader
                field="identifier"
                label={t("entities.field.identifier")}
                secondary={{ field: "title", label: t("entities.field.title") }}
                activeField={sortField}
                activeDir={sortDir}
                onToggle={toggleSort}
                width={IDENTITY_COLUMN_WIDTH}
              />
              <Table.Th w={TEAM_COLUMN_WIDTH}>{t("entities.column.team")}</Table.Th>
              {previewColumns.map((column) => (
                <Table.Th
                  key={previewColumnKey(column)}
                  w={previewColumnWidth(column)}
                  style={{ overflowWrap: "anywhere", whiteSpace: "normal" }}
                >
                  {previewColumnLabel(column)}
                </Table.Th>
              ))}
              <SortHeader
                field="updatedAt"
                label={t("entities.field.updated")}
                activeField={sortField}
                activeDir={sortDir}
                onToggle={toggleSort}
                width={UPDATED_COLUMN_WIDTH}
              />
              <SortHeader
                field="lastSyncedAt"
                label={t("entities.field.lastSync")}
                activeField={sortField}
                activeDir={sortDir}
                onToggle={toggleSort}
                width={LAST_SYNC_COLUMN_WIDTH}
              />
              <Table.Th aria-label={t("common.table.operations")} w={OPERATIONS_COLUMN_WIDTH} />
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {!blueprint ? (
              <Table.Tr>
                <Table.Td colSpan={columnCount}>
                  <EmptyState icon={IconBox} label={t("entities.pickBlueprintEmpty")} />
                </Table.Td>
              </Table.Tr>
            ) : isLoading && !data ? (
              <TableLoadingRow colSpan={columnCount} />
            ) : data && data.items.length > 0 ? (
              data.items.map((entity) => (
                <Table.Tr key={entity.id}>
                  <Table.Td>
                    <Anchor
                      component={RouterLink}
                      to={editEntityPath(entity.id)}
                      size="sm"
                      ff="monospace"
                      aria-label={t("common.action.editAria", { name: entity.identifier })}
                      style={{ overflowWrap: "anywhere" }}
                    >
                      {entity.identifier}
                    </Anchor>
                    <Group gap="xs" wrap="nowrap">
                      <Text size="xs" c="dimmed" truncate style={{ minWidth: 0 }} title={entity.title}>
                        {entity.title}
                      </Text>
                      <EntityFindingsBadge findings={entity.findings} />
                    </Group>
                  </Table.Td>
                  <Table.Td>
                    {teamValuesOf(entity.team).length > 0 ? (
                      <Group gap={4} style={{ minWidth: 0 }}>
                        {teamValuesOf(entity.team).map((value) => (
                          <Badge
                            key={value}
                            variant="light"
                            color="gray"
                            title={value}
                            style={{ maxWidth: "100%" }}
                          >
                            {value}
                          </Badge>
                        ))}
                      </Group>
                    ) : (
                      <Text c="dimmed">—</Text>
                    )}
                  </Table.Td>
                  {previewColumns.map((column) => (
                    <Table.Td key={previewColumnKey(column)}>
                      {column.kind === "schema" ? (
                        schemaPreviewCell(entity.properties[column.id], column.type, t)
                      ) : (
                        <EntityComputedValue
                          value={entity.properties[column.definition.id]}
                          definition={column.definition}
                          preview
                        />
                      )}
                    </Table.Td>
                  ))}
                  <Table.Td>
                    <Text size="sm" title={formatDateTime(entity.updatedAt, i18n.language)}>
                      {relativeTimeAgo(entity.updatedAt, i18n.language)}
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <SyncStateText file={syncStateSource(entity)} />
                  </Table.Td>
                  <Table.Td style={{ width: 1 }} ta="right">
                    <RowActionsMenu label={t("common.table.operationsAria", { name: entity.identifier })}>
                      <Menu.Item
                        component={RouterLink}
                        to={editEntityPath(entity.id)}
                        leftSection={<IconPencil size={14} />}
                        aria-label={t("common.action.editAria", { name: entity.identifier })}
                      >
                        {t("common.action.edit")}
                      </Menu.Item>
                      <Menu.Item
                        leftSection={<IconDownload size={14} />}
                        aria-label={t("entities.exportAria", { name: entity.identifier })}
                        // Rows can land before the blueprint registry does (the two queries are
                        // independent); the export needs the definition to strip computed ids.
                        disabled={!selectedBlueprint}
                        onClick={() => {
                          if (selectedBlueprint) {
                            downloadJson(entityExportJson(entity, selectedBlueprint), entityExportFileName(entity));
                          }
                        }}
                      >
                        {t("ontology.export.button")}
                      </Menu.Item>
                      <Menu.Item
                        leftSection={<IconRefresh size={14} />}
                        aria-label={t("sync.actionAria", { name: entity.identifier })}
                        disabled={entity.sourceUrl == null}
                        onClick={() => setSyncTarget(toSyncTarget(entity))}
                      >
                        {t("sync.action")}
                      </Menu.Item>
                      <Menu.Divider />
                      <Menu.Item
                        color="red"
                        leftSection={<IconTrash size={14} />}
                        onClick={() => deleteConfirm.requestDelete(entity)}
                        aria-label={t("common.action.deleteAria", { name: entity.identifier })}
                      >
                        {t("common.action.delete")}
                      </Menu.Item>
                    </RowActionsMenu>
                  </Table.Td>
                </Table.Tr>
              ))
            ) : !isError ? (
              <Table.Tr>
                <Table.Td colSpan={columnCount}>
                  <EmptyState icon={IconBox} label={t("entities.empty")} />
                </Table.Td>
              </Table.Tr>
            ) : null}
          </Table.Tbody>
        </Table>
      </Table.ScrollContainer>

      {blueprint && <PaginationBar total={total} page={page} pageSize={pageSize} onPageChange={setPage} onPageSizeChange={setPageSize} />}

      <ConfirmDeleteModal
        confirm={deleteConfirm}
        title={t("entities.deleteTitle")}
        errorTitle={t("entities.deleteFailed")}
        body={(target) => t("entities.deleteBody", { identifier: target.identifier })}
        errorMessage={(err) => entityDeleteErrorMessage(err, t)}
      />
      <SyncEntityModal target={syncTarget} onClose={() => setSyncTarget(null)} />
    </Stack>
  );
}
