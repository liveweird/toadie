import { ActionIcon, Group } from "@mantine/core";
import { IconArrowDown, IconArrowUp, IconTrash } from "@tabler/icons-react";

/**
 * The up/down/remove control column for ordered-list editors (the Namespaces document
 * editor today; Lettuce shares it across its paragraph/action-item editors). Move buttons
 * disable at the bounds; labels arrive already translated and position-interpolated —
 * unit tests and e2e locate the controls by them.
 */
export default function RowControls({
  index,
  count,
  onMoveUp,
  onMoveDown,
  onRemove,
  moveUpLabel,
  moveDownLabel,
  removeLabel,
}: {
  index: number;
  count: number;
  onMoveUp: () => void;
  onMoveDown: () => void;
  onRemove: () => void;
  moveUpLabel: string;
  moveDownLabel: string;
  removeLabel: string;
}) {
  return (
    <Group gap={4} wrap="nowrap">
      <ActionIcon
        variant="subtle"
        color="gray"
        disabled={index === 0}
        onClick={onMoveUp}
        aria-label={moveUpLabel}
      >
        <IconArrowUp size={16} />
      </ActionIcon>
      <ActionIcon
        variant="subtle"
        color="gray"
        disabled={index === count - 1}
        onClick={onMoveDown}
        aria-label={moveDownLabel}
      >
        <IconArrowDown size={16} />
      </ActionIcon>
      <ActionIcon variant="subtle" color="red" onClick={onRemove} aria-label={removeLabel}>
        <IconTrash size={16} />
      </ActionIcon>
    </Group>
  );
}
