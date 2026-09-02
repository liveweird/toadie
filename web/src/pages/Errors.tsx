import { useTranslation } from "react-i18next";
import { Alert, Badge, Code, Group, Stack, Table, Text } from "@mantine/core";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { IconListCheck } from "@tabler/icons-react";
import { getCatalogErrors } from "../api/catalogFiles";
import CatalogFileFilterControls from "../components/CatalogFileFilterControls";
import CatalogFileNameLink from "../components/CatalogFileNameLink";
import CatalogKindPills from "../components/CatalogKindPills";
import EmptyState from "../components/EmptyState";
import ErrorClassPills from "../components/ErrorClassPills";
import FilterPanel from "../components/FilterPanel";
import LensPicker from "../components/LensPicker";
import TableLoadingRow from "../components/TableLoadingRow";
import { useCatalogFileFilterState } from "../hooks/useCatalogFileFilterState";
import { isStringArray, useStoredState } from "../hooks/useStoredState";
import { loadErrorMessage } from "../utils/saveError";
import { ERROR_CLASSES, classOfStatus, colorOfStatus } from "../utils/errorClasses";
import KindBadge from "../components/KindBadge";
import PageHeader from "../components/PageHeader";

/**
 * The Errors page at /errors: every error in the stored files — reference resolution,
 * registry findings, structural drift, removed namespaces. The shared filter set narrows
 * which files are REPORTED server-side (references still resolve against the whole
 * workspace, so narrowing never manufactures a MISSING finding — deliberately unlike the
 * Graph, where the same filters decide what is SHOWN); the error-class pills filter the fetched findings
 * client-side. STRUCTURE_INVALID rows carry the validator's own message; every other
 * status renders its static explanation.
 */
export default function Errors() {
  const { t } = useTranslation();
  // The Files list's full filter set (per-view persisted under errors.filter.*).
  const filters = useCatalogFileFilterState("errors");
  const [classes, setClasses] = useStoredState<string[]>(
    "errors.filter.classes",
    [...ERROR_CLASSES],
    isStringArray,
  );

  const { data, isPending, isError, error } = useQuery({
    // Under the "catalogFiles" prefix so every catalog mutation's invalidation refreshes it.
    queryKey: ["catalogFiles", "errors", filters.values],
    queryFn: () => getCatalogErrors(filters.values),
    placeholderData: keepPreviousData,
    // Every kind pill off = show nothing — never fetch (the API can't say match-nothing).
    enabled: !filters.noKinds,
  });

  const noKinds = filters.noKinds;
  const findings =
    data && !noKinds ? data.findings.filter((f) => classes.includes(classOfStatus(f.status))) : [];
  const columnCount = 6;

  return (
    <Stack gap="md">
      <PageHeader title={t("errors.title")} />

      <FilterPanel
        activeFilterCount={filters.activeFilterCount}
        storageKey="errors"
        aside={<LensPicker values={filters.values} controls={filters.controls} />}
      >
        <CatalogFileFilterControls controls={filters.controls} />
      </FilterPanel>

      <CatalogKindPills kinds={filters.controls.kinds} setKinds={filters.controls.setKinds} />

      <ErrorClassPills classes={classes} setClasses={setClasses} />

      {data && !noKinds && (
        <Group gap="lg">
          <Text size="sm" c="dimmed">
            {t("errors.summary.files", { count: data.checkedFiles })}
          </Text>
          <Text size="sm" c="dimmed">
            {t("errors.summary.references", { count: data.checkedReferences })}
          </Text>
          <Text size="sm" fw={600} c={findings.length > 0 ? "red" : "teal"}>
            {t("errors.summary.errors", { count: findings.length })}
          </Text>
        </Group>
      )}

      {isError && (
        <Alert color="red" variant="light" title={t("errors.loadFailed")}>
          {loadErrorMessage(error, t)}
        </Alert>
      )}

      <Table>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>{t("errors.field.file")}</Table.Th>
            <Table.Th>{t("errors.field.kind")}</Table.Th>
            <Table.Th>{t("errors.field.namespace")}</Table.Th>
            <Table.Th>{t("errors.field.refField")}</Table.Th>
            <Table.Th>{t("errors.field.reference")}</Table.Th>
            <Table.Th>{t("errors.field.status")}</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {/* A disabled (noKinds) query stays pending forever — fall through to the empty state. */}
          {isPending && !data && !noKinds ? (
            <TableLoadingRow colSpan={columnCount} />
          ) : findings.length > 0 ? (
            findings.map((f, index) => (
              <Table.Tr key={`${f.fileId}-${f.field}-${f.reference}-${index}`}>
                <Table.Td>
                  <CatalogFileNameLink id={f.fileId} name={f.fileName} />
                </Table.Td>
                <Table.Td>
                  <KindBadge kind={f.fileKind} />
                </Table.Td>
                <Table.Td>
                  <Text size="sm">{f.fileNamespace}</Text>
                </Table.Td>
                <Table.Td>
                  <Text size="sm">{f.field}</Text>
                </Table.Td>
                <Table.Td>{f.reference !== "" && <Code>{f.reference}</Code>}</Table.Td>
                <Table.Td>
                  <Badge variant="light" color={colorOfStatus(f.status)}>
                    {t(`errors.status.${f.status}`)}
                  </Badge>
                  <Text size="xs" c="dimmed" mt={2}>
                    {f.message ?? t(`errors.message.${f.status}`)}
                  </Text>
                </Table.Td>
              </Table.Tr>
            ))
          ) : !isError ? (
            <Table.Tr>
              <Table.Td colSpan={columnCount}>
                <EmptyState
                  icon={IconListCheck}
                  label={t("errors.noFindings")}
                />
              </Table.Td>
            </Table.Tr>
          ) : null}
        </Table.Tbody>
      </Table>
    </Stack>
  );
}
