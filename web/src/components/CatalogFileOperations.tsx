import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import { Button, Menu } from "@mantine/core";
import { IconChevronDown, IconDownload, IconPencil, IconRefresh, IconTrash } from "@tabler/icons-react";
import { editCatalogFilePath } from "../utils/catalogFileLinks";

/**
 * The per-entity Operations menu shared by the Files list and the Hierarchy tree: Edit
 * navigates to the editor; Download, Sync-from-repo, and Delete are handed back to the
 * page, which owns the download hook, the sync modal, and the delete confirm. `onSync` is
 * optional — the Files list passes it only for rows carrying a source reference, and the
 * Hierarchy (whose rows have no sync state) never does. The trigger carries the
 * interpolated `catalog.operationsAria` name — unit tests and the e2e `rowOperation`
 * helper locate rows by it, so the aria shape must not change.
 */
export default function CatalogFileOperations({
  id,
  name,
  downloading,
  onDownload,
  onDelete,
  onSync,
}: {
  id: number;
  name: string;
  downloading: boolean;
  onDownload: () => void;
  onDelete: () => void;
  onSync?: () => void;
}) {
  const { t } = useTranslation();
  return (
    <Menu position="bottom-end">
      <Menu.Target>
        <Button
          variant="subtle"
          size="xs"
          rightSection={<IconChevronDown size={14} />}
          loading={downloading}
          aria-label={t("catalog.operationsAria", { name })}
        >
          {t("common.table.operations")}
        </Button>
      </Menu.Target>
      <Menu.Dropdown>
        <Menu.Item
          component={RouterLink}
          to={editCatalogFilePath(id)}
          leftSection={<IconPencil size={14} />}
        >
          {t("common.action.edit")}
        </Menu.Item>
        <Menu.Item leftSection={<IconDownload size={14} />} onClick={onDownload}>
          {t("common.action.download")}
        </Menu.Item>
        {onSync && (
          <Menu.Item leftSection={<IconRefresh size={14} />} onClick={onSync}>
            {t("catalog.sync.action")}
          </Menu.Item>
        )}
        <Menu.Item color="red" leftSection={<IconTrash size={14} />} onClick={onDelete}>
          {t("common.action.delete")}
        </Menu.Item>
      </Menu.Dropdown>
    </Menu>
  );
}
