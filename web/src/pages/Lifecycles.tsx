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
import { IconPlus, IconRecycle } from "@tabler/icons-react";
import { isAdmin } from "../api/session";
import { getDictionary, updateDictionary, type DictionaryEntry } from "../api/dictionaries";
import { showSuccessToast } from "../utils/toast";
import ConfirmActionModal from "../components/ConfirmActionModal";
import EmptyState from "../components/EmptyState";
import RowControls from "../components/RowControls";
import {
  emptyLifecycleDraft,
  lifecycleFormValidation,
  lifecycleSaveErrorMessage,
  toLifecycleFormValues,
  toLifecycleUpdateBody,
  type LifecycleFormValues,
} from "../utils/lifecycleForm";
import { MAX_ENTITY_PART_LENGTH } from "../utils/catalogFileForm";
import { charCountDescription } from "../utils/charCount";
import { loadErrorMessage } from "../utils/saveError";

/**
 * The lifecycles dictionary (`/lifecycles`) — the Namespaces page's sibling, minus the
 * default-flag plumbing (lifecycles have none): everyone gets the ordered read-only list;
 * an ADMIN gets the whole-list document editor instead — add/edit/reorder/remove rows
 * locally, one Save replaces the dictionary atomically (a removed entry is soft-deleted
 * server-side). These entries are the ONLY spec.lifecycle values catalog-file writes
 * accept, globally for every lifecycle-bearing kind.
 */
export default function Lifecycles() {
  const { t } = useTranslation();

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ["dictionary", "lifecycles"],
    queryFn: () => getDictionary("lifecycles"),
  });

  return (
    <Container size="sm" px={0}>
      <Paper withBorder shadow="sm" p="xl" radius="md">
        <Stack>
          <Title order={2}>{t("lifecycles.title")}</Title>
          <Text size="sm" c="dimmed">
            {t("lifecycles.intro")}
          </Text>
          {isError ? (
            <Alert color="red" variant="light" title={t("lifecycles.loadFailed")}>
              {loadErrorMessage(error, t)}
            </Alert>
          ) : isLoading || !data ? (
            <Center py="xl">
              <Loader />
            </Center>
          ) : isAdmin() ? (
            <LifecyclesEditor initialItems={data} />
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
        icon={<IconRecycle size={32} stroke={1.2} color="var(--mantine-color-dimmed)" />}
        label={t("lifecycles.empty")}
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

function LifecyclesEditor({ initialItems }: { initialItems: DictionaryEntry[] }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  // The save PUT committed but the re-seed GET failed: the editor's rows lack their minted
  // ids, so a resubmit would INSERT DUPLICATES — freeze the editor and ask for a reload.
  const [staleAfterSave, setStaleAfterSave] = useState(false);
  const [cancelOpen, { open: openCancel, close: closeCancel }] = useDisclosure(false);

  const form = useForm<LifecycleFormValues>({
    initialValues: toLifecycleFormValues(initialItems),
    validate: lifecycleFormValidation(t),
  });

  async function save(values: LifecycleFormValues) {
    setError(null);
    setSubmitting(true);
    try {
      await updateDictionary("lifecycles", toLifecycleUpdateBody(values));
    } catch (err) {
      setError(lifecycleSaveErrorMessage(err, t));
      setSubmitting(false);
      return;
    }
    try {
      // Re-seed from the server so new rows carry their minted ids (a resubmit must rename,
      // not insert twice) and the saved state becomes the new dirty/reset baseline.
      const fresh = await getDictionary("lifecycles");
      queryClient.setQueryData(["dictionary", "lifecycles"], fresh);
      const freshValues = toLifecycleFormValues(fresh);
      form.setInitialValues(freshValues);
      form.setValues(freshValues);
      form.resetDirty();
      showSuccessToast(t("lifecycles.toast.saved"));
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
            {t("lifecycles.empty")}
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
                aria-label={t("lifecycles.entryAria", { position: index + 1 })}
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
                moveUpLabel={t("lifecycles.moveUp", { position: index + 1 })}
                moveDownLabel={t("lifecycles.moveDown", { position: index + 1 })}
                removeLabel={t("lifecycles.removeEntry", { position: index + 1 })}
              />
            </Group>
          </Paper>
        ))}
        <Group>
          <Button
            variant="light"
            size="xs"
            leftSection={<IconPlus size={14} />}
            onClick={() => form.insertListItem("entries", emptyLifecycleDraft())}
          >
            {t("lifecycles.addEntry")}
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
              <Text size="sm">{t("lifecycles.savedButStale")}</Text>
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
        title={t("lifecycles.discardTitle")}
        message={t("lifecycles.discardMessage")}
        cancelLabel={t("lifecycles.keepEditing")}
        confirmLabel={t("lifecycles.discard")}
        onConfirm={discard}
      />
    </form>
  );
}
