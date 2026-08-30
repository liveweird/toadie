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
import { IconHash, IconPencil, IconPlus, IconTrash } from "@tabler/icons-react";
import { isAdmin } from "../api/session";
import {
  createTagCategory,
  deleteTagCategory,
  updateTagCategory,
  type TagCategory,
} from "../api/tagCategories";
import ConfirmDeleteModal from "../components/ConfirmDeleteModal";
import EmptyState from "../components/EmptyState";
import KindTierDot, { renderKindOption } from "../components/KindTierDot";
import { useDeleteConfirm } from "../hooks/useDeleteConfirm";
import { useTagCategories } from "../hooks/useTagCategories";
import { ENTITY_KINDS } from "../utils/catalogFileForm";
import {
  emptyTagCategoryForm,
  MAX_CATEGORY_NAME_LENGTH,
  tagCategoryFormValidation,
  tagCategorySaveErrorMessage,
  toTagCategoryBody,
  toTagCategoryFormValues,
  type TagCategoryFormValues,
} from "../utils/tagCategoryForm";
import { loadErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";

/**
 * The tag categories (`/tags`) — an internal Toadie concept, not part of the Backstage
 * schema: everyone gets the read-only list; an ADMIN additionally gets per-category
 * create/edit/delete (the Labels-page modal pattern). A category = a display name + its
 * tags (each tag belongs to exactly ONE category) + the kinds those tags apply to; these
 * tags are the ONLY tags catalog-file writes accept.
 */
export default function Tags() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { categories, loading, error, loadError } = useTagCategories();
  const [editorTarget, setEditorTarget] = useState<TagCategory | "new" | null>(null);

  const remove = useDeleteConfirm<TagCategory>({
    mutationFn: (category) => deleteTagCategory(category.id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["tagCategories"] }),
    successMessage: t("tags.toast.deleted"),
  });

  return (
    <Container size="md" px={0}>
      <Paper withBorder shadow="sm" p="xl" radius="md">
        <Stack>
          <Group justify="space-between" align="flex-start">
            <Title order={2}>{t("tags.title")}</Title>
            {isAdmin() && (
              <Button leftSection={<IconPlus size={16} />} onClick={() => setEditorTarget("new")}>
                {t("tags.newCategory")}
              </Button>
            )}
          </Group>
          <Text size="sm" c="dimmed">
            {t("tags.intro")}
          </Text>
          {error ? (
            <Alert color="red" variant="light" title={t("tags.loadFailed")}>
              {loadErrorMessage(loadError, t)}
            </Alert>
          ) : loading ? (
            <Center py="xl">
              <Loader />
            </Center>
          ) : categories.length === 0 ? (
            <EmptyState
              icon={<IconHash size={32} stroke={1.2} color="var(--mantine-color-dimmed)" />}
              label={t("tags.empty")}
            />
          ) : (
            <Table>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{t("tags.column.name")}</Table.Th>
                  <Table.Th>{t("tags.column.tags")}</Table.Th>
                  <Table.Th>{t("tags.column.kinds")}</Table.Th>
                  {isAdmin() && <Table.Th aria-label={t("common.table.operations")} />}
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {categories.map((category) => (
                  <Table.Tr key={category.id}>
                    <Table.Td>
                      <Text size="sm" fw={500}>
                        {category.name}
                      </Text>
                    </Table.Td>
                    <Table.Td>
                      <Group gap={4}>
                        {category.tags.map((tag) => (
                          <Badge key={tag} variant="light" size="sm">
                            {tag}
                          </Badge>
                        ))}
                      </Group>
                    </Table.Td>
                    <Table.Td>
                      <Group gap={4}>
                        {category.kinds.map((kind) => (
                          <Badge key={kind} color="teal" variant="light" size="sm" leftSection={<KindTierDot kind={kind} />}>
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
                            aria-label={t("common.action.editAria", { name: category.name })}
                            onClick={() => setEditorTarget(category)}
                          >
                            {t("common.action.edit")}
                          </Button>
                          <Button
                            variant="subtle"
                            color="red"
                            size="xs"
                            leftSection={<IconTrash size={14} />}
                            aria-label={t("common.action.deleteAria", { name: category.name })}
                            onClick={() => remove.requestDelete(category)}
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
        <TagCategoryEditorModal
          target={editorTarget === "new" ? null : editorTarget}
          onClose={() => setEditorTarget(null)}
          onSaved={async () => {
            setEditorTarget(null);
            await queryClient.invalidateQueries({ queryKey: ["tagCategories"] });
          }}
        />
      )}

      <ConfirmDeleteModal
        confirm={remove}
        title={t("tags.deleteTitle")}
        errorTitle={t("tags.deleteFailed")}
        body={(category) => t("tags.deleteBody", { name: category.name })}
      />
    </Container>
  );
}

/** Create (target null) / edit (target set) — one modal, the same field block. */
function TagCategoryEditorModal({
  target,
  onClose,
  onSaved,
}: {
  target: TagCategory | null;
  onClose: () => void;
  onSaved: () => Promise<void>;
}) {
  const { t } = useTranslation();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const form = useForm<TagCategoryFormValues>({
    initialValues: target ? toTagCategoryFormValues(target) : emptyTagCategoryForm(),
    validate: tagCategoryFormValidation(t),
  });

  async function save(values: TagCategoryFormValues) {
    setError(null);
    setSubmitting(true);
    try {
      if (target) {
        await updateTagCategory(target.id, toTagCategoryBody(values));
        showSuccessToast(t("tags.toast.saved"));
      } else {
        await createTagCategory(toTagCategoryBody(values));
        showSuccessToast(t("tags.toast.created"));
      }
      await onSaved();
    } catch (err) {
      setError(tagCategorySaveErrorMessage(err, t));
      setSubmitting(false);
    }
  }

  return (
    <Modal
      opened
      onClose={onClose}
      title={target ? t("tags.editTitle") : t("tags.createTitle")}
      centered
    >
      <form onSubmit={form.onSubmit(save)} noValidate>
        <Stack>
          <TextInput
            label={t("tags.field.name")}
            description={t("tags.field.nameHint")}
            maxLength={MAX_CATEGORY_NAME_LENGTH}
            data-autofocus
            {...form.getInputProps("name")}
          />
          <TagsInput
            label={t("tags.field.tags")}
            description={t("tags.field.tagsHint")}
            splitChars={[",", " "]}
            {...form.getInputProps("tags")}
          />
          <MultiSelect
            label={t("tags.field.kinds")}
            description={t("tags.field.kindsHint")}
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
