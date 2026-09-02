import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import { Alert, Button, Stack, Table, Text } from "@mantine/core";
import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  IconFileDescription,
  IconFileImport,
  IconPlus,
} from "@tabler/icons-react";
import { deleteCatalogFile, listCatalogFiles, type CatalogFileListItem } from "../api/catalogFiles";
import CatalogFileFilterControls from "../components/CatalogFileFilterControls";
import CatalogFileNameLink from "../components/CatalogFileNameLink";
import CatalogFileOperations from "../components/CatalogFileOperations";
import CatalogKindPills from "../components/CatalogKindPills";
import ConfirmDeleteModal from "../components/ConfirmDeleteModal";
import EmptyState from "../components/EmptyState";
import FilterPanel from "../components/FilterPanel";
import LensPicker from "../components/LensPicker";
import PaginationBar from "../components/PaginationBar";
import OverwriteWithYamlModal from "../components/OverwriteWithYamlModal";
import SyncStateText from "../components/SyncStateText";
import SyncCatalogFileModal from "../components/SyncCatalogFileModal";
import SortHeader from "../components/SortHeader";
import TableLoadingRow from "../components/TableLoadingRow";
import { useCatalogDownloads } from "../hooks/useCatalogDownloads";
import { useCatalogFileFilterState } from "../hooks/useCatalogFileFilterState";
import { useDeleteConfirm } from "../hooks/useDeleteConfirm";
import { usePagedSort } from "../hooks/usePagedSort";
import { loadErrorMessage } from "../utils/saveError";
import { formatDate, formatDateTime } from "../utils/relativeTime";
import { importCatalogFilesPath, newCatalogFilePath } from "../utils/catalogFileLinks";
import KindBadge from "../components/KindBadge";
import PageHeader from "../components/PageHeader";

const SORT_FIELDS = ["name", "kind", "namespace", "updatedAt", "lastSyncedAt"] as const;
type SortField = (typeof SORT_FIELDS)[number];

const SETTINGS_KEY = "catalogFiles";

export default function CatalogFiles() {
  const { t, i18n } = useTranslation();
  // The shared filter surface (Hierarchy and Graph carry the same set, per-view persisted).
  const filters = useCatalogFileFilterState(SETTINGS_KEY);

  const queryClient = useQueryClient();
  const downloads = useCatalogDownloads();

  const { page, setPage, pageSize, setPageSize, sortField, sortDir, sortParam, toggleSort } =
    usePagedSort<SortField>("name", filters.deps, {
      key: SETTINGS_KEY,
      sortFields: SORT_FIELDS,
    });

  const query = useQuery({
    queryKey: ["catalogFiles", page, pageSize, sortParam, filters.values],
    queryFn: () => listCatalogFiles({ page, pageSize, sort: sortParam, ...filters.values }),
    placeholderData: keepPreviousData,
    // Every kind pill off = show nothing — never fetch (the API can't say match-nothing).
    enabled: !filters.noKinds,
  });
  // keepPreviousData would keep showing the stale rows while disabled — override, don't
  // rely on the query emptying itself.
  const data = filters.noKinds ? undefined : query.data;
  const { isLoading, isError, error } = query;

  const deleteConfirm = useDeleteConfirm<CatalogFileListItem>({
    mutationFn: (row) => deleteCatalogFile(row.id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["catalogFiles"] }),
    successMessage: t("catalog.toast.deleted"),
  });

  // The Sync-from-repo modal's target row (null = closed); offered only on rows with a
  // source reference.
  const [syncTarget, setSyncTarget] = useState<CatalogFileListItem | null>(null);
  const [overwriteTarget, setOverwriteTarget] = useState<CatalogFileListItem | null>(null);

  const total = data?.total ?? 0;
  const columnCount = 7;

  return (
    <Stack gap="md">
      <PageHeader
        title={t("catalog.title")}
        actions={
          <>
            <Button
              component={RouterLink}
              to={importCatalogFilesPath}
              variant="default"
              leftSection={<IconFileImport size={16} />}
            >
              {t("catalog.import.linkLabel")}
            </Button>
            <Button component={RouterLink} to={newCatalogFilePath} leftSection={<IconPlus size={16} />}>
              {t("catalog.createFile")}
            </Button>
          </>
        }
      />

      <FilterPanel
        activeFilterCount={filters.activeFilterCount}
        storageKey={SETTINGS_KEY}
        aside={<LensPicker values={filters.values} controls={filters.controls} />}
      >
        <CatalogFileFilterControls controls={filters.controls} />
      </FilterPanel>

      <CatalogKindPills kinds={filters.controls.kinds} setKinds={filters.controls.setKinds} />

      {isError && (
        <Alert color="red" variant="light" title={t("catalog.loadFailed")}>
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

      <Table>
        <Table.Thead>
          <Table.Tr>
            <SortHeader
              field="name"
              label={t("common.field.name")}
              activeField={sortField}
              activeDir={sortDir}
              onToggle={toggleSort}
            />
            <SortHeader
              field="namespace"
              label={t("catalog.field.namespace")}
              activeField={sortField}
              activeDir={sortDir}
              onToggle={toggleSort}
            />
            <SortHeader
              field="kind"
              label={t("catalog.field.kind")}
              activeField={sortField}
              activeDir={sortDir}
              onToggle={toggleSort}
            />
            <Table.Th>{t("catalog.field.title")}</Table.Th>
            <SortHeader
              field="updatedAt"
              label={t("catalog.field.updated")}
              activeField={sortField}
              activeDir={sortDir}
              onToggle={toggleSort}
            />
            <SortHeader
              field="lastSyncedAt"
              label={t("catalog.field.lastSync")}
              activeField={sortField}
              activeDir={sortDir}
              onToggle={toggleSort}
            />
            <Table.Th aria-label={t("common.table.operations")} style={{ width: 1 }} />
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {isLoading && !data ? (
            <TableLoadingRow colSpan={columnCount} />
          ) : data && data.items.length > 0 ? (
            data.items.map((file) => (
              <Table.Tr key={file.id}>
                <Table.Td>
                  <CatalogFileNameLink id={file.id} name={file.name} />
                </Table.Td>
                <Table.Td>
                  <Text size="sm">{file.namespace}</Text>
                </Table.Td>
                <Table.Td>
                  <KindBadge kind={file.kind} />
                </Table.Td>
                <Table.Td>
                  <Text size="sm">{file.title ?? ""}</Text>
                </Table.Td>
                <Table.Td>
                  {/* Same treatment as the Last-sync cell: compact date, precise tooltip. */}
                  <Text size="sm" title={formatDateTime(file.updatedAt, i18n.language)}>
                    {formatDate(file.updatedAt, i18n.language)}
                  </Text>
                </Table.Td>
                <Table.Td>
                  <SyncStateText file={file} />
                </Table.Td>
                <Table.Td style={{ width: 1, whiteSpace: "nowrap" }}>
                  <CatalogFileOperations
                    id={file.id}
                    name={file.name}
                    downloading={downloads.downloadingId === file.id}
                    onExport={() => void downloads.handleDownload(file)}
                    onOverwrite={() => setOverwriteTarget(file)}
                    onDelete={() => deleteConfirm.requestDelete(file)}
                    // Always offered, greyed out until the row carries a source reference.
                    sync={{ onSync: () => setSyncTarget(file), enabled: file.sourceUrl != null }}
                  />
                </Table.Td>
              </Table.Tr>
            ))
          ) : !isError ? (
            <Table.Tr>
              <Table.Td colSpan={columnCount}>
                <EmptyState
                  icon={IconFileDescription}
                  label={t("catalog.noFiles")}
                />
              </Table.Td>
            </Table.Tr>
          ) : null}
        </Table.Tbody>
      </Table>

      <PaginationBar
        total={total}
        page={page}
        pageSize={pageSize}
        onPageChange={setPage}
        onPageSizeChange={setPageSize}
      />

      <SyncCatalogFileModal file={syncTarget} onClose={() => setSyncTarget(null)} />

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
