import { useState } from "react";
import { Alert, Badge, Box, Button, Group, Paper, Radio, Stack, Text, TextInput } from "@mantine/core";
import { useDisclosure } from "@mantine/hooks";
import { useForm } from "@mantine/form";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { IconFolders, IconPlus } from "@tabler/icons-react";
import { isAdmin } from "../api/session";
import { getDictionary, updateDictionary, type DictionaryEntry } from "../api/dictionaries";
import { showSuccessToast } from "../utils/toast";
import ConfirmActionModal from "../components/ConfirmActionModal";
import EmptyState from "../components/EmptyState";
import RowControls from "../components/RowControls";
import {
  emptyEntryDraft,
  missingDefault,
  namespaceFormValidation,
  namespaceSaveErrorMessage,
  toFormValues,
  toUpdateBody,
  type NamespaceFormValues,
} from "../utils/namespaceForm";
import { MAX_ENTITY_PART_LENGTH } from "../utils/catalogFileForm";
import { BELOW_INPUT, charCountDescription } from "../utils/charCount";
import { loadErrorMessage } from "../utils/saveError";
import LoadingBlock from "../components/LoadingBlock";
import PageHeader from "../components/PageHeader";
import classes from "../theme.module.css";
import { FORM_MAX_WIDTH } from "../utils/layout";

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
    <Stack gap="md">
      <PageHeader title={t("namespaces.title")} description={t("namespaces.intro")} />
      <Paper withBorder p="lg" radius="md" maw={FORM_MAX_WIDTH}>
        <Stack>
          {isError ? (
            <Alert color="red" variant="light" title={t("namespaces.loadFailed")}>
              {loadErrorMessage(error, t)}
            </Alert>
          ) : isLoading || !data ? (
            <LoadingBlock />
          ) : isAdmin() ? (
            <NamespacesEditor initialItems={data} />
          ) : (
            <ReadOnlyEntries items={data} />
          )}
        </Stack>
      </Paper>
    </Stack>
  );
}

/** The non-admin view: the admin-curated order as numbered rows. */
function ReadOnlyEntries({ items }: { items: DictionaryEntry[] }) {
  const { t } = useTranslation();
  if (items.length === 0) {
    return (
      <EmptyState
        icon={IconFolders}
        label={t("namespaces.empty")}
      />
    );
  }
  return (
    <Box className={classes.listRows}>
      {items.map((entry, index) => (
        <Box key={entry.id} className={classes.listRow}>
          <Group gap="xs" wrap="nowrap" align="baseline">
            <Text size="sm" c="dimmed" w={24} ta="right" style={{ flexShrink: 0 }}>
              {index + 1}.
            </Text>
            <Group gap="xs" wrap="nowrap" align="baseline" justify="space-between" style={{ flex: 1 }}>
              <Text size="sm">{entry.value}</Text>
              {entry.isDefault && (
                <Badge color="gray" variant="light" size="sm" style={{ flexShrink: 0 }}>
                  {t("namespaces.defaultBadge")}
                </Badge>
              )}
            </Group>
          </Group>
        </Box>
      ))}
    </Box>
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
    // The document-level rule the per-field validation can't carry: removing the flagged
    // row leaves no default — the radio UI already makes more-than-one impossible.
    if (missingDefault(values)) {
      setError(t("namespaces.defaultRequired"));
      return;
    }
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

  function markDefault(index: number) {
    form.setValues({
      entries: form.values.entries.map((entry, i) => ({ ...entry, isDefault: i === index })),
    });
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
        <Box className={classes.listRows}>
        {rows.map((row, index) => (
          <Box key={row.key} className={classes.listRow}>
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
                inputWrapperOrder={[...BELOW_INPUT]}
                {...form.getInputProps(`entries.${index}.value`)}
              />
              <Radio
                mt={10}
                checked={row.isDefault}
                onChange={() => markDefault(index)}
                label={t("namespaces.defaultBadge")}
                aria-label={t("namespaces.defaultAria", { position: index + 1 })}
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
          </Box>
        ))}
        </Box>
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

        <Group justify="flex-end" gap="sm" className={classes.stickyActions}>
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
