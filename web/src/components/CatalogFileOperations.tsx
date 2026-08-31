import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import { Button, Menu } from "@mantine/core";
import {
  IconChevronDown,
  IconFileExport,
  IconPencil,
  IconRefresh,
  IconTrash,
  IconUpload,
} from "@tabler/icons-react";
import { editCatalogFilePath } from "../utils/catalogFileLinks";

/** Sync is offered only where the caller actually knows the file's source state. */
export type SyncOperation = {
  onSync: () => void;
  /** False greys the item out — the file carries no source reference to sync from. */
  enabled: boolean;
};

/**
 * The per-entity Operations menu shared by the Files list and the Hierarchy tree: Edit
 * navigates to the editor; Export-as-YAML, Overwrite-with-YAML, Sync-from-source, and Delete
 * are handed back to the page, which owns the download hook, the two modals, and the delete
 * confirm.
 *
 * `sync` is optional because a caller either knows the file's source state or it doesn't —
 * the Files list always passes it (with `enabled` off for a source-less row, so the item
 * greys out instead of vanishing), while the Hierarchy passes nothing: its rows come from
 * the graph payload, which carries no `sourceUrl`, and a permanently-greyed item there would
 * assert something we cannot know. The trigger carries the interpolated
 * `catalog.operationsAria` name — unit tests and the e2e `rowOperation` helper locate rows by
 * it, so the aria shape must not change.
 */
export default function CatalogFileOperations({
  id,
  name,
  downloading,
  onExport,
  onOverwrite,
  onDelete,
  sync,
}: {
  id: number;
  name: string;
  downloading: boolean;
  onExport: () => void;
  onOverwrite: () => void;
  onDelete: () => void;
  sync?: SyncOperation;
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
        <Menu.Item leftSection={<IconFileExport size={14} />} onClick={onExport}>
          {t("catalog.exportFile")}
        </Menu.Item>
        <Menu.Item leftSection={<IconUpload size={14} />} onClick={onOverwrite}>
          {t("catalog.overwrite.action")}
        </Menu.Item>
        {sync && (
          <Menu.Item
            leftSection={<IconRefresh size={14} />}
            onClick={sync.onSync}
            disabled={!sync.enabled}
          >
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
