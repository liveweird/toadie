import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import { Alert, Badge, Button, Group, Select, Stack, Table, Text, Title } from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  IconDownload,
  IconFileDescription,
  IconFileExport,
  IconFileImport,
  IconPencil,
  IconPlus,
  IconTrash,
} from "@tabler/icons-react";
import { deleteCatalogFile, listCatalogFiles, type CatalogFileListItem } from "../api/catalogFiles";
import { ENTITY_KINDS } from "../utils/catalogFileForm";
import ClearableTextInput from "../components/ClearableTextInput";
import ConfirmDeleteModal from "../components/ConfirmDeleteModal";
import EmptyState from "../components/EmptyState";
import FilterPanel from "../components/FilterPanel";
import PaginationBar from "../components/PaginationBar";
import SortHeader from "../components/SortHeader";
import TableLoadingRow from "../components/TableLoadingRow";
import { useCatalogDownloads } from "../hooks/useCatalogDownloads";
import { useDeleteConfirm } from "../hooks/useDeleteConfirm";
import { usePagedSort } from "../hooks/usePagedSort";
import { isString, useStoredState } from "../hooks/useStoredState";
import { loadErrorMessage } from "../utils/saveError";

const SORT_FIELDS = ["name", "kind", "namespace", "updatedAt"] as const;
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
  const [kindFilter, setKindFilter] = useStoredState(`${SETTINGS_KEY}.filter.kind`, "", isString);
  const activeFilterCount =
    (nameFilter.trim() ? 1 : 0) + (namespaceFilter.trim() ? 1 : 0) + (kindFilter ? 1 : 0);

  const queryClient = useQueryClient();
  const downloads = useCatalogDownloads();

  const [debouncedName] = useDebouncedValue(nameFilter, 300);
  const [debouncedNamespace] = useDebouncedValue(namespaceFilter, 300);

  const { page, setPage, pageSize, setPageSize, sortField, sortDir, sortParam, toggleSort } =
    usePagedSort<SortField>("name", [debouncedName, debouncedNamespace, kindFilter], {
      key: SETTINGS_KEY,
      sortFields: SORT_FIELDS,
    });

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ["catalogFiles", page, pageSize, sortParam, debouncedName, debouncedNamespace, kindFilter],
    queryFn: () =>
      listCatalogFiles({
        page,
        pageSize,
        sort: sortParam,
        name: debouncedName || undefined,
        namespace: debouncedNamespace || undefined,
        kind: kindFilter || undefined,
      }),
    placeholderData: keepPreviousData,
  });

  const deleteConfirm = useDeleteConfirm<CatalogFileListItem>({
    mutationFn: (row) => deleteCatalogFile(row.id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["catalogFiles"] }),
    successMessage: t("catalog.toast.deleted"),
  });

  const total = data?.total ?? 0;
  const columnCount = 8;

  return (
    <Stack gap="md">
      <Title order={2}>{t("catalog.title")}</Title>

      <FilterPanel activeFilterCount={activeFilterCount} storageKey={SETTINGS_KEY}>
        <ClearableTextInput
          label={t("common.field.name")}
          value={nameFilter}
          onChange={setNameFilter}
          clearLabel={t("common.filter.clearName")}
        />
        <ClearableTextInput
          label={t("catalog.field.namespace")}
          value={namespaceFilter}
          onChange={setNamespaceFilter}
          clearLabel={t("common.filter.clearNamespace")}
          placeholder={t("common.filter.exact")}
        />
        <Select
          label={t("catalog.field.kind")}
          placeholder={t("catalog.anyKind")}
          data={[...ENTITY_KINDS]}
          value={kindFilter || null}
          onChange={(v) => setKindFilter(v ?? "")}
          clearable
          clearButtonProps={{ "aria-label": t("catalog.clearKindFilter") }}
        />
      </FilterPanel>

      {isError && (
        <Alert color="red" variant="light" title={t("catalog.loadFailed")}>
          {loadErrorMessage(error, t)}
        </Alert>
      )}
      {downloads.exportError && (
        <Alert
          color="red"
          variant="light"
          title={t("catalog.export.failed")}
          withCloseButton
          closeButtonLabel={t("common.action.close")}
          onClose={downloads.dismissExportError}
        >
          {t("common.error.network")}
        </Alert>
      )}
      {downloads.downloadError && (
        <Alert
          color="red"
          variant="light"
          title={t("catalog.downloadFailed")}
          withCloseButton
          closeButtonLabel={t("common.action.close")}
          onClose={downloads.dismissDownloadError}
        >
          {t("common.error.network")}
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
                field="kind"
                label={t("catalog.field.kind")}
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
                  <Badge variant="light" size="sm">
                    {file.kind}
                  </Badge>
                </Table.Td>
                <Table.Td>
                  <Text size="sm">{file.namespace}</Text>
                </Table.Td>
                <Table.Td>
                  <Group gap={6} wrap="nowrap">
                    {file.type && (
                      <Badge variant="outline" size="sm" color="gray">
                        {file.type}
                      </Badge>
                    )}
                    {file.lifecycle && (
                      <Badge variant="outline" size="sm" color="gray">
                        {file.lifecycle}
                      </Badge>
                    )}
                  </Group>
                </Table.Td>
                <Table.Td>
                  <Text size="sm">{file.owner ?? ""}</Text>
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
                      aria-label={t("common.action.editAria", { name: file.name })}
                    >
                      {t("common.action.edit")}
                    </Button>
                    <Button
                      variant="subtle"
                      size="xs"
                      leftSection={<IconDownload size={14} />}
                      onClick={() => void downloads.handleDownload(file)}
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
                      aria-label={t("common.action.deleteAria", { name: file.name })}
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
      />

      <Group justify="flex-end">
        <Button
          component={RouterLink}
          to="/catalog-files/import"
          variant="default"
          leftSection={<IconFileImport size={16} />}
        >
          {t("catalog.import.linkLabel")}
        </Button>
        <Button
          variant="default"
          leftSection={<IconFileExport size={16} />}
          onClick={() => void downloads.handleExport(namespaceFilter.trim() || undefined)}
          disabled={total === 0}
        >
          {t("catalog.export.button")}
        </Button>
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
