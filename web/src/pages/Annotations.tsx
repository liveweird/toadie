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
  Text,
  TextInput,
  Title,
} from "@mantine/core";
import { useForm } from "@mantine/form";
import { useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { IconNote, IconPlus } from "@tabler/icons-react";
import { ApiError } from "../api/http";
import { isAdmin } from "../api/session";
import {
  createAnnotationKey,
  deleteAnnotationKey,
  updateAnnotationKey,
  type AnnotationKey,
} from "../api/annotationKeys";
import ConfirmDeleteModal from "../components/ConfirmDeleteModal";
import EmptyState from "../components/EmptyState";
import KindTierDot, { renderKindOption } from "../components/KindTierDot";
import RowEditDelete from "../components/RowEditDelete";
import { useDeleteConfirm } from "../hooks/useDeleteConfirm";
import { useAnnotationKeys } from "../hooks/useAnnotationKeys";
import { ENTITY_KINDS } from "../utils/catalogFileForm";
import {
  annotationKeyFormValidation,
  annotationKeySaveErrorMessage,
  emptyAnnotationKeyForm,
  MAX_ANNOTATION_KEY_LENGTH,
  toAnnotationKeyBody,
  toAnnotationKeyFormValues,
  type AnnotationKeyFormValues,
} from "../utils/annotationKeyForm";
import { loadErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";

/**
 * The annotation-key registry (`/annotations`) — the Labels page's sibling with the value
 * dimension dropped (annotation VALUES stay free strings): everyone gets the read-only
 * list; an ADMIN additionally gets per-key create/edit/delete in a modal. These keys —
 * each with the kinds it applies to — are the ONLY `metadata.annotations` keys
 * catalog-file writes accept.
 */
export default function Annotations() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { annotationKeys, loading, error, loadError } = useAnnotationKeys();
  const [editorTarget, setEditorTarget] = useState<AnnotationKey | "new" | null>(null);

  const remove = useDeleteConfirm<AnnotationKey>({
    mutationFn: (row) => deleteAnnotationKey(row.id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["annotationKeys"] }),
    successMessage: t("annotations.toast.deleted"),
  });

  return (
    <Container size="md" px={0}>
      <Paper withBorder shadow="sm" p="xl" radius="md">
        <Stack>
          <Group justify="space-between" align="flex-start">
            <Title order={2}>{t("annotations.title")}</Title>
            {isAdmin() && (
              <Button leftSection={<IconPlus size={16} />} onClick={() => setEditorTarget("new")}>
                {t("annotations.newKey")}
              </Button>
            )}
          </Group>
          <Text size="sm" c="dimmed">
            {t("annotations.intro")}
          </Text>
          {error ? (
            <Alert color="red" variant="light" title={t("annotations.loadFailed")}>
              {loadErrorMessage(loadError, t)}
            </Alert>
          ) : loading ? (
            <Center py="xl">
              <Loader />
            </Center>
          ) : annotationKeys.length === 0 ? (
            <EmptyState
              icon={<IconNote size={32} stroke={1.2} color="var(--mantine-color-dimmed)" />}
              label={t("annotations.empty")}
            />
          ) : (
            <Table>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{t("annotations.column.key")}</Table.Th>
                  <Table.Th>{t("annotations.column.kinds")}</Table.Th>
                  {isAdmin() && <Table.Th aria-label={t("common.table.operations")} />}
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {annotationKeys.map((row) => (
                  <Table.Tr key={row.id}>
                    <Table.Td>
                      <Text size="sm" ff="monospace">
                        {row.key}
                      </Text>
                    </Table.Td>
                    <Table.Td>
                      <Group gap={4}>
                        {row.kinds.map((kind) => (
                          <Badge key={kind} color="teal" variant="light" size="sm" leftSection={<KindTierDot kind={kind} />}>
                            {kind}
                          </Badge>
                        ))}
                      </Group>
                    </Table.Td>
                    {isAdmin() && (
                      <Table.Td>
                        <RowEditDelete
                          name={row.key}
                          onEdit={() => setEditorTarget(row)}
                          onDelete={() => remove.requestDelete(row)}
                        />
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
        <AnnotationKeyEditorModal
          target={editorTarget === "new" ? null : editorTarget}
          onClose={() => setEditorTarget(null)}
          onSaved={async () => {
            setEditorTarget(null);
            await queryClient.invalidateQueries({ queryKey: ["annotationKeys"] });
          }}
        />
      )}

      <ConfirmDeleteModal
        confirm={remove}
        title={t("annotations.deleteTitle")}
        errorTitle={t("annotations.deleteFailed")}
        body={(row) => t("annotations.deleteBody", { key: row.key })}
      />
    </Container>
  );
}

/** Create (target null) / edit (target set) — one modal, the same field block. */
function AnnotationKeyEditorModal({
  target,
  onClose,
  onSaved,
}: {
  target: AnnotationKey | null;
  onClose: () => void;
  onSaved: () => Promise<void>;
}) {
  const { t } = useTranslation();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const form = useForm<AnnotationKeyFormValues>({
    initialValues: target ? toAnnotationKeyFormValues(target) : emptyAnnotationKeyForm(),
    validate: annotationKeyFormValidation(t),
  });

  async function save(values: AnnotationKeyFormValues) {
    setError(null);
    setSubmitting(true);
    try {
      if (target) {
        await updateAnnotationKey(target.id, toAnnotationKeyBody(values));
        showSuccessToast(t("annotations.toast.saved"));
      } else {
        await createAnnotationKey(toAnnotationKeyBody(values));
        showSuccessToast(t("annotations.toast.created"));
      }
      await onSaved();
    } catch (err) {
      // The 409 is about THIS control (the case-insensitive key clash) — mark the field, the
      // way the catalog editor puts server verdicts on controls; other failures stay the alert.
      if (err instanceof ApiError && err.status === 409) {
        form.setFieldError("key", t("annotations.saveConflict"));
      } else {
        setError(annotationKeySaveErrorMessage(err, t));
      }
      setSubmitting(false);
    }
  }

  return (
    <Modal
      opened
      onClose={onClose}
      title={target ? t("annotations.editTitle") : t("annotations.createTitle")}
      centered
    >
      <form onSubmit={form.onSubmit(save)} noValidate>
        <Stack>
          <TextInput
            label={t("annotations.field.key")}
            description={t("annotations.field.keyHint")}
            maxLength={MAX_ANNOTATION_KEY_LENGTH}
            data-autofocus
            {...form.getInputProps("key")}
          />
          <MultiSelect
            label={t("annotations.field.kinds")}
            description={t("annotations.field.kindsHint")}
            data={[...ENTITY_KINDS]}
            renderOption={renderKindOption}
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
