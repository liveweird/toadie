import { useState } from "react";
import { useStoredState } from "../hooks/useStoredState";
import { ActionIcon, Alert, Badge, Button, Group, Menu, Modal, Radio, Select, Stack, TextInput } from "@mantine/core";
import { useForm } from "@mantine/form";
import { IconDeviceFloppy, IconDots, IconPencil, IconTrash } from "@tabler/icons-react";
import { useQueryClient } from "@tanstack/react-query";
import type { TFunction } from "i18next";
import { useTranslation } from "react-i18next";
import {
  createSavedEntityQuery,
  deleteSavedEntityQuery,
  updateSavedEntityQuery,
  type SavedEntityQuery,
  type SavedEntityQueryBody,
} from "../api/entityQueries";
import { getUserId } from "../api/session";
import { useDeleteConfirm } from "../hooks/useDeleteConfirm";
import { useSavedEntityQueries } from "../hooks/useSavedEntityQueries";
import { queryProblemDiagnostics } from "../utils/queryDiagnostics";
import { saveErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";
import ConfirmDeleteModal from "./ConfirmDeleteModal";

const MAX_QUERY_NAME_LENGTH = 100;

/** The saved-query save's fixed error vocabulary (409 = the caller already owns the name). */
function entityQuerySaveErrorMessage(err: unknown, t: TFunction): string {
  return saveErrorMessage(err, t, {
    forbidden: "entityQueries.saveForbidden",
    conflict: "entityQueries.saveConflict",
    notFound: "entityQueries.saveGone",
    failedStatus: "common.error.actionFailedStatus",
    failed: "common.error.actionFailed",
  });
}

/**
 * The saved entity-query combo + its actions menu — the `LensPicker` shape one level down,
 * operating on the entity query bar's DRAFT text instead of the shared catalog filter set.
 * Picking an option runs the saved text immediately (`onPick`, wired to `useEntityQuery`'s
 * `runText`); the picked query is transient UI state (deliberately unpersisted, exactly like
 * the lens picker's selection), and the "Modified" badge appears when the current draft has
 * diverged from the picked query's stored text.
 */
export default function EntityQueryPicker({
  draft,
  onPick,
}: {
  draft: string;
  onPick: (text: string) => void;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { entityQueries } = useSavedEntityQueries();
  const userId = getUserId();
  // Shared with the other canvas, like the bar's draft/applied pair (`useEntityQuery`): the
  // picked query follows the user from the Entity graph to the Entity hierarchy and back.
  const [selectedId, setSelectedId] = useStoredState<string | null>(
    "entityQuery.picked",
    null,
    (v) => v === null || typeof v === "string",
  );
  const [editorTarget, setEditorTarget] = useState<SavedEntityQuery | "new" | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);

  const selected = entityQueries.find((query) => String(query.id) === selectedId) ?? null;
  const owned = selected !== null && selected.createdBy === userId;
  const modified = selected !== null && draft.trim() !== selected.query.trim();

  const mine = entityQueries.filter((query) => query.visibility === "PRIVATE");
  const shared = entityQueries.filter((query) => query.visibility === "PUBLIC");
  const option = (query: SavedEntityQuery) => ({
    value: String(query.id),
    // A foreign public query carries its creator's name — different creators may reuse a name.
    label: query.createdBy === userId ? query.name : `${query.name} — ${query.creatorName}`,
  });
  const data = [
    { group: t("entityQueries.group.private"), items: mine.map(option) },
    { group: t("entityQueries.group.public"), items: shared.map(option) },
  ].filter((g) => g.items.length > 0);

  async function invalidate() {
    await queryClient.invalidateQueries({ queryKey: ["entityQueries"] });
  }

  function applyPick(id: string | null) {
    setSelectedId(id);
    setSaveError(null);
    // Clearing the select just forgets the pick — it does NOT clear the bar's draft/applied text.
    if (id === null) return;
    const query = entityQueries.find((candidate) => String(candidate.id) === id);
    if (query) onPick(query.query);
  }

  async function saveChanges() {
    if (!selected) return;
    setSaveError(null);
    try {
      await updateSavedEntityQuery(selected.id, {
        name: selected.name,
        visibility: selected.visibility,
        query: draft,
      });
      showSuccessToast(t("entityQueries.toast.saved"));
      await invalidate();
    } catch (err) {
      const diagnostics = queryProblemDiagnostics(err);
      setSaveError(diagnostics.length > 0 ? diagnostics[0].message : entityQuerySaveErrorMessage(err, t));
    }
  }

  const remove = useDeleteConfirm<SavedEntityQuery>({
    mutationFn: (query) => deleteSavedEntityQuery(query.id),
    onSuccess: async () => {
      setSelectedId(null);
      await invalidate();
    },
    successMessage: t("entityQueries.toast.deleted"),
  });

  return (
    <Stack gap={4}>
      <Group gap="xs" wrap="nowrap">
        <Select
          size="xs"
          w={220}
          placeholder={t("entityQueries.picker.placeholder")}
          aria-label={t("entityQueries.picker.label")}
          data={data}
          value={selectedId}
          // Re-picking the already-picked query must not DESELECT it (Mantine's default): the pick
          // is shared across both canvases, so it is usually already selected when a page opens.
          allowDeselect={false}
          onChange={applyPick}
          searchable
          clearable
          clearButtonProps={{ "aria-label": t("entityQueries.picker.clearAria") }}
        />
        {/* Neutral gray: "differs from the saved query" is state, not caution. */}
        {modified && (
          <Badge size="sm" variant="light" color="gray">
            {t("entityQueries.modified")}
          </Badge>
        )}
        <Menu position="bottom-start" withinPortal>
          <Menu.Target>
            <ActionIcon variant="default" size="input-xs" aria-label={t("entityQueries.actionsAria")}>
              <IconDots size={16} />
            </ActionIcon>
          </Menu.Target>
          <Menu.Dropdown>
            <Menu.Item leftSection={<IconDeviceFloppy size={14} />} onClick={() => setEditorTarget("new")}>
              {t("entityQueries.action.saveAs")}
            </Menu.Item>
            {owned && (
              <>
                <Menu.Item
                  leftSection={<IconDeviceFloppy size={14} />}
                  disabled={!modified}
                  onClick={() => void saveChanges()}
                >
                  {t("entityQueries.action.save")}
                </Menu.Item>
                <Menu.Item leftSection={<IconPencil size={14} />} onClick={() => setEditorTarget(selected)}>
                  {t("entityQueries.action.edit")}
                </Menu.Item>
                <Menu.Item
                  color="red"
                  leftSection={<IconTrash size={14} />}
                  onClick={() => remove.requestDelete(selected)}
                >
                  {t("entityQueries.action.delete")}
                </Menu.Item>
              </>
            )}
          </Menu.Dropdown>
        </Menu>
      </Group>
      {saveError && (
        <Alert color="red" variant="light" p="xs">
          {saveError}
        </Alert>
      )}

      {editorTarget && (
        <EntityQueryEditorModal
          target={editorTarget === "new" ? null : editorTarget}
          currentQuery={draft}
          onClose={() => setEditorTarget(null)}
          onSaved={async (savedId) => {
            setEditorTarget(null);
            setSelectedId(String(savedId));
            await invalidate();
          }}
        />
      )}

      <ConfirmDeleteModal
        confirm={remove}
        title={t("entityQueries.deleteTitle")}
        errorTitle={t("entityQueries.deleteFailed")}
        body={(query) => t("entityQueries.deleteBody", { name: query.name })}
        errorMessage={(err) => entityQuerySaveErrorMessage(err, t)}
      />
    </Stack>
  );
}

/**
 * Create (target null — stores the CURRENT draft under the chosen name) / edit (target set —
 * renames and/or flips visibility, KEEPING the query's stored text — the `LensEditorModal`
 * rule: a rename deliberately does not absorb a diverged current draft).
 */
function EntityQueryEditorModal({
  target,
  currentQuery,
  onClose,
  onSaved,
}: {
  target: SavedEntityQuery | null;
  currentQuery: string;
  onClose: () => void;
  onSaved: (savedId: number) => Promise<void>;
}) {
  const { t } = useTranslation();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const form = useForm<{ name: string; visibility: "PRIVATE" | "PUBLIC" }>({
    initialValues: { name: target?.name ?? "", visibility: target?.visibility ?? "PRIVATE" },
    validate: {
      name: (value) => {
        if (!value.trim()) return t("entityQueries.validation.nameRequired");
        return value.trim().length <= MAX_QUERY_NAME_LENGTH ? null : t("entityQueries.validation.nameTooLong");
      },
    },
  });

  async function save(formValues: { name: string; visibility: "PRIVATE" | "PUBLIC" }) {
    setError(null);
    setSubmitting(true);
    try {
      const body: SavedEntityQueryBody = {
        name: formValues.name.trim(),
        visibility: formValues.visibility,
        query: target ? target.query : currentQuery,
      };
      let savedId: number;
      if (target) {
        await updateSavedEntityQuery(target.id, body);
        savedId = target.id;
        showSuccessToast(t("entityQueries.toast.saved"));
      } else {
        savedId = (await createSavedEntityQuery(body)).id;
        showSuccessToast(t("entityQueries.toast.created"));
      }
      await onSaved(savedId);
    } catch (err) {
      const diagnostics = queryProblemDiagnostics(err);
      if (diagnostics.length > 0) {
        form.setFieldError("name", diagnostics[0].message);
      } else {
        setError(entityQuerySaveErrorMessage(err, t));
      }
      setSubmitting(false);
    }
  }

  return (
    <Modal
      opened
      onClose={onClose}
      title={target ? t("entityQueries.editTitle") : t("entityQueries.createTitle")}
      centered
    >
      <form onSubmit={form.onSubmit(save)} noValidate>
        <Stack>
          <TextInput
            label={t("entityQueries.field.name")}
            maxLength={MAX_QUERY_NAME_LENGTH}
            data-autofocus
            {...form.getInputProps("name")}
          />
          <Radio.Group label={t("entityQueries.field.visibility")} {...form.getInputProps("visibility")}>
            <Stack gap="xs" mt="xs">
              <Radio
                value="PRIVATE"
                label={t("entityQueries.visibility.private")}
                description={t("entityQueries.visibility.privateHint")}
              />
              <Radio
                value="PUBLIC"
                label={t("entityQueries.visibility.public")}
                description={t("entityQueries.visibility.publicHint")}
              />
            </Stack>
          </Radio.Group>
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
