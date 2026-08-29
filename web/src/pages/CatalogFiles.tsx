import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import { Alert, Badge, Button, Group, Stack, Table, Text, Title } from "@mantine/core";
import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  IconFileDescription,
  IconFileExport,
  IconFileImport,
  IconPlus,
} from "@tabler/icons-react";
import { deleteCatalogFile, listCatalogFiles, type CatalogFileListItem } from "../api/catalogFiles";
import CatalogFileFilterControls from "../components/CatalogFileFilterControls";
import CatalogFileOperations from "../components/CatalogFileOperations";
import ConfirmDeleteModal from "../components/ConfirmDeleteModal";
import EmptyState from "../components/EmptyState";
import FilterPanel from "../components/FilterPanel";
import PaginationBar from "../components/PaginationBar";
import SortHeader from "../components/SortHeader";
import TableLoadingRow from "../components/TableLoadingRow";
import { useCatalogDownloads } from "../hooks/useCatalogDownloads";
import { useCatalogFileFilterState } from "../hooks/useCatalogFileFilterState";
import { useDeleteConfirm } from "../hooks/useDeleteConfirm";
import { usePagedSort } from "../hooks/usePagedSort";
import { loadErrorMessage } from "../utils/saveError";
import { importCatalogFilesPath, newCatalogFilePath } from "../utils/catalogFileLinks";

const SORT_FIELDS = ["name", "kind", "namespace", "updatedAt"] as const;
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

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ["catalogFiles", page, pageSize, sortParam, filters.values],
    queryFn: () => listCatalogFiles({ page, pageSize, sort: sortParam, ...filters.values }),
    placeholderData: keepPreviousData,
  });

  const deleteConfirm = useDeleteConfirm<CatalogFileListItem>({
    mutationFn: (row) => deleteCatalogFile(row.id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["catalogFiles"] }),
    successMessage: t("catalog.toast.deleted"),
  });

  const total = data?.total ?? 0;
  const columnCount = 6;

  return (
    <Stack gap="md">
      <Title order={2}>{t("catalog.title")}</Title>

      <FilterPanel activeFilterCount={filters.activeFilterCount} storageKey={SETTINGS_KEY}>
        <CatalogFileFilterControls controls={filters.controls} />
      </FilterPanel>

      {isError && (
        <Alert color="red" variant="light" title={t("catalog.loadFailed")}>
          {loadErrorMessage(error, t)}
        </Alert>
      )}
      {downloads.exportError != null && (
        <Alert
          color="red"
          variant="light"
          title={t("catalog.export.failed")}
          withCloseButton
          closeButtonLabel={t("common.action.close")}
          onClose={downloads.dismissExportError}
        >
          {loadErrorMessage(downloads.exportError, t)}
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

      <Table withTableBorder>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>
              <SortHeader
                field="name"
                label={t("common.field.name")}
                activeField={sortField}
                activeDir={sortDir}
                onToggle={toggleSort}
              />
            </Table.Th>
            <Table.Th>
              <SortHeader
                field="namespace"
                label={t("catalog.field.namespace")}
                activeField={sortField}
                activeDir={sortDir}
                onToggle={toggleSort}
              />
            </Table.Th>
            <Table.Th>
              <SortHeader
                field="kind"
                label={t("catalog.field.kind")}
                activeField={sortField}
                activeDir={sortDir}
                onToggle={toggleSort}
              />
            </Table.Th>
            <Table.Th>{t("catalog.field.title")}</Table.Th>
            <Table.Th>
              <SortHeader
                field="updatedAt"
                label={t("catalog.field.updated")}
                activeField={sortField}
                activeDir={sortDir}
                onToggle={toggleSort}
              />
            </Table.Th>
            <Table.Th aria-label={t("catalog.operations")} style={{ width: 1 }} />
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {isLoading && !data ? (
            <TableLoadingRow colSpan={columnCount} />
          ) : data && data.items.length > 0 ? (
            data.items.map((file) => (
              <Table.Tr key={file.id}>
                <Table.Td>
                  <Text size="sm" fw={500}>
                    {file.name}
                  </Text>
                </Table.Td>
                <Table.Td>
                  <Text size="sm">{file.namespace}</Text>
                </Table.Td>
                <Table.Td>
                  <Badge variant="light" size="sm">
                    {file.kind}
                  </Badge>
                </Table.Td>
                <Table.Td>
                  <Text size="sm">{file.title ?? ""}</Text>
                </Table.Td>
                <Table.Td>
                  <Text size="sm">{new Date(file.updatedAt).toLocaleDateString(i18n.language)}</Text>
                </Table.Td>
                <Table.Td style={{ width: 1, whiteSpace: "nowrap" }}>
                  <CatalogFileOperations
                    id={file.id}
                    name={file.name}
                    downloading={downloads.downloadingId === file.id}
                    onDownload={() => void downloads.handleDownload(file)}
                    onDelete={() => deleteConfirm.requestDelete(file)}
                  />
                </Table.Td>
              </Table.Tr>
            ))
          ) : !isError ? (
            <Table.Tr>
              <Table.Td colSpan={columnCount}>
                <EmptyState
                  icon={<IconFileDescription size={32} stroke={1.2} color="var(--mantine-color-dimmed)" />}
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

      <Group justify="flex-end">
        <Button
          component={RouterLink}
          to={importCatalogFilesPath}
          variant="default"
          leftSection={<IconFileImport size={16} />}
        >
          {t("catalog.import.linkLabel")}
        </Button>
        <Button
          variant="default"
          leftSection={<IconFileExport size={16} />}
          onClick={() => void downloads.handleExport(filters.values.namespace)}
          loading={downloads.exporting}
          disabled={total === 0}
        >
          {t("catalog.export.button")}
        </Button>
        <Button component={RouterLink} to={newCatalogFilePath} leftSection={<IconPlus size={16} />}>
          {t("catalog.createFile")}
        </Button>
      </Group>

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
