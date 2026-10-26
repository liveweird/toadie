import { Alert, Anchor, Badge, Button, Group, Stack, Table, Text } from "@mantine/core";
import { IconArrowsExchange, IconListCheck, IconPlus } from "@tabler/icons-react";
import { useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { Link as RouterLink, useNavigate } from "react-router-dom";
import { deleteConversionMapping, type ConversionMapping } from "../api/conversionMappings";
import { isAdmin } from "../api/session";
import ConfirmDeleteModal from "../components/ConfirmDeleteModal";
import DataTable from "../components/DataTable";
import EmptyState from "../components/EmptyState";
import LoadingBlock from "../components/LoadingBlock";
import PageHeader from "../components/PageHeader";
import RowEditDelete from "../components/RowEditDelete";
import { useConversionMappings } from "../hooks/useConversionMappings";
import { useDeleteConfirm } from "../hooks/useDeleteConfirm";
import {
  editConversionMappingPath,
  newConversionMappingPath,
} from "../utils/conversionLinks";
import { conversionMappingDeleteErrorMessage, pairCount } from "../utils/conversionMappingForm";
import { ontologyErrorsPath } from "../utils/ontologyLinks";
import { formatDateTime, relativeTimeAgo } from "../utils/relativeTime";
import { loadErrorMessage } from "../utils/saveError";

/**
 * The conversion-mapping registry (`/ontology/mappings`): everyone reads the list — name, pair
 * count, the recomputed blocking findings (red) and drift (orange) as status chips, creator,
 * updated — and an ADMIN additionally gets New mapping, Edit and Delete. The editor is a full
 * page (a mapping document is far larger than one registry row).
 */
export default function ConversionMappings() {
  const { t, i18n } = useTranslation();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const { mappings, loading, error, loadError } = useConversionMappings();

  const remove = useDeleteConfirm<ConversionMapping>({
    mutationFn: (mapping) => deleteConversionMapping(mapping.id),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["conversionMappings"] });
      await queryClient.invalidateQueries({ queryKey: ["entities", "errors"] });
    },
    successMessage: t("conversion.mappings.toast.deleted"),
  });

  return (
    <Stack gap="md">
      <PageHeader
        title={t("conversion.mappings.title")}
        description={t("conversion.mappings.intro")}
        actions={
          <Group gap="sm">
            <Button component={RouterLink} to={ontologyErrorsPath} variant="default" leftSection={<IconListCheck size={16} />}>
              {t("conversion.mappings.errorsLink")}
            </Button>
            {isAdmin() && (
              <Button component={RouterLink} to={newConversionMappingPath} leftSection={<IconPlus size={16} />}>
                {t("conversion.mappings.newMapping")}
              </Button>
            )}
          </Group>
        }
      />
      {error ? (
        <Alert color="red" variant="light" title={t("conversion.mappings.loadFailed")}>
          {loadErrorMessage(loadError, t)}
        </Alert>
      ) : loading ? (
        <LoadingBlock />
      ) : mappings.length === 0 ? (
        <EmptyState icon={IconArrowsExchange} label={t("conversion.mappings.empty")} />
      ) : (
        <Table.ScrollContainer minWidth={760}>
          <DataTable label={t("conversion.mappings.title")}>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t("conversion.mappings.column.name")}</Table.Th>
                <Table.Th w={80}>{t("conversion.mappings.column.pairs")}</Table.Th>
                <Table.Th>{t("conversion.mappings.column.status")}</Table.Th>
                <Table.Th>{t("conversion.mappings.column.creator")}</Table.Th>
                <Table.Th w={140}>{t("conversion.mappings.column.updated")}</Table.Th>
                {isAdmin() && <Table.Th aria-label={t("common.table.operations")} />}
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {mappings.map((mapping) => (
                <Table.Tr key={mapping.id}>
                  <Table.Td>
                    {isAdmin() ? (
                      <Anchor
                        component={RouterLink}
                        to={editConversionMappingPath(mapping.id)}
                        size="sm"
                        aria-label={t("common.action.editAria", { name: mapping.name })}
                      >
                        {mapping.name}
                      </Anchor>
                    ) : (
                      <Text size="sm">{mapping.name}</Text>
                    )}
                  </Table.Td>
                  <Table.Td>
                    <Badge variant="light" size="sm">
                      {pairCount(mapping)}
                    </Badge>
                  </Table.Td>
                  <Table.Td>
                    <Group gap={6}>
                      {mapping.findings.length === 0 && mapping.drift.length === 0 && (
                        <Badge variant="light" color="teal" size="sm">
                          {t("conversion.mappings.status.ok")}
                        </Badge>
                      )}
                      {mapping.findings.length > 0 && (
                        <Badge variant="light" color="red" size="sm" tt="none">
                          {t("conversion.mappings.status.findings", { count: mapping.findings.length })}
                        </Badge>
                      )}
                      {mapping.drift.length > 0 && (
                        <Badge variant="light" color="orange" size="sm" tt="none">
                          {t("conversion.mappings.status.drift", { count: mapping.drift.length })}
                        </Badge>
                      )}
                    </Group>
                  </Table.Td>
                  <Table.Td>
                    <Text size="sm" c={mapping.creatorDeleted ? "dimmed" : undefined}>
                      {mapping.creatorName}
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <Text size="sm" title={formatDateTime(mapping.updatedAt, i18n.language)}>
                      {relativeTimeAgo(mapping.updatedAt, i18n.language)}
                    </Text>
                  </Table.Td>
                  {isAdmin() && (
                    <Table.Td style={{ width: 1 }} ta="right">
                      <RowEditDelete
                        name={mapping.name}
                        onEdit={() => void navigate(editConversionMappingPath(mapping.id))}
                        onDelete={() => remove.requestDelete(mapping)}
                      />
                    </Table.Td>
                  )}
                </Table.Tr>
              ))}
            </Table.Tbody>
          </DataTable>
        </Table.ScrollContainer>
      )}
      <ConfirmDeleteModal
        confirm={remove}
        title={t("conversion.mappings.deleteTitle")}
        errorTitle={t("conversion.mappings.deleteFailed")}
        body={(mapping) => t("conversion.mappings.deleteBody", { name: mapping.name })}
        errorMessage={(err) => conversionMappingDeleteErrorMessage(err, t)}
      />
    </Stack>
  );
}
