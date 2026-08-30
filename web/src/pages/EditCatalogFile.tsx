import { useTranslation } from "react-i18next";
import { Navigate, useParams } from "react-router-dom";
import { Paper, Stack, Title } from "@mantine/core";
import { useForm } from "@mantine/form";
import { useQuery } from "@tanstack/react-query";
import { getCatalogFile, updateCatalogFile } from "../api/catalogFiles";
import { ApiError } from "../api/http";
import CatalogFileEditor from "../components/CatalogFileEditor";
import SaveAnywayModal from "../components/SaveAnywayModal";
import EditPageLoadState from "../components/EditPageLoadState";
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
