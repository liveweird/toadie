import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import { Button, Menu } from "@mantine/core";
import { IconChevronDown, IconDownload, IconPencil, IconTrash } from "@tabler/icons-react";

/**
 * The per-entity Operations menu shared by the Files list and the Hierarchy tree: Edit
 * navigates to the editor; Download and Delete are handed back to the page, which owns
 * the download hook and the delete confirm. The trigger carries the interpolated
 * `catalog.operationsAria` name — unit tests and the e2e `rowOperation` helper locate
 * rows by it, so the aria shape must not change.
 */
export default function CatalogFileOperations({
  id,
  name,
  downloading,
  onDownload,
  onDelete,
}: {
  id: number;
  name: string;
  downloading: boolean;
  onDownload: () => void;
  onDelete: () => void;
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
          {t("catalog.operations")}
        </Button>
      </Menu.Target>
      <Menu.Dropdown>
        <Menu.Item
          component={RouterLink}
          to={`/catalog-files/${id}/edit`}
          leftSection={<IconPencil size={14} />}
        >
          {t("common.action.edit")}
        </Menu.Item>
        <Menu.Item leftSection={<IconDownload size={14} />} onClick={onDownload}>
          {t("common.action.download")}
        </Menu.Item>
        <Menu.Item color="red" leftSection={<IconTrash size={14} />} onClick={onDelete}>
          {t("common.action.delete")}
        </Menu.Item>
      </Menu.Dropdown>
    </Menu>
  );
}
