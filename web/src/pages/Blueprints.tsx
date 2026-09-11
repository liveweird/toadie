import { Alert, Anchor, Badge, Box, Button, Group, Stack, Table, Text } from "@mantine/core";
import { useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { Link as RouterLink, useNavigate } from "react-router-dom";
import { IconDownload, IconFileImport, IconPlus, IconSchema } from "@tabler/icons-react";
import { isAdmin } from "../api/session";
import { deleteBlueprint, type Blueprint } from "../api/blueprints";
import ConfirmDeleteModal from "../components/ConfirmDeleteModal";
import EmptyState from "../components/EmptyState";
import LoadingBlock from "../components/LoadingBlock";
import PageHeader from "../components/PageHeader";
import RowEditDelete from "../components/RowEditDelete";
import { useBlueprints } from "../hooks/useBlueprints";
import { useDeleteConfirm } from "../hooks/useDeleteConfirm";
import { blueprintDeleteErrorMessage } from "../utils/blueprintForm";
import { editBlueprintPath, newBlueprintPath } from "../utils/blueprintLinks";
import { entitiesPath } from "../utils/entityLinks";
import { CONTENT_MAX_WIDTH } from "../utils/layout";
import { blueprintsExportJson, downloadJson } from "../utils/ontologyExport";
import { ontologyImportPath } from "../utils/ontologyLinks";
import { loadErrorMessage } from "../utils/saveError";

/**
 * The blueprint registry (`/blueprints`): everyone gets the read-only list; an ADMIN
 * additionally gets New/Edit/Delete — the labels/tags/types registries' pattern, except the
 * editor is a full page (BlueprintEditor) rather than a modal, since a blueprint definition
 * is far larger than one label row.
 */
export default function Blueprints() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { blueprints, loading, error, loadError } = useBlueprints();

  const remove = useDeleteConfirm<Blueprint>({
    mutationFn: (blueprint) => deleteBlueprint(blueprint.id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["blueprints"] }),
    successMessage: t("blueprints.toast.deleted"),
  });

  return (
    <Stack gap="md">
      <PageHeader
        title={t("blueprints.title")}
        description={t("blueprints.intro")}
        actions={
          <Group gap="sm">
            <Button component={RouterLink} to={ontologyImportPath} variant="default" leftSection={<IconFileImport size={16} />}>
              {t("ontology.importLink")}
            </Button>
            <Button
              variant="default"
              leftSection={<IconDownload size={16} />}
              disabled={loading || blueprints.length === 0}
              onClick={() => downloadJson(blueprintsExportJson(blueprints), "toadie-blueprints.json")}
            >
              {t("ontology.export.button")}
            </Button>
            {isAdmin() && (
              <Button component={RouterLink} to={newBlueprintPath} leftSection={<IconPlus size={16} />}>
                {t("blueprints.newBlueprint")}
              </Button>
            )}
          </Group>
        }
      />
      <Box maw={CONTENT_MAX_WIDTH}>
        <Stack>
          {error ? (
            <Alert color="red" variant="light" title={t("blueprints.loadFailed")}>
              {loadErrorMessage(loadError, t)}
            </Alert>
          ) : loading ? (
            <LoadingBlock />
          ) : blueprints.length === 0 ? (
            <EmptyState icon={IconSchema} label={t("blueprints.empty")} />
          ) : (
            <Table>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{t("blueprints.column.identifier")}</Table.Th>
                  <Table.Th>{t("blueprints.column.title")}</Table.Th>
                  <Table.Th>{t("blueprints.column.properties")}</Table.Th>
                  <Table.Th>{t("blueprints.column.relations")}</Table.Th>
                  <Table.Th>{t("blueprints.column.entities")}</Table.Th>
                  {isAdmin() && <Table.Th aria-label={t("common.table.operations")} />}
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {blueprints.map((blueprint) => (
                  <Table.Tr key={blueprint.id}>
                    <Table.Td>
                      <Group gap="xs" wrap="nowrap">
                        <Text size="sm" ff="monospace">
                          {blueprint.identifier}
                        </Text>
                        {blueprint.system && (
                          <Badge variant="outline" color="gray" size="sm">
                            {t("blueprints.systemBadge")}
                          </Badge>
                        )}
                      </Group>
                    </Table.Td>
                    <Table.Td>{blueprint.title}</Table.Td>
                    <Table.Td>
                      <Badge variant="light" size="sm">
                        {Object.keys(blueprint.schema.properties).length}
                      </Badge>
                    </Table.Td>
                    <Table.Td>
                      <Badge variant="light" size="sm">
                        {Object.keys(blueprint.relations).length}
                      </Badge>
                    </Table.Td>
                    <Table.Td>
                      <Anchor component={RouterLink} to={entitiesPath(blueprint.identifier)} size="sm">
                        {t("blueprints.viewEntities")}
                      </Anchor>
                    </Table.Td>
                    {isAdmin() && (
                      <Table.Td>
                        <RowEditDelete
                          name={blueprint.identifier}
                          onEdit={() => navigate(editBlueprintPath(blueprint.id))}
                          onDelete={() => remove.requestDelete(blueprint)}
                          deleteDisabled={blueprint.system}
                          deleteTooltip={t("blueprints.systemDeleteTooltip")}
                        />
                      </Table.Td>
                    )}
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          )}
        </Stack>
      </Box>

      <ConfirmDeleteModal
        confirm={remove}
        title={t("blueprints.deleteTitle")}
        errorTitle={t("blueprints.deleteFailed")}
        body={(blueprint) => t("blueprints.deleteBody", { identifier: blueprint.identifier })}
        errorMessage={(err) => blueprintDeleteErrorMessage(err, t)}
      />
    </Stack>
  );
}
