import type { ReactNode } from "react";
import { Button, Group, Modal, Stack, Text } from "@mantine/core";

/**
 * The shared confirm-before-acting modal (discard, and future non-row-deletion confirms):
 * a message plus a neutral cancel and a red confirm. Labels arrive already translated —
 * they differ per flow (Keep editing/Discard, …). Row-deletion flows paired with
 * useDeleteConfirm keep using ConfirmDeleteModal instead. (Lettuce's version also carries
 * a navigation confirm and a color override — re-port those with their first consumer.)
 */
export default function ConfirmActionModal({
  opened,
  onClose,
  title,
  message,
  cancelLabel,
  confirmLabel,
  onConfirm,
}: {
  opened: boolean;
  onClose: () => void;
  title: string;
  message: ReactNode;
  cancelLabel: string;
  confirmLabel: string;
  onConfirm: () => void;
}) {
  return (
    <Modal opened={opened} onClose={onClose} title={title} centered>
      <Stack gap="md">
        <Text>{message}</Text>
        <Group justify="flex-end" gap="sm">
          <Button variant="default" onClick={onClose}>
            {cancelLabel}
          </Button>
          <Button color="red" onClick={onConfirm}>
            {confirmLabel}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
