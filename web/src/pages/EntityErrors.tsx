import { useMemo } from "react";
import { useTranslation } from "react-i18next";
import { Link as RouterLink, useNavigate } from "react-router-dom";
import { Alert, Anchor, Badge, Button, Group, Stack, Table, Text } from "@mantine/core";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { IconListCheck } from "@tabler/icons-react";
import {
  getEntityErrors,
  type BlueprintErrorRow,
  type EntityErrorRow,
  type EntityFinding,
  type SavedQueryErrorRow,
} from "../api/entities";
import { getUserId, isAdmin } from "../api/session";
import BlueprintPills from "../components/BlueprintPills";
import EmptyState from "../components/EmptyState";
import EntityErrorsSummaryStrip from "../components/EntityErrorsSummaryStrip";
import EntityGraphFilterControls from "../components/EntityGraphFilterControls";
import FilterPanel from "../components/FilterPanel";
import PageHeader from "../components/PageHeader";
import TableLoadingRow from "../components/TableLoadingRow";
import { useBlueprints } from "../hooks/useBlueprints";
import { useEntityGraphFilterState } from "../hooks/useEntityGraphFilterState";
import { useEntityQuery } from "../hooks/useEntityQuery";
import { isStringArray, useStoredState } from "../hooks/useStoredState";
import { editBlueprintPath } from "../utils/blueprintLinks";
import { classOfEntityCode, colorOfEntityClass, ENTITY_ERROR_CLASSES } from "../utils/entityErrorClasses";
import { editEntityPath, entityGraphPath } from "../utils/entityLinks";
import { loadErrorMessage } from "../utils/saveError";

const COLUMN_COUNT = 4;

/**
 * The Port-world Errors report at /ontology/errors (v2.5.0) — `pages/Errors.tsx`'s twin one
 * level over: a workspace-wide sweep over the active entity/blueprint/saved-query registries
 * (`GET /api/v1/entities/errors`) instead of the catalog's stored files. Four classes in ONE
 * table: stale entities (the same `EntityFindingCode`s a strict save already enforces),
 * unresolved ownership, computed-property health (both report-only, always on a BLUEPRINT
 * row), and broken saved queries (the caller's own + everyone's PUBLIC, re-parsed against the
 * CURRENT active blueprints/hierarchies). `blueprint`/`q`/`team` narrow which entity AND
 * blueprint rows are REPORTED — reference resolution stays workspace-wide, so narrowing never
 * manufactures a finding a wider view wouldn't also show (the catalog Errors report's own
 * REPORTED-vs-SHOWN asymmetry); saved queries are never narrowed by these filters. The
 * error-class chips in the summary strip filter the fetched findings/diagnostics client-side.
 */
export default function EntityErrors() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { blueprints, loading: blueprintsLoading } = useBlueprints();
  const activeBlueprints = useMemo(() => blueprints.map((b) => b.identifier), [blueprints]);
  const filters = useEntityGraphFilterState("entityErrors", activeBlueprints, blueprintsLoading);
  const [classes, setClasses] = useStoredState<string[]>(
    "entityErrors.filter.classes",
    [...ENTITY_ERROR_CLASSES],
    isStringArray,
  );
  const query = useEntityQuery();
  const [, setPickedQuery] = useStoredState<string | null>(
    "entityQuery.picked",
    null,
    (v) => v === null || typeof v === "string",
  );

  const { data, isPending, isError, error } = useQuery({
    queryKey: ["entities", "errors", filters.values],
    queryFn: () => getEntityErrors(filters.values),
    placeholderData: keepPreviousData,
    // Every blueprint pill off = show/report nothing — never fetch (the pills' shared rule).
    enabled: filters.ready && !filters.noBlueprints,
  });

  const noBlueprints = filters.noBlueprints;
  const report = data && !noBlueprints ? data : undefined;

  const classFilter = new Set(classes);
  const entityRows = (report?.entities ?? [])
    .map((row) => ({ ...row, findings: row.findings.filter((f) => classFilter.has(classOfEntityCode(f.code))) }))
    .filter((row) => row.findings.length > 0);
  const blueprintRows = (report?.blueprints ?? [])
    .map((row) => ({ ...row, findings: row.findings.filter((f) => classFilter.has(classOfEntityCode(f.code))) }))
    .filter((row) => row.findings.length > 0);
  const savedQueryRows = classFilter.has("queries")
    ? (report?.savedQueries ?? []).filter((row) => row.diagnostics.length > 0)
    : [];
  const shownErrors =
    entityRows.reduce((sum, row) => sum + row.findings.length, 0) +
    blueprintRows.reduce((sum, row) => sum + row.findings.length, 0) +
    savedQueryRows.reduce((sum, row) => sum + row.diagnostics.length, 0);
  const rowCount = entityRows.length + blueprintRows.length + savedQueryRows.length;

  function openInGraph(row: SavedQueryErrorRow) {
    setPickedQuery(String(row.id));
    query.runText(row.query);
    navigate(entityGraphPath);
  }

  return (
    <Stack gap="md">
      <PageHeader
        title={t("entityErrors.title")}
        toolbar={
          <>
            <FilterPanel
              activeFilterCount={filters.activeFilterCount}
              storageKey="entityErrors"
              trailing={
                <BlueprintPills
                  active={filters.blueprintPills.active}
                  hidden={filters.blueprintPills.hidden}
                  onChange={filters.blueprintPills.setHidden}
                />
              }
            >
              <EntityGraphFilterControls controls={filters.controls} />
            </FilterPanel>
            <Group gap="sm" wrap="wrap" align="center">
              <EntityErrorsSummaryStrip
                report={report}
                classes={classes}
                setClasses={setClasses}
                shownErrors={shownErrors}
              />
            </Group>
          </>
        }
      />

      {isError && (
        <Alert color="red" variant="light" title={t("entityErrors.loadFailed")}>
          {loadErrorMessage(error, t)}
        </Alert>
      )}

      <Table>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>{t("entityErrors.field.subject")}</Table.Th>
            <Table.Th>{t("entityErrors.field.blueprint")}</Table.Th>
            <Table.Th>{t("entityErrors.field.team")}</Table.Th>
            <Table.Th>{t("entityErrors.field.findings")}</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {/* A disabled (noBlueprints) query stays pending forever — fall through to the empty state. */}
          {isPending && !data && !noBlueprints ? (
            <TableLoadingRow colSpan={COLUMN_COUNT} />
          ) : rowCount > 0 ? (
            <>
              {entityRows.map((row) => (
                <EntityRow key={`entity-${row.id}`} row={row} />
              ))}
              {blueprintRows.map((row) => (
                <BlueprintRow key={`blueprint-${row.id}`} row={row} />
              ))}
              {savedQueryRows.map((row) => (
                <SavedQueryRow key={`savedQuery-${row.id}`} row={row} onOpenInGraph={openInGraph} />
              ))}
            </>
          ) : !isError ? (
            <Table.Tr>
              <Table.Td colSpan={COLUMN_COUNT}>
                <EmptyState icon={IconListCheck} label={t("entityErrors.noFindings")} />
              </Table.Td>
            </Table.Tr>
          ) : null}
        </Table.Tbody>
      </Table>
    </Stack>
  );
}

function FindingsList({ findings }: { findings: readonly EntityFinding[] }) {
  const { t } = useTranslation();
  return (
    <Stack gap={4}>
      {findings.map((f, index) => (
        <Group gap={6} wrap="nowrap" key={`${f.field}-${f.code}-${index}`} align="flex-start">
          <Badge
            variant="light"
            size="xs"
            color={colorOfEntityClass(classOfEntityCode(f.code))}
            title={f.message}
            style={{ flexShrink: 0 }}
          >
            {t(`entityErrors.code.${f.code}`)}
          </Badge>
          <Text size="xs" c="dimmed" style={{ flexShrink: 0 }}>
            {f.field}
          </Text>
          <Text size="xs">{t(`entityErrors.explain.${f.code}`)}</Text>
        </Group>
      ))}
    </Stack>
  );
}

function EntityRow({ row }: { row: EntityErrorRow }) {
  const { t } = useTranslation();
  return (
    <Table.Tr>
      <Table.Td style={{ verticalAlign: "top" }}>
        <Group gap={6} wrap="nowrap">
          <Anchor
            component={RouterLink}
            to={editEntityPath(row.id)}
            size="sm"
            ff="monospace"
            aria-label={t("common.action.editAria", { name: row.identifier })}
          >
            {row.identifier}
          </Anchor>
          <Badge variant="outline" color="gray" size="sm">
            {t("entityErrors.subject.entity")}
          </Badge>
        </Group>
      </Table.Td>
      <Table.Td style={{ verticalAlign: "top" }}>
        <Text size="sm">{row.blueprintTitle}</Text>
      </Table.Td>
      <Table.Td style={{ verticalAlign: "top" }}>
        {row.team.length > 0 ? (
          <Group gap={4}>
            {row.team.map((value) => (
              <Badge key={value} variant="light" color="gray">
                {value}
              </Badge>
            ))}
          </Group>
        ) : (
          <Text c="dimmed">—</Text>
        )}
      </Table.Td>
      <Table.Td>
        <FindingsList findings={row.findings} />
      </Table.Td>
    </Table.Tr>
  );
}

function BlueprintRow({ row }: { row: BlueprintErrorRow }) {
  const { t } = useTranslation();
  return (
    <Table.Tr>
      <Table.Td style={{ verticalAlign: "top" }}>
        <Group gap={6} wrap="nowrap">
          {isAdmin() ? (
            <Anchor
              component={RouterLink}
              to={editBlueprintPath(row.id)}
              size="sm"
              ff="monospace"
              aria-label={t("common.action.editAria", { name: row.identifier })}
            >
              {row.identifier}
            </Anchor>
          ) : (
            <Text size="sm" ff="monospace">
              {row.identifier}
            </Text>
          )}
          <Badge variant="outline" color="gray" size="sm">
            {t("entityErrors.subject.blueprint")}
          </Badge>
        </Group>
      </Table.Td>
      <Table.Td style={{ verticalAlign: "top" }}>
        <Text size="sm">{row.title}</Text>
      </Table.Td>
      <Table.Td style={{ verticalAlign: "top" }}>
        <Text c="dimmed">—</Text>
      </Table.Td>
      <Table.Td>
        <FindingsList findings={row.findings} />
      </Table.Td>
    </Table.Tr>
  );
}

function SavedQueryRow({
  row,
  onOpenInGraph,
}: {
  row: SavedQueryErrorRow;
  onOpenInGraph: (row: SavedQueryErrorRow) => void;
}) {
  const { t } = useTranslation();
  const mine = row.createdBy === getUserId();
  return (
    <Table.Tr>
      <Table.Td style={{ verticalAlign: "top" }}>
        <Group gap={6} wrap="nowrap">
          <Text size="sm">{row.name}</Text>
          <Badge variant="outline" color="gray" size="sm">
            {t("entityErrors.subject.savedQuery")}
          </Badge>
          {mine && (
            <Badge variant="light" color="teal" size="sm">
              {t("entityErrors.yours")}
            </Badge>
          )}
        </Group>
      </Table.Td>
      <Table.Td style={{ verticalAlign: "top" }}>
        <Badge variant="light" color="gray" size="sm">
          {row.visibility === "PRIVATE"
            ? t("entityQueries.visibility.private")
            : t("entityQueries.visibility.public")}
        </Badge>
      </Table.Td>
      <Table.Td style={{ verticalAlign: "top" }}>
        <Text c="dimmed">—</Text>
      </Table.Td>
      <Table.Td>
        <Stack gap={4} align="flex-start">
          {row.diagnostics.map((d, index) => (
            <Group gap={6} wrap="nowrap" key={`${d.code}-${index}`} align="flex-start">
              <Badge variant="light" size="xs" color="orange" title={d.message} style={{ flexShrink: 0 }}>
                {t(`entityErrors.code.${d.code}`)}
              </Badge>
              <Text size="xs">{t(`entityErrors.explain.${d.code}`)}</Text>
              {d.line != null && d.column != null && (
                <Text size="xs" c="dimmed" style={{ flexShrink: 0 }}>
                  {t("entityQuery.position", { line: d.line, column: d.column })}
                </Text>
              )}
              {d.suggestion && (
                <Text size="xs" c="dimmed" style={{ flexShrink: 0 }}>
                  {t("entityQuery.didYouMean", { name: d.suggestion })}
                </Text>
              )}
            </Group>
          ))}
          <Button variant="subtle" size="xs" px={0} onClick={() => onOpenInGraph(row)}>
            {t("entityErrors.openInGraph")}
          </Button>
        </Stack>
      </Table.Td>
    </Table.Tr>
  );
}
