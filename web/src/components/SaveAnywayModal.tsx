import { Button, Code, Group, Modal, Stack, Text } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { DocumentCheckFinding } from "../api/catalogFiles";

/**
 * The Save-anyway confirmation: a strict save was rejected for SOFT findings (unresolved
 * references / registry violations). Lists them in the check panel's vocabulary and offers
 * the explicit waiver — confirming retries the save with `allowInvalid=true`; the stored
 * findings then surface on the Cross-check page until fixed.
 */
export default function SaveAnywayModal({
  findings,
  onCancel,
  onConfirm,
  saving,
}: {
  /** The strict rejection's findings; null keeps the modal closed. */
  findings: DocumentCheckFinding[] | null;
  onCancel: () => void;
  onConfirm: () => void;
  saving: boolean;
}) {
  const { t } = useTranslation();
  return (
    <Modal
      opened={findings !== null}
      onClose={onCancel}
      title={t("catalog.saveAnyway.title")}
      centered
    >
      <Stack gap="sm">
        <Text size="sm">{t("catalog.saveAnyway.intro")}</Text>
        <Stack gap={4}>
          {(findings ?? []).map((f, index) => (
            <Text size="sm" key={`${f.field}-${f.reference}-${index}`}>
              <Code>{f.reference}</Code> ({f.field}) — {t(`crossCheck.message.${f.status}`)}
            </Text>
          ))}
        </Stack>
        <Group justify="flex-end" gap="sm">
          <Button variant="default" onClick={onCancel} disabled={saving}>
            {t("common.action.cancel")}
          </Button>
          <Button color="orange" onClick={onConfirm} loading={saving}>
            {t("catalog.saveAnyway.confirm")}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
