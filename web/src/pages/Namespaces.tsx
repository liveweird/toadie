import { useState } from "react";
import {
  Alert,
  Button,
  Center,
  Container,
  Group,
  Loader,
  Paper,
  Stack,
  Text,
  TextInput,
  Title,
} from "@mantine/core";
import { useDisclosure } from "@mantine/hooks";
import { useForm } from "@mantine/form";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { IconPlus, IconTags } from "@tabler/icons-react";
import { isAdmin } from "../api/session";
import { getDictionary, updateDictionary, type DictionaryEntry } from "../api/dictionaries";
import { showSuccessToast } from "../utils/toast";
import ConfirmActionModal from "../components/ConfirmActionModal";
import EmptyState from "../components/EmptyState";
import RowControls from "../components/RowControls";
import {
  emptyEntryDraft,
  namespaceFormValidation,
  namespaceSaveErrorMessage,
  toFormValues,
  toUpdateBody,
  type NamespaceFormValues,
} from "../utils/namespaceForm";
import { MAX_ENTITY_PART_LENGTH } from "../utils/catalogFileForm";
import { charCountDescription } from "../utils/charCount";
import { loadErrorMessage } from "../utils/saveError";

/**
 * The namespaces dictionary (`/namespaces`): everyone gets the ordered read-only list; an
 * ADMIN gets the whole-list document editor instead — add/edit/reorder/remove rows locally,
 * one Save replaces the dictionary atomically (a removed entry is soft-deleted server-side).
 * These entries are the ONLY namespaces catalog-file writes accept.
 */
export default function Namespaces() {
  const { t } = useTranslation();

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ["dictionary", "namespaces"],
    queryFn: () => getDictionary("namespaces"),
  });

  return (
    <Container size="sm" px={0}>
      <Paper withBorder shadow="sm" p="xl" radius="md">
        <Stack>
          <Title order={2}>{t("namespaces.title")}</Title>
          <Text size="sm" c="dimmed">
            {t("namespaces.intro")}
          </Text>
          {isError ? (
            <Alert color="red" variant="light" title={t("namespaces.loadFailed")}>
              {loadErrorMessage(error, t)}
            </Alert>
          ) : isLoading || !data ? (
            <Center py="xl">
              <Loader />
            </Center>
          ) : isAdmin() ? (
            <NamespacesEditor initialItems={data} />
          ) : (
            <ReadOnlyEntries items={data} />
          )}
        </Stack>
      </Paper>
    </Container>
  );
}

/** The non-admin view: the admin-curated order as numbered rows. */
function ReadOnlyEntries({ items }: { items: DictionaryEntry[] }) {
  const { t } = useTranslation();
  if (items.length === 0) {
    return (
      <EmptyState
        icon={<IconTags size={32} stroke={1.2} color="var(--mantine-color-dimmed)" />}
        label={t("namespaces.empty")}
      />
    );
  }
  return (
    <Stack gap="xs">
      {items.map((entry, index) => (
        <Paper key={entry.id} withBorder p="sm" radius="md">
          <Group gap="xs" wrap="nowrap" align="baseline">
            <Text size="sm" c="dimmed" w={24} ta="right" style={{ flexShrink: 0 }}>
              {index + 1}.
            </Text>
            <Text size="sm">{entry.value}</Text>
          </Group>
        </Paper>
      ))}
    </Stack>
  );
}

function NamespacesEditor({ initialItems }: { initialItems: DictionaryEntry[] }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  // The save PUT committed but the re-seed GET failed: the editor's rows lack their minted
  // ids, so a resubmit would INSERT DUPLICATES — freeze the editor and ask for a reload.
  const [staleAfterSave, setStaleAfterSave] = useState(false);
  const [cancelOpen, { open: openCancel, close: closeCancel }] = useDisclosure(false);

  const form = useForm<NamespaceFormValues>({
    initialValues: toFormValues(initialItems),
    validate: namespaceFormValidation(t),
  });

  async function save(values: NamespaceFormValues) {
    setError(null);
    setSubmitting(true);
    try {
      await updateDictionary("namespaces", toUpdateBody(values));
    } catch (err) {
      setError(namespaceSaveErrorMessage(err, t));
      setSubmitting(false);
      return;
    }
    try {
      // Re-seed from the server so new rows carry their minted ids (a resubmit must rename,
      // not insert twice) and the saved state becomes the new dirty/reset baseline.
      const fresh = await getDictionary("namespaces");
      queryClient.setQueryData(["dictionary", "namespaces"], fresh);
      const freshValues = toFormValues(fresh);
      form.setInitialValues(freshValues);
      form.setValues(freshValues);
      form.resetDirty();
      showSuccessToast(t("namespaces.toast.saved"));
    } catch {
      // The PUT committed — this is NOT a save failure. Without the re-read the editor can't
      // be trusted for further edits, so it freezes behind the reload prompt below.
      setStaleAfterSave(true);
    } finally {
      setSubmitting(false);
    }
  }

  function discard() {
    form.reset();
    setError(null);
    closeCancel();
  }

  const rows = form.values.entries;

  return (
    <form onSubmit={form.onSubmit(save)} noValidate>
      <Stack>
        {rows.length === 0 && (
          <Text c="dimmed" size="sm">
            {t("namespaces.empty")}
          </Text>
        )}
        {rows.map((row, index) => (
          <Paper key={row.key} withBorder p="sm" radius="md">
            <Group align="flex-start" gap="xs" wrap="nowrap">
              <Text size="sm" c="dimmed" w={24} ta="right" pt={8} style={{ flexShrink: 0 }}>
                {index + 1}.
              </Text>
              <TextInput
                style={{ flex: 1 }}
                aria-label={t("namespaces.entryAria", { position: index + 1 })}
                maxLength={MAX_ENTITY_PART_LENGTH}
                description={charCountDescription(
                  form.values.entries[index]?.value.length ?? 0,
                  MAX_ENTITY_PART_LENGTH,
                )}
                inputWrapperOrder={["label", "input", "description", "error"]}
                {...form.getInputProps(`entries.${index}.value`)}
              />
              <RowControls
                index={index}
                count={rows.length}
                onMoveUp={() => form.reorderListItem("entries", { from: index, to: index - 1 })}
                onMoveDown={() => form.reorderListItem("entries", { from: index, to: index + 1 })}
                onRemove={() => form.removeListItem("entries", index)}
                moveUpLabel={t("namespaces.moveUp", { position: index + 1 })}
                moveDownLabel={t("namespaces.moveDown", { position: index + 1 })}
                removeLabel={t("namespaces.removeEntry", { position: index + 1 })}
              />
            </Group>
          </Paper>
        ))}
        <Group>
          <Button
            variant="light"
            size="xs"
            leftSection={<IconPlus size={14} />}
            onClick={() => form.insertListItem("entries", emptyEntryDraft())}
          >
            {t("namespaces.addEntry")}
          </Button>
        </Group>

        {error && (
          <Alert color="red" variant="light">
            {error}
          </Alert>
        )}
        {staleAfterSave && (
          <Alert color="orange" variant="light">
            <Group gap="sm" justify="space-between">
              <Text size="sm">{t("namespaces.savedButStale")}</Text>
              <Button size="xs" color="orange" variant="light" onClick={() => window.location.reload()}>
                {t("common.errorBoundary.reload")}
              </Button>
            </Group>
          </Alert>
        )}

        <Group justify="flex-end" gap="sm">
          <Button
            type="button"
            variant="default"
            onClick={openCancel}
            disabled={submitting || staleAfterSave || !form.isDirty()}
          >
            {t("common.action.cancel")}
          </Button>
          <Button type="submit" loading={submitting} disabled={staleAfterSave || !form.isDirty()}>
            {t("common.action.save")}
          </Button>
        </Group>
      </Stack>

      <ConfirmActionModal
        opened={cancelOpen}
        onClose={closeCancel}
        title={t("namespaces.discardTitle")}
        message={t("namespaces.discardMessage")}
        cancelLabel={t("namespaces.keepEditing")}
        confirmLabel={t("namespaces.discard")}
        onConfirm={discard}
      />
    </form>
  );
}
