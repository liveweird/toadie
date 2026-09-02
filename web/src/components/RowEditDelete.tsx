import { ActionIcon, Group, Tooltip } from "@mantine/core";
import { IconPencil, IconTrash } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";

/**
 * The registry pages' per-row Edit/Delete cell (Labels, Tags, Annotations, Types) — one leaf
 * widget instead of four verbatim copies. Two always-available actions do not earn a
 * click-to-reveal menu, so they stay visible as icon buttons with tooltips (v1.19.0), while
 * `name` feeds the interpolated aria-labels (`common.action.editAria`/`deleteAria`), which is
 * what tests and e2e locate the buttons by.
 */
export default function RowEditDelete({
  name,
  onEdit,
  onDelete,
}: {
  name: string;
  onEdit: () => void;
  onDelete: () => void;
}) {
  const { t } = useTranslation();
  return (
    <Group gap={4} justify="flex-end" wrap="nowrap">
      <Tooltip label={t("common.action.edit")}>
        <ActionIcon size="sm" aria-label={t("common.action.editAria", { name })} onClick={onEdit}>
          <IconPencil size={16} />
        </ActionIcon>
      </Tooltip>
      <Tooltip label={t("common.action.delete")}>
        <ActionIcon size="sm" color="red" aria-label={t("common.action.deleteAria", { name })} onClick={onDelete}>
          <IconTrash size={16} />
        </ActionIcon>
      </Tooltip>
    </Group>
  );
}
