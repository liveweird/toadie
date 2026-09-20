import { useState } from "react";
import { Alert, Anchor, Badge, Button, Group, Menu, Stack, Table, Text } from "@mantine/core";
import { useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import {
  IconDownload,
  IconFileImport,
  IconPencil,
  IconPlus,
  IconRefresh,
  IconSchema,
  IconTrash,
} from "@tabler/icons-react";
import { isAdmin } from "../api/session";
import { deleteBlueprint, type Blueprint } from "../api/blueprints";
import ConfirmDeleteModal from "../components/ConfirmDeleteModal";
import EmptyState from "../components/EmptyState";
import LoadingBlock from "../components/LoadingBlock";
import PageHeader from "../components/PageHeader";
import RowActionsMenu from "../components/RowActionsMenu";
import SyncBlueprintModal from "../components/SyncBlueprintModal";
import SyncStateText, { syncStateSource } from "../components/SyncStateText";
import { useBlueprints } from "../hooks/useBlueprints";
import { useDeleteConfirm } from "../hooks/useDeleteConfirm";
import { blueprintDeleteErrorMessage } from "../utils/blueprintForm";
import { editBlueprintPath, newBlueprintPath } from "../utils/blueprintLinks";
import { toBlueprintSyncTarget, type BlueprintSyncTarget } from "../utils/blueprintSync";
import { entitiesPath } from "../utils/entityLinks";
import { blueprintsExportJson, downloadJson } from "../utils/ontologyExport";
import { ontologyImportPath } from "../utils/ontologyLinks";
import { loadErrorMessage } from "../utils/saveError";

/**
 * The blueprint registry (`/blueprints`): everyone gets the read-only list, including the
 * non-sortable Last-sync column (2.10.0 — `SyncStateText`, the Entities list's own column); an
 * ADMIN additionally gets New blueprint in the header and, per row, a Operations kebab (Edit ·
 * Sync from source, disabled without a `sourceUrl` · a divider · Delete, disabled for a system
 * blueprint) — the labels/tags/types registries' pattern, except the editor is a full page
 * (BlueprintEditor) rather than a modal, since a blueprint definition is far larger than one
 * label row. Export stays a header action, unchanged.
 */
export default function Blueprints() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { blueprints, loading, error, loadError } = useBlueprints();
  const [syncTarget, setSyncTarget] = useState<BlueprintSyncTarget | null>(null);

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
          <Table.ScrollContainer minWidth={900}>
            <Table>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{t("blueprints.column.identifier")}</Table.Th>
                  <Table.Th>{t("blueprints.column.title")}</Table.Th>
                  <Table.Th>{t("blueprints.column.properties")}</Table.Th>
                  <Table.Th>{t("blueprints.column.relations")}</Table.Th>
                  <Table.Th>{t("blueprints.column.entities")}</Table.Th>
                  <Table.Th w={140}>{t("blueprints.column.lastSync")}</Table.Th>
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
                    <Table.Td>
                      <SyncStateText file={syncStateSource(blueprint)} />
                    </Table.Td>
                    {isAdmin() && (
                      <Table.Td style={{ width: 1 }} ta="right">
                        <RowActionsMenu label={t("common.table.operationsAria", { name: blueprint.identifier })}>
                          <Menu.Item
                            component={RouterLink}
                            to={editBlueprintPath(blueprint.id)}
                            leftSection={<IconPencil size={14} />}
                            aria-label={t("common.action.editAria", { name: blueprint.identifier })}
                          >
                            {t("common.action.edit")}
                          </Menu.Item>
                          <Menu.Item
                            leftSection={<IconRefresh size={14} />}
                            aria-label={t("sync.actionAria", { name: blueprint.identifier })}
                            disabled={blueprint.sourceUrl == null}
                            onClick={() => setSyncTarget(toBlueprintSyncTarget(blueprint))}
                          >
                            {t("sync.action")}
                          </Menu.Item>
                          <Menu.Divider />
                          <Menu.Item
                            color="red"
                            leftSection={<IconTrash size={14} />}
                            onClick={() => remove.requestDelete(blueprint)}
                            disabled={blueprint.system}
                            title={blueprint.system ? t("blueprints.systemDeleteTooltip") : undefined}
                            aria-label={t("common.action.deleteAria", { name: blueprint.identifier })}
                          >
                            {t("common.action.delete")}
                          </Menu.Item>
                        </RowActionsMenu>
                      </Table.Td>
                    )}
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </Table.ScrollContainer>
        )}
      </Stack>

      <ConfirmDeleteModal
        confirm={remove}
        title={t("blueprints.deleteTitle")}
        errorTitle={t("blueprints.deleteFailed")}
        body={(blueprint) => t("blueprints.deleteBody", { identifier: blueprint.identifier })}
        errorMessage={(err) => blueprintDeleteErrorMessage(err, t)}
      />
      <SyncBlueprintModal target={syncTarget} onClose={() => setSyncTarget(null)} />
    </Stack>
  );
}
