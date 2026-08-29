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
  Paper,
  Select,
  Stack,
  Table,
  TagsInput,
  Text,
  Title,
} from "@mantine/core";
import { useForm } from "@mantine/form";
import { useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { IconCategory, IconPencil, IconPlus, IconTrash } from "@tabler/icons-react";
import { isAdmin } from "../api/session";
import {
  createEntityTypes,
  deleteEntityTypes,
  updateEntityTypes,
  type EntityTypes,
} from "../api/entityTypes";
import ConfirmDeleteModal from "../components/ConfirmDeleteModal";
import EmptyState from "../components/EmptyState";
import { useDeleteConfirm } from "../hooks/useDeleteConfirm";
import { useEntityTypes } from "../hooks/useEntityTypes";
import {
  emptyEntityTypesForm,
  entityTypesFormValidation,
  entityTypesSaveErrorMessage,
  toEntityTypesBody,
  toEntityTypesFormValues,
  TYPE_BEARING_KINDS,
  type EntityTypesFormValues,
} from "../utils/entityTypeForm";
import { loadErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";

/**
 * The per-kind type dictionaries (`/types`) — an internal Toadie constraint on the open
 * `spec.type` field, not part of the Backstage schema: everyone gets the read-only list; an
 * ADMIN additionally gets per-dictionary create/edit/delete (the Labels/Tags modal
 * pattern). One dictionary per type-bearing kind; the dictionaries are independent of one
 * another, and their values are the ONLY spec.type values catalog-file writes accept.
 */
export default function Types() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { dictionaries, loading, error, loadError } = useEntityTypes();
  const [editorTarget, setEditorTarget] = useState<EntityTypes | "new" | null>(null);

  const remove = useDeleteConfirm<EntityTypes>({
    mutationFn: (dictionary) => deleteEntityTypes(dictionary.id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["entityTypes"] }),
    successMessage: t("types.toast.deleted"),
  });

  return (
    <Container size="md" px={0}>
      <Paper withBorder shadow="sm" p="xl" radius="md">
        <Stack>
          <Group justify="space-between" align="flex-start">
            <Title order={2}>{t("types.title")}</Title>
            {isAdmin() && (
              <Button leftSection={<IconPlus size={16} />} onClick={() => setEditorTarget("new")}>
                {t("types.newDictionary")}
              </Button>
            )}
          </Group>
          <Text size="sm" c="dimmed">
            {t("types.intro")}
          </Text>
          {error ? (
            <Alert color="red" variant="light" title={t("types.loadFailed")}>
              {loadErrorMessage(loadError, t)}
            </Alert>
          ) : loading ? (
            <Center py="xl">
              <Loader />
            </Center>
          ) : dictionaries.length === 0 ? (
            <EmptyState
              icon={<IconCategory size={32} stroke={1.2} color="var(--mantine-color-dimmed)" />}
              label={t("types.empty")}
            />
          ) : (
            <Table>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{t("types.column.kind")}</Table.Th>
                  <Table.Th>{t("types.column.types")}</Table.Th>
                  {isAdmin() && <Table.Th aria-label={t("common.table.operations")} />}
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {dictionaries.map((dictionary) => (
                  <Table.Tr key={dictionary.id}>
                    <Table.Td>
                      <Badge color="teal" variant="light" size="sm">
                        {dictionary.kind}
                      </Badge>
                    </Table.Td>
                    <Table.Td>
                      <Group gap={4}>
                        {dictionary.types.map((type) => (
                          <Badge key={type} variant="light" size="sm">
                            {type}
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
                            aria-label={t("common.action.editAria", { name: dictionary.kind })}
                            onClick={() => setEditorTarget(dictionary)}
                          >
                            {t("common.action.edit")}
                          </Button>
                          <Button
                            variant="subtle"
                            color="red"
                            size="xs"
                            leftSection={<IconTrash size={14} />}
                            aria-label={t("common.action.deleteAria", { name: dictionary.kind })}
                            onClick={() => remove.requestDelete(dictionary)}
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
        <EntityTypesEditorModal
          target={editorTarget === "new" ? null : editorTarget}
          onClose={() => setEditorTarget(null)}
          onSaved={async () => {
            setEditorTarget(null);
            await queryClient.invalidateQueries({ queryKey: ["entityTypes"] });
          }}
        />
      )}

      <ConfirmDeleteModal
        confirm={remove}
        title={t("types.deleteTitle")}
        errorTitle={t("types.deleteFailed")}
        body={(dictionary) => t("types.deleteBody", { kind: dictionary.kind })}
      />
    </Container>
  );
}

/** Create (target null) / edit (target set) — one modal, the same field block. */
function EntityTypesEditorModal({
  target,
  onClose,
  onSaved,
}: {
  target: EntityTypes | null;
  onClose: () => void;
  onSaved: () => Promise<void>;
}) {
  const { t } = useTranslation();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const form = useForm<EntityTypesFormValues>({
    initialValues: target ? toEntityTypesFormValues(target) : emptyEntityTypesForm(),
    validate: entityTypesFormValidation(t),
  });

  async function save(values: EntityTypesFormValues) {
    setError(null);
    setSubmitting(true);
    try {
      if (target) {
        await updateEntityTypes(target.id, toEntityTypesBody(values));
        showSuccessToast(t("types.toast.saved"));
      } else {
        await createEntityTypes(toEntityTypesBody(values));
        showSuccessToast(t("types.toast.created"));
      }
      await onSaved();
    } catch (err) {
      setError(entityTypesSaveErrorMessage(err, t));
      setSubmitting(false);
    }
  }

  return (
    <Modal
      opened
      onClose={onClose}
      title={target ? t("types.editTitle") : t("types.createTitle")}
      centered
    >
      <form onSubmit={form.onSubmit(save)} noValidate>
        <Stack>
          <Select
            label={t("types.field.kind")}
            description={t("types.field.kindHint")}
            data={[...TYPE_BEARING_KINDS]}
            data-autofocus
            {...form.getInputProps("kind")}
            onChange={(v) => form.setFieldValue("kind", v ?? "")}
          />
          <TagsInput
            label={t("types.field.types")}
            description={t("types.field.typesHint")}
            splitChars={[",", " "]}
            {...form.getInputProps("types")}
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
