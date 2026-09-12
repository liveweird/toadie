import { useState } from "react";
import { Alert, Box, Button, Group, Paper, Stack, Text, TextInput } from "@mantine/core";
import { useDisclosure } from "@mantine/hooks";
import { useForm } from "@mantine/form";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { IconPlus, IconSitemap } from "@tabler/icons-react";
import { isAdmin } from "../api/session";
import { getDictionary, updateDictionary, type DictionaryEntry } from "../api/dictionaries";
import { showSuccessToast } from "../utils/toast";
import ConfirmActionModal from "../components/ConfirmActionModal";
import EmptyState from "../components/EmptyState";
import RowControls from "../components/RowControls";
import {
  emptyHierarchyDraft,
  hierarchiesFormValidation,
  hierarchiesSaveErrorMessage,
  toHierarchiesFormValues,
  toHierarchiesUpdateBody,
  type HierarchiesFormValues,
} from "../utils/hierarchiesForm";
import { MAX_ENTITY_PART_LENGTH } from "../utils/catalogFileForm";
import { BELOW_INPUT, charCountDescription } from "../utils/charCount";
import { loadErrorMessage } from "../utils/saveError";
import LoadingBlock from "../components/LoadingBlock";
import PageHeader from "../components/PageHeader";
import classes from "../theme.module.css";
import { FORM_MAX_WIDTH } from "../utils/layout";

/**
 * The hierarchies dictionary (`/hierarchies`) — the Lifecycles page's sibling, minus the
 * default-flag plumbing (hierarchies have none): everyone gets the ordered read-only list;
 * an ADMIN gets the whole-list document editor instead — add/edit/reorder/remove rows
 * locally, one Save replaces the dictionary atomically (a removed entry is soft-deleted
 * server-side). These entries name the parallel entity hierarchies (composition,
 * ownership, cost centre…) a blueprint may point one of its single relations at via
 * `hierarchyRelation`; the FIRST entry is the one the Entity hierarchy and Entity graph
 * pages open on.
 */
export default function Hierarchies() {
  const { t } = useTranslation();

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ["dictionary", "hierarchies"],
    queryFn: () => getDictionary("hierarchies"),
  });

  return (
    <Stack gap="md">
      <PageHeader title={t("hierarchies.title")} description={t("hierarchies.intro")} />
      <Paper withBorder p="lg" radius="md" maw={FORM_MAX_WIDTH}>
        <Stack>
          {isError ? (
            <Alert color="red" variant="light" title={t("hierarchies.loadFailed")}>
              {loadErrorMessage(error, t)}
            </Alert>
          ) : isLoading || !data ? (
            <LoadingBlock />
          ) : isAdmin() ? (
            <HierarchiesEditor initialItems={data} />
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
        icon={IconSitemap}
        label={t("hierarchies.empty")}
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
            <Text size="sm">{entry.value}</Text>
          </Group>
        </Box>
      ))}
    </Box>
  );
}

function HierarchiesEditor({ initialItems }: { initialItems: DictionaryEntry[] }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  // The save PUT committed but the re-seed GET failed: the editor's rows lack their minted
  // ids, so a resubmit would INSERT DUPLICATES — freeze the editor and ask for a reload.
  const [staleAfterSave, setStaleAfterSave] = useState(false);
  const [cancelOpen, { open: openCancel, close: closeCancel }] = useDisclosure(false);

  const form = useForm<HierarchiesFormValues>({
    initialValues: toHierarchiesFormValues(initialItems),
    validate: hierarchiesFormValidation(t),
  });

  async function save(values: HierarchiesFormValues) {
    setError(null);
    setSubmitting(true);
    try {
      await updateDictionary("hierarchies", toHierarchiesUpdateBody(values));
    } catch (err) {
      setError(hierarchiesSaveErrorMessage(err, t));
      setSubmitting(false);
      return;
    }
    try {
      // Re-seed from the server so new rows carry their minted ids (a resubmit must rename,
      // not insert twice) and the saved state becomes the new dirty/reset baseline.
      const fresh = await getDictionary("hierarchies");
      queryClient.setQueryData(["dictionary", "hierarchies"], fresh);
      const freshValues = toHierarchiesFormValues(fresh);
      form.setInitialValues(freshValues);
      form.setValues(freshValues);
      form.resetDirty();
      showSuccessToast(t("hierarchies.toast.saved"));
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
            {t("hierarchies.empty")}
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
                aria-label={t("hierarchies.entryAria", { position: index + 1 })}
                maxLength={MAX_ENTITY_PART_LENGTH}
                description={charCountDescription(
                  form.values.entries[index]?.value.length ?? 0,
                  MAX_ENTITY_PART_LENGTH,
                )}
                inputWrapperOrder={[...BELOW_INPUT]}
                {...form.getInputProps(`entries.${index}.value`)}
              />
              <RowControls
                index={index}
                count={rows.length}
                onMoveUp={() => form.reorderListItem("entries", { from: index, to: index - 1 })}
                onMoveDown={() => form.reorderListItem("entries", { from: index, to: index + 1 })}
                onRemove={() => form.removeListItem("entries", index)}
                moveUpLabel={t("hierarchies.moveUp", { position: index + 1 })}
                moveDownLabel={t("hierarchies.moveDown", { position: index + 1 })}
                removeLabel={t("hierarchies.removeEntry", { position: index + 1 })}
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
            onClick={() => form.insertListItem("entries", emptyHierarchyDraft())}
          >
            {t("hierarchies.addEntry")}
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
              <Text size="sm">{t("hierarchies.savedButStale")}</Text>
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
        title={t("hierarchies.discardTitle")}
        message={t("hierarchies.discardMessage")}
        cancelLabel={t("hierarchies.keepEditing")}
        confirmLabel={t("hierarchies.discard")}
        onConfirm={discard}
      />
    </form>
  );
}
