import { useTranslation } from "react-i18next";
import { Alert, Badge, Code, Group, Stack, Table, Text } from "@mantine/core";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { IconListCheck } from "@tabler/icons-react";
import { getCatalogErrors } from "../api/catalogFiles";
import CatalogFileNameLink from "../components/CatalogFileNameLink";
import CatalogToolbar from "../components/CatalogToolbar";
import ErrorsSummaryStrip from "../components/ErrorsSummaryStrip";
import EmptyState from "../components/EmptyState";
import TableLoadingRow from "../components/TableLoadingRow";
import { useCatalogFileFilterState } from "../hooks/useCatalogFileFilterState";
import { isStringArray, useStoredState } from "../hooks/useStoredState";
import { loadErrorMessage } from "../utils/saveError";
import { ERROR_CLASSES, classOfStatus, colorOfStatus } from "../utils/errorClasses";
import { groupFindingsByFile } from "../utils/errorGroups";
import KindBadge from "../components/KindBadge";
import PageHeader from "../components/PageHeader";

/**
 * The Errors page at /errors: every error in the stored files — reference resolution,
 * registry findings, structural drift, removed namespaces. The shared filter set narrows
 * which files are REPORTED server-side (references still resolve against the whole
 * workspace, so narrowing never manufactures a MISSING finding — deliberately unlike the
 * Graph, where the same filters decide what is SHOWN); the error-class chips in the summary
 * strip filter the fetched findings client-side. Since v1.20.0 the table is grouped per FILE:
 * STRUCTURE_INVALID findings show the validator's own message inline; every other status
 * carries its static explanation as the badge's hover text.
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
  const shown =
    data && !noKinds ? data.findings.filter((f) => classes.includes(classOfStatus(f.status))) : [];
  // Grouped BY FILE: one row per file with every finding it has — the static explanation
  // rides each status badge as hover text instead of repeating as body text on every row.
  // Client-side and unpaged on purpose: rows are bounded by files-with-findings (already
  // filter-narrowed), an order of magnitude fewer than findings.
  const rows = groupFindingsByFile(shown);
  const columnCount = 4;

  return (
    <Stack gap="md">
      <PageHeader
        title={t("errors.title")}
        toolbar={
          <CatalogToolbar viewKey="errors" filters={filters}>
            <ErrorsSummaryStrip
              report={noKinds ? undefined : data}
              classes={classes}
              setClasses={setClasses}
              shownErrors={shown.length}
            />
          </CatalogToolbar>
        }
      />

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
            <Table.Th>{t("errors.field.findings")}</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {/* A disabled (noKinds) query stays pending forever — fall through to the empty state. */}
          {isPending && !data && !noKinds ? (
            <TableLoadingRow colSpan={columnCount} />
          ) : rows.length > 0 ? (
            rows.map((row) => (
              <Table.Tr key={row.fileId}>
                <Table.Td style={{ verticalAlign: "top" }}>
                  <CatalogFileNameLink id={row.fileId} name={row.fileName} />
                </Table.Td>
                <Table.Td style={{ verticalAlign: "top", width: 130 }}>
                  <KindBadge kind={row.fileKind} />
                </Table.Td>
                <Table.Td style={{ verticalAlign: "top", width: 160 }}>
                  <Text size="sm">{row.fileNamespace}</Text>
                </Table.Td>
                <Table.Td>
                  <Stack gap={4}>
                    {row.findings.map((f, index) => (
                      <Group gap={6} wrap="nowrap" key={`${f.field}-${f.reference}-${index}`}>
                        <Badge
                          variant="light"
                          size="xs"
                          color={colorOfStatus(f.status)}
                          title={f.message ?? t(`errors.message.${f.status}`)}
                          style={{ flexShrink: 0 }}
                        >
                          {t(`errors.status.${f.status}`)}
                        </Badge>
                        {f.field !== "document" && f.field !== "source" && (
                          <Text size="xs" c="dimmed" style={{ flexShrink: 0 }}>
                            {f.field}
                          </Text>
                        )}
                        {f.reference !== "" && <Code>{f.reference}</Code>}
                        {f.status === "STRUCTURE_INVALID" && f.message && (
                          <Text size="xs" truncate>
                            {f.message}
                          </Text>
                        )}
                      </Group>
                    ))}
                  </Stack>
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
