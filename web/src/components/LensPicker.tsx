import { useState } from "react";
import {
  ActionIcon,
  Alert,
  Badge,
  Button,
  Group,
  Menu,
  Modal,
  Radio,
  Select,
  Stack,
  TextInput,
} from "@mantine/core";
import { useForm } from "@mantine/form";
import { IconDeviceFloppy, IconDots, IconPencil, IconTrash } from "@tabler/icons-react";
import { useQueryClient } from "@tanstack/react-query";
import type { TFunction } from "i18next";
import { useTranslation } from "react-i18next";
import type { CatalogFileFilterValues } from "../api/catalogFiles";
import { createLens, deleteLens, updateLens, type Lens, type LensBody, type LensFilters } from "../api/lenses";
import { getUserId } from "../api/session";
import type { CatalogFileFilterControlsState } from "../hooks/useCatalogFileFilterState";
import { useDeleteConfirm } from "../hooks/useDeleteConfirm";
import { useLenses } from "../hooks/useLenses";
import { fromLensFilters, sameLensFilters, toLensFilters } from "../utils/lensFilters";
import { saveErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";
import ConfirmDeleteModal from "./ConfirmDeleteModal";

const MAX_LENS_NAME_LENGTH = 100;

/** The lens save's fixed error vocabulary (409 = the caller already owns the name). */
function lensSaveErrorMessage(err: unknown, t: TFunction): string {
  return saveErrorMessage(err, t, {
    forbidden: "lenses.saveForbidden",
    conflict: "lenses.saveConflict",
    notFound: "lenses.saveGone",
    failedStatus: "common.error.actionFailedStatus",
    failed: "common.error.actionFailed",
  });
}

/**
 * The lens combo + its actions menu, rendered in every filterable view's FilterPanel header
 * (Hierarchy, Files, Graph, Errors — one shared component, so a lens saved on one view
 * applies on all of them). Picking a lens bulk-applies its payload into the view's stored
 * filter slots; the selection itself is transient UI state (deliberately unpersisted — the
 * lens is a way to SET filters, not a live binding), and the "modified" badge appears when
 * the current filters have diverged from the selected lens.
 */
export default function LensPicker({
  values,
  controls,
}: {
  values: CatalogFileFilterValues;
  controls: CatalogFileFilterControlsState;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { lenses } = useLenses();
  const userId = getUserId();
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [editorTarget, setEditorTarget] = useState<Lens | "new" | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);

  const selected = lenses.find((lens) => String(lens.id) === selectedId) ?? null;
  const owned = selected !== null && selected.createdBy === userId;
  // Compare against the RAW name (the debounced values.name would flash "modified" while
  // a freshly applied lens's name settles).
  const currentValues: CatalogFileFilterValues = { ...values, name: controls.name || undefined };
  const modified = selected !== null && !sameLensFilters(currentValues, selected.filters);

  const mine = lenses.filter((lens) => lens.visibility === "PRIVATE");
  const shared = lenses.filter((lens) => lens.visibility === "PUBLIC");
  const option = (lens: Lens) => ({
    value: String(lens.id),
    // A foreign public lens carries its creator's name — different creators may reuse a name.
    label: lens.createdBy === userId ? lens.name : `${lens.name} — ${lens.creatorName}`,
  });
  const data = [
    { group: t("lenses.group.private"), items: mine.map(option) },
    { group: t("lenses.group.public"), items: shared.map(option) },
  ].filter((g) => g.items.length > 0);

  async function invalidate() {
    await queryClient.invalidateQueries({ queryKey: ["lenses"] });
  }

  function applyLens(id: string | null) {
    setSelectedId(id);
    setSaveError(null);
    const lens = lenses.find((candidate) => String(candidate.id) === id);
    if (lens) controls.applyValues(fromLensFilters(lens.filters));
  }

  async function saveChanges() {
    if (!selected) return;
    setSaveError(null);
    try {
      await updateLens(selected.id, {
        name: selected.name,
        visibility: selected.visibility,
        filters: toLensFilters(currentValues),
      });
      showSuccessToast(t("lenses.toast.saved"));
      await invalidate();
    } catch (err) {
      setSaveError(lensSaveErrorMessage(err, t));
    }
  }

  const remove = useDeleteConfirm<Lens>({
    mutationFn: (lens) => deleteLens(lens.id),
    onSuccess: async () => {
      setSelectedId(null);
      await invalidate();
    },
    successMessage: t("lenses.toast.deleted"),
  });

  return (
    <Stack gap={4}>
      <Group gap="xs" wrap="nowrap">
        <Select
          size="xs"
          w={220}
          placeholder={t("lenses.picker.placeholder")}
          aria-label={t("lenses.picker.label")}
          data={data}
          value={selectedId}
          onChange={applyLens}
          searchable
          clearable
          clearButtonProps={{ "aria-label": t("lenses.picker.clearAria") }}
        />
        {modified && (
          <Badge size="sm" variant="light" color="yellow">
            {t("lenses.modified")}
          </Badge>
        )}
        <Menu position="bottom-start" withinPortal>
          <Menu.Target>
            <ActionIcon variant="default" size="input-xs" aria-label={t("lenses.actionsAria")}>
              <IconDots size={16} />
            </ActionIcon>
          </Menu.Target>
          <Menu.Dropdown>
            <Menu.Item leftSection={<IconDeviceFloppy size={14} />} onClick={() => setEditorTarget("new")}>
              {t("lenses.action.saveAs")}
            </Menu.Item>
            {owned && (
              <>
                <Menu.Item
                  leftSection={<IconDeviceFloppy size={14} />}
                  disabled={!modified}
                  onClick={() => void saveChanges()}
                >
                  {t("lenses.action.save")}
                </Menu.Item>
                <Menu.Item leftSection={<IconPencil size={14} />} onClick={() => setEditorTarget(selected)}>
                  {t("lenses.action.edit")}
                </Menu.Item>
                <Menu.Item
                  color="red"
                  leftSection={<IconTrash size={14} />}
                  onClick={() => remove.requestDelete(selected)}
                >
                  {t("lenses.action.delete")}
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
        <LensEditorModal
          target={editorTarget === "new" ? null : editorTarget}
          currentFilters={toLensFilters(currentValues)}
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
        title={t("lenses.deleteTitle")}
        errorTitle={t("lenses.deleteFailed")}
        body={(lens) => t("lenses.deleteBody", { name: lens.name })}
        errorMessage={(err) => lensSaveErrorMessage(err, t)}
      />
    </Stack>
  );
}

/**
 * Create (target null — stores the CURRENT filters under the chosen name) / edit (target
 * set — renames and/or flips visibility, KEEPING the lens's stored filter payload).
 */
function LensEditorModal({
  target,
  currentFilters,
  onClose,
  onSaved,
}: {
  target: Lens | null;
  currentFilters: LensFilters;
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
        if (!value.trim()) return t("lenses.validation.nameRequired");
        return value.trim().length <= MAX_LENS_NAME_LENGTH ? null : t("lenses.validation.nameTooLong");
      },
    },
  });

  async function save(formValues: { name: string; visibility: "PRIVATE" | "PUBLIC" }) {
    setError(null);
    setSubmitting(true);
    try {
      const body: LensBody = {
        name: formValues.name.trim(),
        visibility: formValues.visibility,
        filters: target ? target.filters : currentFilters,
      };
      let savedId: number;
      if (target) {
        await updateLens(target.id, body);
        savedId = target.id;
        showSuccessToast(t("lenses.toast.saved"));
      } else {
        savedId = (await createLens(body)).id;
        showSuccessToast(t("lenses.toast.created"));
      }
      await onSaved(savedId);
    } catch (err) {
      setError(lensSaveErrorMessage(err, t));
      setSubmitting(false);
    }
  }

  return (
    <Modal opened onClose={onClose} title={target ? t("lenses.editTitle") : t("lenses.createTitle")} centered>
      <form onSubmit={form.onSubmit(save)} noValidate>
        <Stack>
          <TextInput
            label={t("lenses.field.name")}
            maxLength={MAX_LENS_NAME_LENGTH}
            data-autofocus
            {...form.getInputProps("name")}
          />
          <Radio.Group label={t("lenses.field.visibility")} {...form.getInputProps("visibility")}>
            <Stack gap="xs" mt="xs">
              <Radio
                value="PRIVATE"
                label={t("lenses.visibility.private")}
                description={t("lenses.visibility.privateHint")}
              />
              <Radio
                value="PUBLIC"
                label={t("lenses.visibility.public")}
                description={t("lenses.visibility.publicHint")}
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
