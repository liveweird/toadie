import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Navigate, useParams } from "react-router-dom";
import { Alert, Button, Group, Paper, Stack, Title } from "@mantine/core";
import { IconFileExport, IconRefresh, IconUpload } from "@tabler/icons-react";
import { useForm } from "@mantine/form";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { getCatalogFile, updateCatalogFile } from "../api/catalogFiles";
import { ApiError } from "../api/http";
import CatalogFileEditor from "../components/CatalogFileEditor";
import OverwriteWithYamlModal from "../components/OverwriteWithYamlModal";
import SyncCatalogFileModal from "../components/SyncCatalogFileModal";
import SyncStateText from "../components/SyncStateText";
import SaveAnywayModal from "../components/SaveAnywayModal";
import EditPageLoadState from "../components/EditPageLoadState";
import { useCatalogDownloads } from "../hooks/useCatalogDownloads";
import { useCatalogFileSave } from "../hooks/useCatalogFileSave";
import {
  catalogFileFormValidation,
  emptyCatalogFileForm,
  fromCatalogFileResponse,
  type CatalogFileFormValues,
} from "../utils/catalogFileForm";
import { CATALOG_SAVE_ERROR_KEYS, loadErrorMessage } from "../utils/saveError";
import { catalogFilesPath } from "../utils/catalogFileLinks";

export default function EditCatalogFile() {
  const { t } = useTranslation();
  const params = useParams<{ id: string }>();
  const id = Number(params.id);

  // The shared form vocabulary (utils/catalogFileForm.ts); the field block owns the sections.
  const form = useForm<CatalogFileFormValues>({
    initialValues: emptyCatalogFileForm(),
    validate: catalogFileFormValidation(t),
  });

  // The strict-save → Save-anyway flow shared with the create page.
  const save = useCatalogFileSave({
    saveRequest: (body, options) => updateCatalogFile(id, body, options),
    toastKey: "catalog.toast.updated",
    errorKeys: CATALOG_SAVE_ERROR_KEYS,
  });

  // The whole-file operations act on the STORED file, so they live beside the form rather
  // than inside it — none of them goes through the form's submit path.
  const queryClient = useQueryClient();
  const downloads = useCatalogDownloads();
  const [overwriteOpen, setOverwriteOpen] = useState(false);
  const [syncOpen, setSyncOpen] = useState(false);

  /**
   * Re-seed the form after Overwrite or Sync replaced the stored document underneath it.
   * Mantine's `initialize` is a ONE-SHOT latch, so the guarded call below never fires again:
   * without this the fields keep the pre-write document AND its dirty baseline, so a later
   * Save would PUT the old document and silently revert the write.
   *
   * Callback-driven on purpose — NOT an effect on `data`. The detail query also refetches in
   * the background (window focus, sibling invalidations), and re-seeding on any data change
   * would discard whatever the user is mid-way through typing. Only this user's own completed
   * write may reset the form. The three calls are the house idiom (see pages/Namespaces.tsx):
   * new baseline, repaint, clear dirty.
   */
  async function reseedFromServer() {
    // Re-read THROUGH the query rather than beside it: on success the cache and the form move
    // together, and on failure the error lands in this page's own query state, so the editor
    // is replaced by its load-error branch — which is what must happen, since a Save over a
    // form we could not refresh would revert the write that just committed.
    try {
      const values = fromCatalogFileResponse(
        await queryClient.fetchQuery({
          queryKey: ["catalogFiles", "detail", id],
          queryFn: () => getCatalogFile(id),
          retry: false,
          staleTime: 0,
        }),
      );
      form.setInitialValues(values);
      form.setValues(values);
      form.resetDirty();
    } catch {
      // Handled by the query's error state above — nothing to add here.
    }
  }

  const idIsValid = Number.isFinite(id) && id > 0;

  const { data, isLoading, isError, error: fetchError } = useQuery({
    queryKey: ["catalogFiles", "detail", id],
    queryFn: () => getCatalogFile(id),
    enabled: idIsValid,
    retry: false,
  });

  // Derived, not effect-set: initialize applies once (the guarded-initialize idiom).
  if (data && !form.initialized) {
    form.initialize(fromCatalogFileResponse(data));
  }

  if (!idIsValid) return <Navigate to={catalogFilesPath} replace />;

  const notFound = isError && fetchError instanceof ApiError && fetchError.status === 404;

  if (isLoading || isError) {
    return (
      <Paper withBorder shadow="sm" p="xl" radius="md" maw={560}>
        <Stack>
          <Title order={2}>{t("catalog.editFile")}</Title>
          <EditPageLoadState
            isLoading={isLoading}
            message={notFound ? t("catalog.fileNotFound") : loadErrorMessage(fetchError, t)}
            backTo={catalogFilesPath}
            backLabel={t("catalog.backToList")}
          />
        </Stack>
      </Paper>
    );
  }

  // `data` is present here — the loading/error branches returned above. A stored file always
  // carries its resolved namespace; the fallback mirrors pickRepoDocument's own.
  const file = data!;
  const name = file.metadata.name;
  const namespace = file.metadata.namespace || "default";
  const actions = (
    <Stack gap="xs">
      <Group gap="sm">
        <Button
          variant="default"
          size="xs"
          leftSection={<IconFileExport size={14} />}
          onClick={() => void downloads.handleDownload({ id })}
          loading={downloads.downloadingId === id}
        >
          {t("catalog.exportFile")}
        </Button>
        <Button
          variant="default"
          size="xs"
          leftSection={<IconUpload size={14} />}
          onClick={() => setOverwriteOpen(true)}
        >
          {t("catalog.overwrite.action")}
        </Button>
        <Button
          variant="default"
          size="xs"
          leftSection={<IconRefresh size={14} />}
          onClick={() => setSyncOpen(true)}
          disabled={file.sourceUrl == null}
        >
          {t("catalog.sync.action")}
        </Button>
        <SyncStateText file={file} />
      </Group>
      {downloads.downloadError != null && (
        <Alert color="red" variant="light" title={t("catalog.downloadFailed")}>
          {loadErrorMessage(downloads.downloadError, t)}
        </Alert>
      )}
    </Stack>
  );

  return (
    // Same two-pane document layout as the create page (see web/CLAUDE.md).
    <>
      <CatalogFileEditor
        title={t("catalog.editFile")}
        submitLabel={t("common.action.save")}
        form={form}
        onSubmit={save.onSubmit}
        error={save.error}
        submitting={save.submitting}
        actions={actions}
      />
      <OverwriteWithYamlModal
        file={overwriteOpen ? { id, kind: file.kind, name, namespace } : null}
        onClose={() => setOverwriteOpen(false)}
        onCompleted={() => void reseedFromServer()}
      />
      <SyncCatalogFileModal
        file={
          syncOpen
            ? {
                id,
                kind: file.kind,
                name,
                namespace,
                sourceUrl: file.sourceUrl,
                updatedAt: file.updatedAt,
                lastSyncedAt: file.lastSyncedAt,
              }
            : null
        }
        onClose={() => setSyncOpen(false)}
        onCompleted={() => void reseedFromServer()}
      />
      <SaveAnywayModal
        findings={save.waiverFindings}
        onCancel={save.cancelWaiver}
        onConfirm={save.onSaveAnyway}
        saving={save.submitting}
      />
    </>
  );
}
