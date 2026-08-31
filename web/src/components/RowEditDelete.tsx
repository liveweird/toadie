import { Button, Group } from "@mantine/core";
import { IconPencil, IconTrash } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";

/**
 * The registry pages' per-row Edit/Delete cell (Labels, Tags, Annotations, Types) — one leaf
 * widget instead of four verbatim copies. `name` feeds the interpolated aria-labels
 * (`common.action.editAria`/`deleteAria`), which is what tests and e2e locate the buttons by.
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
    <Group gap="xs" justify="flex-end" wrap="nowrap">
      <Button
        variant="subtle"
        size="xs"
        leftSection={<IconPencil size={14} />}
        aria-label={t("common.action.editAria", { name })}
        onClick={onEdit}
      >
        {t("common.action.edit")}
      </Button>
      <Button
        variant="subtle"
        color="red"
        size="xs"
        leftSection={<IconTrash size={14} />}
        aria-label={t("common.action.deleteAria", { name })}
        onClick={onDelete}
      >
        {t("common.action.delete")}
      </Button>
    </Group>
  );
}
