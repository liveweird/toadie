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
  deleteDisabled = false,
  deleteTooltip,
}: {
  name: string;
  onEdit: () => void;
  onDelete: () => void;
  /** A row that can never be deleted (Blueprints' system rows, Phase 4 v1.26.0). */
  deleteDisabled?: boolean;
  /** Shown instead of the ordinary "Delete" tooltip while `deleteDisabled` — required together. */
  deleteTooltip?: string;
}) {
  const { t } = useTranslation();
  return (
    <Group gap={4} justify="flex-end" wrap="nowrap">
      <Tooltip label={t("common.action.edit")}>
        <ActionIcon size="sm" aria-label={t("common.action.editAria", { name })} onClick={onEdit}>
          <IconPencil size={16} />
        </ActionIcon>
      </Tooltip>
      <Tooltip label={deleteDisabled && deleteTooltip ? deleteTooltip : t("common.action.delete")}>
        {/* Mantine disables pointer events on a disabled ActionIcon, which would also block
            the Tooltip's own hover trigger — the span keeps hover reachable (Mantine's
            documented disabled-button-in-Tooltip pattern). */}
        <span>
          <ActionIcon
            size="sm"
            color="red"
            aria-label={t("common.action.deleteAria", { name })}
            onClick={onDelete}
            disabled={deleteDisabled}
          >
            <IconTrash size={16} />
          </ActionIcon>
        </span>
      </Tooltip>
    </Group>
  );
}
