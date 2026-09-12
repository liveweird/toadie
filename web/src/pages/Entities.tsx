import { useState } from "react";
import { Alert, Anchor, Badge, Button, Group, Select, Stack, Table, Text } from "@mantine/core";
import { useQueryClient } from "@tanstack/react-query";
import type { TFunction } from "i18next";
import { useTranslation } from "react-i18next";
import { Link as RouterLink, useNavigate } from "react-router-dom";
import { IconBox, IconDownload, IconFileImport, IconPlus } from "@tabler/icons-react";
import type { Blueprint } from "../api/blueprints";
import { deleteEntity, listAllEntities, type Entity } from "../api/entities";
import ClearableTextInput from "../components/ClearableTextInput";
import ConfirmDeleteModal from "../components/ConfirmDeleteModal";
import EmptyState from "../components/EmptyState";
import EntityComputedValue from "../components/EntityComputedValue";
import EntityFindingsBadge from "../components/EntityFindingsBadge";
import PageHeader from "../components/PageHeader";
import PaginationBar from "../components/PaginationBar";
import RowEditDelete from "../components/RowEditDelete";
import SortHeader from "../components/SortHeader";
import TableLoadingRow from "../components/TableLoadingRow";
import { useBlueprintParam, useQParam, useTeamParam } from "../hooks/useBlueprintParam";
import { useBlueprints } from "../hooks/useBlueprints";
import { useDeleteConfirm } from "../hooks/useDeleteConfirm";
import { useEntities } from "../hooks/useEntities";
import { useEntityOptions } from "../hooks/useEntityOptions";
import { usePagedSort } from "../hooks/usePagedSort";
import { previewComputedColumns, type ComputedDefinition } from "../utils/computedProperties";
import { entityDeleteErrorMessage, teamValuesOf } from "../utils/entityForm";
import { editEntityPath, newEntityPath } from "../utils/entityLinks";
import { entitiesExportJson, downloadJson } from "../utils/ontologyExport";
import { ontologyImportPath } from "../utils/ontologyLinks";
import { formatDateTime, relativeTimeAgo } from "../utils/relativeTime";
import { loadErrorMessage } from "../utils/saveError";
import { TEAM_BLUEPRINT } from "../utils/systemBlueprints";

const SORT_FIELDS = ["identifier", "title", "updatedAt"] as const;
type SortField = (typeof SORT_FIELDS)[number];

const SETTINGS_KEY = "entities";
const MAX_COLUMN_PROPERTIES = 4;

// One preview column is either a plain schema property (string/number/boolean, rendered
// inline below) or a computed one (v1.27.0 — rendered through the shared `EntityComputedValue`,
// since its output shape isn't limited to the three schema scalar types).
type PreviewColumn =
  | { kind: "schema"; id: string; label: string; type: "string" | "number" | "boolean" }
  | { kind: "computed"; definition: ComputedDefinition };

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
    <Text size="sm" truncate="end" maw={220} title={String(value)}>
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
    }));
}

/**
 * The per-blueprint entities list under Port Ontology (`/entities`): pick a blueprint
 * (`?blueprint=`, `hooks/useBlueprintParam.ts`) to see its instances — no blueprint means
 * nothing is fetched, matching the CatalogFiles `noKinds` idiom (the page renders its own
 * "pick a blueprint" empty state instead of an unfiltered, meaningless page). Every
 * authenticated user gets New/Edit/Delete (`RowEditDelete`) — entities are a shared workspace
 * like catalog files, with no admin gate anywhere in this feature.
 */
export default function Entities() {
  const { t, i18n } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { blueprint, setBlueprint } = useBlueprintParam();
  const { team, setTeam } = useTeamParam();
  const { blueprints } = useBlueprints();
  const { options: teamOptions } = useEntityOptions(TEAM_BLUEPRINT);
  const selectedBlueprint = blueprint ? blueprints.find((b) => b.identifier === blueprint) : undefined;
  const { q, setQ } = useQParam();
  const [exporting, setExporting] = useState(false);
  const [exportError, setExportError] = useState<string | null>(null);

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

  async function handleExport() {
    if (!blueprint || !selectedBlueprint) return;
    setExporting(true);
    setExportError(null);
    try {
      const rows = await listAllEntities({ blueprint, team: team ?? undefined, q: q || undefined });
      downloadJson(entitiesExportJson(rows, selectedBlueprint), `toadie-entities-${blueprint}.json`);
    } catch {
      setExportError(t("ontology.export.failed"));
    } finally {
      setExporting(false);
    }
  }

  const previewColumns: PreviewColumn[] = selectedBlueprint
    ? [
        ...schemaPreviewColumns(selectedBlueprint),
        ...previewComputedColumns(selectedBlueprint).map((definition) => ({ kind: "computed" as const, definition })),
      ].slice(0, MAX_COLUMN_PROPERTIES)
    : [];

  const total = data?.total ?? 0;
  const columnCount = 4 + previewColumns.length + 2;

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
              variant="default"
              leftSection={<IconDownload size={16} />}
              disabled={!blueprint}
              loading={exporting}
              onClick={() => void handleExport()}
            >
              {t("ontology.export.button")}
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
          <Stack gap="sm">
            <Select
              label={t("entities.field.blueprint")}
              placeholder={t("entities.pickBlueprint")}
              data={blueprints.map((b) => ({ value: b.identifier, label: b.identifier }))}
              value={blueprint}
              onChange={setBlueprint}
              searchable
              clearable
              w={280}
            />
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
          </Stack>
        }
      />

      {isError && (
        <Alert color="red" variant="light" title={t("entities.loadFailed")}>
          {loadErrorMessage(error, t)}
        </Alert>
      )}

      {exportError && (
        <Alert color="red" variant="light">
          {exportError}
        </Alert>
      )}

      <Table.ScrollContainer minWidth={640}>
        <Table layout="fixed">
          <Table.Thead>
            <Table.Tr>
              <SortHeader field="identifier" label={t("entities.field.identifier")} activeField={sortField} activeDir={sortDir} onToggle={toggleSort} />
              <SortHeader field="title" label={t("entities.field.title")} activeField={sortField} activeDir={sortDir} onToggle={toggleSort} />
              <Table.Th>{t("entities.column.team")}</Table.Th>
              {previewColumns.map((column) => (
                <Table.Th key={previewColumnKey(column)}>{previewColumnLabel(column)}</Table.Th>
              ))}
              <Table.Th>{t("entities.column.findings")}</Table.Th>
              <SortHeader field="updatedAt" label={t("entities.field.updated")} activeField={sortField} activeDir={sortDir} onToggle={toggleSort} width={150} />
              <Table.Th aria-label={t("common.table.operations")} w={80} />
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
                    >
                      {entity.identifier}
                    </Anchor>
                  </Table.Td>
                  <Table.Td>{entity.title}</Table.Td>
                  <Table.Td>
                    {teamValuesOf(entity.team).length > 0 ? (
                      <Group gap={4}>
                        {teamValuesOf(entity.team).map((value) => (
                          <Badge key={value} variant="light" color="gray">
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
                        <EntityComputedValue value={entity.properties[column.definition.id]} definition={column.definition} />
                      )}
                    </Table.Td>
                  ))}
                  <Table.Td>
                    <EntityFindingsBadge findings={entity.findings} />
                  </Table.Td>
                  <Table.Td>
                    <Text size="sm" title={formatDateTime(entity.updatedAt, i18n.language)}>
                      {relativeTimeAgo(entity.updatedAt, i18n.language)}
                    </Text>
                  </Table.Td>
                  <Table.Td ta="right">
                    <RowEditDelete
                      name={entity.identifier}
                      onEdit={() => navigate(editEntityPath(entity.id))}
                      onDelete={() => deleteConfirm.requestDelete(entity)}
                    />
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
    </Stack>
  );
}
