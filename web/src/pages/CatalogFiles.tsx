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
import CatalogFileDrawer from "../components/CatalogFileDrawer";
import CatalogFileNameLink from "../components/CatalogFileNameLink";
import CatalogToolbar from "../components/CatalogToolbar";
import CatalogFileOperations from "../components/CatalogFileOperations";
import ConfirmDeleteModal from "../components/ConfirmDeleteModal";
import EmptyState from "../components/EmptyState";
import PaginationBar from "../components/PaginationBar";
import OverwriteWithYamlModal from "../components/OverwriteWithYamlModal";
import SyncStateText from "../components/SyncStateText";
import SyncCatalogFileModal from "../components/SyncCatalogFileModal";
import SortHeader from "../components/SortHeader";
import TableLoadingRow from "../components/TableLoadingRow";
import { useCatalogDownloads } from "../hooks/useCatalogDownloads";
import { useQuickViewParam } from "../hooks/useQuickViewParam";
import { useCatalogFileFilterState } from "../hooks/useCatalogFileFilterState";
import { useDeleteConfirm } from "../hooks/useDeleteConfirm";
import { usePagedSort } from "../hooks/usePagedSort";
import { loadErrorMessage } from "../utils/saveError";
import { formatDateTime, relativeTimeAgo } from "../utils/relativeTime";
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
  const quickView = useQuickViewParam();

  const total = data?.total ?? 0;
  const columnCount = 6;

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
        toolbar={<CatalogToolbar viewKey={SETTINGS_KEY} filters={filters} />}
      />

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

      <Table.ScrollContainer minWidth={760}>
      <Table layout="fixed">
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
              field="kind"
              label={t("catalog.field.kind")}
              activeField={sortField}
              activeDir={sortDir}
              onToggle={toggleSort}
              width={130}
            />
            <SortHeader
              field="namespace"
              label={t("catalog.field.namespace")}
              activeField={sortField}
              activeDir={sortDir}
              onToggle={toggleSort}
              width={160}
            />
            <SortHeader
              field="updatedAt"
              label={t("catalog.field.updated")}
              activeField={sortField}
              activeDir={sortDir}
              onToggle={toggleSort}
              width={150}
            />
            <SortHeader
              field="lastSyncedAt"
              label={t("catalog.field.lastSync")}
              activeField={sortField}
              activeDir={sortDir}
              onToggle={toggleSort}
              width={200}
            />
            <Table.Th aria-label={t("common.table.operations")} w={48} />
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {isLoading && !data ? (
            <TableLoadingRow colSpan={columnCount} />
          ) : data && data.items.length > 0 ? (
            data.items.map((file) => (
              <Table.Tr key={file.id}>
                <Table.Td>
                  {/* Name over title: the title was never sortable, and one cell keeps the
                      Name column the only wide one at 1920. */}
                  <CatalogFileNameLink id={file.id} name={file.name} />
                  {file.title && (
                    <Text size="xs" c="dimmed" truncate>
                      {file.title}
                    </Text>
                  )}
                </Table.Td>
                <Table.Td>
                  <KindBadge kind={file.kind} />
                </Table.Td>
                <Table.Td>
                  <Text size="sm">{file.namespace}</Text>
                </Table.Td>
                <Table.Td>
                  {/* Relative, with the precise timestamp as the hover text (the SyncStateText idiom). */}
                  <Text size="sm" title={formatDateTime(file.updatedAt, i18n.language)}>
                    {relativeTimeAgo(file.updatedAt, i18n.language)}
                  </Text>
                </Table.Td>
                <Table.Td>
                  <SyncStateText file={file} />
                </Table.Td>
                <Table.Td ta="right">
                  <CatalogFileOperations
                    id={file.id}
                    name={file.name}
                    downloading={downloads.downloadingId === file.id}
                    onExport={() => void downloads.handleDownload(file)}
                    onOverwrite={() => setOverwriteTarget(file)}
                    onDelete={() => deleteConfirm.requestDelete(file)}
                    // Always offered, greyed out until the row carries a source reference.
                    sync={{ onSync: () => setSyncTarget(file), enabled: file.sourceUrl != null }}
                    onQuickView={() => quickView.open(file.id)}
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
      </Table.ScrollContainer>

      <PaginationBar
        total={total}
        page={page}
        pageSize={pageSize}
        onPageChange={setPage}
        onPageSizeChange={setPageSize}
      />

      <CatalogFileDrawer />

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
