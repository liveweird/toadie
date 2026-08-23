import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import { Alert, Badge, Button, Group, Stack, Table, Text, Title } from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { IconDownload, IconFileDescription, IconPencil, IconPlus, IconTrash } from "@tabler/icons-react";
import {
  deleteCatalogFile,
  getCatalogFile,
  listCatalogFiles,
  type CatalogFileListItem,
} from "../api/catalogFiles";
import ClearableTextInput from "../components/ClearableTextInput";
import ConfirmDeleteModal from "../components/ConfirmDeleteModal";
import EmptyState from "../components/EmptyState";
import FilterPanel from "../components/FilterPanel";
import PaginationBar from "../components/PaginationBar";
import SortHeader from "../components/SortHeader";
import TableLoadingRow from "../components/TableLoadingRow";
import { useDeleteConfirm } from "../hooks/useDeleteConfirm";
import { usePagedSort } from "../hooks/usePagedSort";
import { isString, useStoredState } from "../hooks/useStoredState";
import { catalogInfoYaml, downloadYaml } from "../utils/catalogYaml";
import { loadErrorMessage } from "../utils/saveError";

const SORT_FIELDS = ["name", "namespace", "updatedAt"] as const;
type SortField = (typeof SORT_FIELDS)[number];

const SETTINGS_KEY = "catalogFiles";

export default function CatalogFiles() {
  const { t, i18n } = useTranslation();
  const [nameFilter, setNameFilter] = useStoredState(`${SETTINGS_KEY}.filter.name`, "", isString);
  const [namespaceFilter, setNamespaceFilter] = useStoredState(
    `${SETTINGS_KEY}.filter.namespace`,
    "",
    isString,
  );
  const activeFilterCount = (nameFilter.trim() ? 1 : 0) + (namespaceFilter.trim() ? 1 : 0);

  const queryClient = useQueryClient();
  const [downloadError, setDownloadError] = useState(false);

  const [debouncedName] = useDebouncedValue(nameFilter, 300);
  const [debouncedNamespace] = useDebouncedValue(namespaceFilter, 300);

  const { page, setPage, pageSize, setPageSize, sortField, sortDir, sortParam, toggleSort } =
    usePagedSort<SortField>("name", [debouncedName, debouncedNamespace], {
      key: SETTINGS_KEY,
      sortFields: SORT_FIELDS,
    });

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ["catalogFiles", page, pageSize, sortParam, debouncedName, debouncedNamespace],
    queryFn: () =>
      listCatalogFiles({
        page,
        pageSize,
        sort: sortParam,
        name: debouncedName || undefined,
        namespace: debouncedNamespace || undefined,
      }),
    placeholderData: keepPreviousData,
  });

  const deleteConfirm = useDeleteConfirm<CatalogFileListItem>({
    mutationFn: (row) => deleteCatalogFile(row.id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["catalogFiles"] }),
    successMessage: t("catalog.toast.deleted"),
  });

  // The list row carries only the display fields — fetch the full document for the YAML.
  async function handleDownload(row: CatalogFileListItem) {
    setDownloadError(false);
    try {
      const file = await getCatalogFile(row.id);
      downloadYaml(catalogInfoYaml({ metadata: file.metadata, spec: file.spec }));
    } catch {
      setDownloadError(true);
    }
  }

  const total = data?.total ?? 0;
  const columnCount = 7;

  return (
    <Stack gap="md">
      <Title order={2}>{t("catalog.title")}</Title>

      <FilterPanel activeFilterCount={activeFilterCount} storageKey={SETTINGS_KEY}>
        <ClearableTextInput
          label={t("common.field.name")}
          value={nameFilter}
          onChange={setNameFilter}
          clearLabel={t("catalog.clearNameFilter")}
        />
        <ClearableTextInput
          label={t("catalog.field.namespace")}
          value={namespaceFilter}
          onChange={setNamespaceFilter}
          clearLabel={t("catalog.clearNamespaceFilter")}
          placeholder={t("common.filter.exact")}
        />
      </FilterPanel>

      {isError && (
        <Alert color="red" variant="light" title={t("catalog.loadFailed")}>
          {loadErrorMessage(error, t)}
        </Alert>
      )}
      {downloadError && (
        <Alert
          color="red"
          variant="light"
          title={t("catalog.downloadFailed")}
          withCloseButton
          closeButtonLabel={t("common.action.close")}
          onClose={() => setDownloadError(false)}
        >
          {t("common.error.network")}
        </Alert>
      )}

      <Table highlightOnHover withTableBorder verticalSpacing="sm">
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
            <Table.Th>{t("catalog.field.type")}</Table.Th>
            <Table.Th>{t("catalog.field.owner")}</Table.Th>
            <Table.Th>{t("catalog.field.createdBy")}</Table.Th>
            <Table.Th>
              <SortHeader
                field="updatedAt"
                label={t("catalog.field.updated")}
                activeField={sortField}
                activeDir={sortDir}
                onToggle={toggleSort}
              />
            </Table.Th>
            <Table.Th aria-label={t("common.action.edit")} style={{ width: 1 }} />
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
                  {file.title && (
                    <Text size="xs" c="dimmed">
                      {file.title}
                    </Text>
                  )}
                </Table.Td>
                <Table.Td>
                  <Text size="sm">{file.namespace}</Text>
                </Table.Td>
                <Table.Td>
                  <Group gap={6} wrap="nowrap">
                    <Badge variant="light" size="sm">
                      {file.type}
                    </Badge>
                    <Badge variant="outline" size="sm" color="gray">
                      {file.lifecycle}
                    </Badge>
                  </Group>
                </Table.Td>
                <Table.Td>
                  <Text size="sm">{file.owner}</Text>
                </Table.Td>
                <Table.Td>
                  <Text size="sm" c={file.creatorDeleted ? "dimmed" : undefined}>
                    {file.creatorName}
                    {file.creatorDeleted ? t("catalog.deletedSuffix") : ""}
                  </Text>
                </Table.Td>
                <Table.Td>
                  <Text size="sm">{new Date(file.updatedAt).toLocaleDateString(i18n.language)}</Text>
                </Table.Td>
                <Table.Td style={{ width: 1, whiteSpace: "nowrap" }}>
                  <Group gap={4} wrap="nowrap">
                    <Button
                      component={RouterLink}
                      to={`/catalog-files/${file.id}/edit`}
                      variant="subtle"
                      size="xs"
                      leftSection={<IconPencil size={14} />}
                      aria-label={t("catalog.editAria", { name: file.name })}
                    >
                      {t("common.action.edit")}
                    </Button>
                    <Button
                      variant="subtle"
                      size="xs"
                      leftSection={<IconDownload size={14} />}
                      onClick={() => void handleDownload(file)}
                      aria-label={t("catalog.downloadAria", { name: file.name })}
                    >
                      {t("common.action.download")}
                    </Button>
                    <Button
                      color="red"
                      variant="subtle"
                      size="xs"
                      leftSection={<IconTrash size={14} />}
                      onClick={() => deleteConfirm.requestDelete(file)}
                      aria-label={t("catalog.deleteAria", { name: file.name })}
                    >
                      {t("common.action.delete")}
                    </Button>
                  </Group>
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
        rowsPerPageLabelKey="catalog.rowsPerPage"
      />

      <Group justify="flex-end">
        <Button component={RouterLink} to="/catalog-files/new" leftSection={<IconPlus size={16} />}>
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
