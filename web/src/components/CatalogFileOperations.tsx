import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import { Menu } from "@mantine/core";
import {
  IconFileExport,
  IconPencil,
  IconPin,
  IconPinnedOff,
  IconRefresh,
  IconTrash,
  IconUpload,
} from "@tabler/icons-react";
import { editCatalogFilePath } from "../utils/catalogFileLinks";
import RowActionsMenu from "./RowActionsMenu";

/** Pin is offered only by a view that NESTS rows, and so has a subtree to focus. */
export type PinOperation = {
  onToggle: () => void;
  /** True while THIS row is the pinned one — the item then reads Unpin. */
  pinned: boolean;
};

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
 * `pin` is optional for the same reason as `sync`, from the other side: only the Hierarchy
 * nests rows, so only it can focus an entity and its descendants — the flat Files list passes
 * nothing and never shows the item. It sits FIRST, above a divider, because it acts on the
 * VIEW while everything below it acts on the file.
 *
 * `sync` is optional because a caller either knows the file's source state or it doesn't —
 * the Files list always passes it (with `enabled` off for a source-less row, so the item
 * greys out instead of vanishing), while the Hierarchy passes nothing: its rows come from
 * the graph payload, which carries no `sourceUrl`, and a permanently-greyed item there would
 * assert something we cannot know. The trigger (the shared `RowActionsMenu` kebab) carries the
 * interpolated `common.table.operationsAria` name — unit tests and the e2e `rowOperation`
 * helper locate rows by it, so the aria shape must not change.
 */
export default function CatalogFileOperations({
  id,
  name,
  downloading,
  onExport,
  onOverwrite,
  onDelete,
  sync,
  pin,
}: {
  id: number;
  name: string;
  downloading: boolean;
  onExport: () => void;
  onOverwrite: () => void;
  onDelete: () => void;
  sync?: SyncOperation;
  pin?: PinOperation;
}) {
  const { t } = useTranslation();
  return (
    <RowActionsMenu label={t("common.table.operationsAria", { name })} loading={downloading}>
      {pin && (
        <>
          <Menu.Item
            leftSection={pin.pinned ? <IconPinnedOff size={14} /> : <IconPin size={14} />}
            onClick={pin.onToggle}
          >
            {t(pin.pinned ? "catalog.pin.clear" : "catalog.pin.action")}
          </Menu.Item>
          <Menu.Divider />
        </>
      )}
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
    </RowActionsMenu>
  );
}
