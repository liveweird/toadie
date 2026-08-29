import { useState } from "react";
import {
  Alert,
  Badge,
  Button,
  Center,
  Container,
  Group,
  Loader,
  Modal,
  MultiSelect,
  Paper,
  Stack,
  Table,
  TagsInput,
  Text,
  TextInput,
  Title,
} from "@mantine/core";
import { useForm } from "@mantine/form";
import { useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { IconPencil, IconPlus, IconTag, IconTrash } from "@tabler/icons-react";
import { isAdmin } from "../api/session";
import { createLabel, deleteLabel, updateLabel, type Label } from "../api/labels";
import ConfirmDeleteModal from "../components/ConfirmDeleteModal";
import EmptyState from "../components/EmptyState";
import { useDeleteConfirm } from "../hooks/useDeleteConfirm";
import { useLabels } from "../hooks/useLabels";
import { ENTITY_KINDS } from "../utils/catalogFileForm";
import {
  emptyLabelForm,
  labelFormValidation,
  labelSaveErrorMessage,
  MAX_LABEL_KEY_LENGTH,
  toLabelBody,
  toLabelFormValues,
  type LabelFormValues,
} from "../utils/labelForm";
import { loadErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";

/**
 * The label registry (`/labels`): everyone gets the read-only list; an ADMIN additionally
 * gets per-label create/edit/delete (a modal per label — unlike the namespaces dictionary
 * this is NOT a whole-document editor). These labels — key, allowed values, allowed kinds —
 * are the ONLY labels catalog-file writes accept.
 */
export default function Labels() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { labels, loading, error, loadError } = useLabels();
  const [editorTarget, setEditorTarget] = useState<Label | "new" | null>(null);

  const remove = useDeleteConfirm<Label>({
    mutationFn: (label) => deleteLabel(label.id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["labels"] }),
    successMessage: t("labels.toast.deleted"),
  });

  return (
    <Container size="md" px={0}>
      <Paper withBorder shadow="sm" p="xl" radius="md">
        <Stack>
          <Group justify="space-between" align="flex-start">
            <Title order={2}>{t("labels.title")}</Title>
            {isAdmin() && (
              <Button leftSection={<IconPlus size={16} />} onClick={() => setEditorTarget("new")}>
                {t("labels.newLabel")}
              </Button>
            )}
          </Group>
          <Text size="sm" c="dimmed">
            {t("labels.intro")}
          </Text>
          {error ? (
            <Alert color="red" variant="light" title={t("labels.loadFailed")}>
              {loadErrorMessage(loadError, t)}
            </Alert>
          ) : loading ? (
            <Center py="xl">
              <Loader />
            </Center>
          ) : labels.length === 0 ? (
            <EmptyState
              icon={<IconTag size={32} stroke={1.2} color="var(--mantine-color-dimmed)" />}
              label={t("labels.empty")}
            />
          ) : (
            <Table>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{t("labels.column.key")}</Table.Th>
                  <Table.Th>{t("labels.column.values")}</Table.Th>
                  <Table.Th>{t("labels.column.kinds")}</Table.Th>
                  {isAdmin() && <Table.Th aria-label={t("common.table.operations")} />}
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {labels.map((label) => (
                  <Table.Tr key={label.id}>
                    <Table.Td>
                      <Text size="sm" ff="monospace">
                        {label.key}
                      </Text>
                    </Table.Td>
                    <Table.Td>
                      <Group gap={4}>
                        {label.values.map((value) => (
                          <Badge key={value} variant="light" size="sm">
                            {value}
                          </Badge>
                        ))}
                      </Group>
                    </Table.Td>
                    <Table.Td>
                      <Group gap={4}>
                        {label.kinds.map((kind) => (
                          <Badge key={kind} color="teal" variant="light" size="sm">
                            {kind}
                          </Badge>
                        ))}
                      </Group>
                    </Table.Td>
                    {isAdmin() && (
                      <Table.Td>
                        <Group gap="xs" justify="flex-end" wrap="nowrap">
                          <Button
                            variant="subtle"
                            size="xs"
                            leftSection={<IconPencil size={14} />}
                            aria-label={t("common.action.editAria", { name: label.key })}
                            onClick={() => setEditorTarget(label)}
                          >
                            {t("common.action.edit")}
                          </Button>
                          <Button
                            variant="subtle"
                            color="red"
                            size="xs"
                            leftSection={<IconTrash size={14} />}
                            aria-label={t("common.action.deleteAria", { name: label.key })}
                            onClick={() => remove.requestDelete(label)}
                          >
                            {t("common.action.delete")}
                          </Button>
                        </Group>
                      </Table.Td>
                    )}
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          )}
        </Stack>
      </Paper>

      {editorTarget !== null && (
        <LabelEditorModal
          target={editorTarget === "new" ? null : editorTarget}
          onClose={() => setEditorTarget(null)}
          onSaved={async () => {
            setEditorTarget(null);
            await queryClient.invalidateQueries({ queryKey: ["labels"] });
          }}
        />
      )}

      <ConfirmDeleteModal
        confirm={remove}
        title={t("labels.deleteTitle")}
        errorTitle={t("labels.deleteFailed")}
        body={(label) => t("labels.deleteBody", { key: label.key })}
      />
    </Container>
  );
}

/** Create (target null) / edit (target set) — one modal, the same field block. */
function LabelEditorModal({
  target,
  onClose,
  onSaved,
}: {
  target: Label | null;
  onClose: () => void;
  onSaved: () => Promise<void>;
}) {
  const { t } = useTranslation();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const form = useForm<LabelFormValues>({
    initialValues: target ? toLabelFormValues(target) : emptyLabelForm(),
    validate: labelFormValidation(t),
  });

  async function save(values: LabelFormValues) {
    setError(null);
    setSubmitting(true);
    try {
      if (target) {
        await updateLabel(target.id, toLabelBody(values));
        showSuccessToast(t("labels.toast.saved"));
      } else {
        await createLabel(toLabelBody(values));
        showSuccessToast(t("labels.toast.created"));
      }
      await onSaved();
    } catch (err) {
      setError(labelSaveErrorMessage(err, t));
      setSubmitting(false);
    }
  }

  return (
    <Modal
      opened
      onClose={onClose}
      title={target ? t("labels.editTitle") : t("labels.createTitle")}
      centered
    >
      <form onSubmit={form.onSubmit(save)} noValidate>
        <Stack>
          <TextInput
            label={t("labels.field.key")}
            description={t("labels.field.keyHint")}
            maxLength={MAX_LABEL_KEY_LENGTH}
            data-autofocus
            {...form.getInputProps("key")}
          />
          <TagsInput
            label={t("labels.field.values")}
            description={t("labels.field.valuesHint")}
            splitChars={[",", " "]}
            {...form.getInputProps("values")}
          />
          <MultiSelect
            label={t("labels.field.kinds")}
            description={t("labels.field.kindsHint")}
            data={[...ENTITY_KINDS]}
            {...form.getInputProps("kinds")}
          />
          {error && (
            <Alert color="red" variant="light">
              {error}
            </Alert>
          )}
          <Group justify="flex-end" gap="sm">
            <Button type="button" variant="default" onClick={onClose} disabled={submitting}>
              {t("common.action.cancel")}
            </Button>
            <Button type="submit" loading={submitting}>
              {t("common.action.save")}
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
