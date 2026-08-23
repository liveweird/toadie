import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Navigate, useNavigate, useParams } from "react-router-dom";
import { Paper, Stack, Title } from "@mantine/core";
import { useForm } from "@mantine/form";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { getCatalogFile, updateCatalogFile } from "../api/catalogFiles";
import { ApiError } from "../api/http";
import CatalogFileEditor from "../components/CatalogFileEditor";
import EditPageLoadState from "../components/EditPageLoadState";
import {
  catalogFileFormValidation,
  emptyCatalogFileForm,
  fromCatalogFileResponse,
  toCatalogFileRequest,
  type CatalogFileFormValues,
} from "../utils/catalogFileForm";
import { saveErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";

export default function EditCatalogFile() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const params = useParams<{ id: string }>();
  const id = Number(params.id);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  // The shared form vocabulary (utils/catalogFileForm.ts); the field block owns the sections.
  const form = useForm<CatalogFileFormValues>({
    initialValues: emptyCatalogFileForm(),
    validate: catalogFileFormValidation(t),
  });

  const idIsValid = Number.isFinite(id) && id > 0;

  const { data, isLoading, isError, error: fetchError } = useQuery({
    queryKey: ["catalogFile", id],
    queryFn: () => getCatalogFile(id),
    enabled: idIsValid,
    retry: false,
  });

  // Derived, not effect-set: initialize applies once (the guarded-initialize idiom).
  if (data && !form.initialized) {
    form.initialize(fromCatalogFileResponse(data));
  }

  if (!idIsValid) return <Navigate to="/catalog-files" replace />;

  async function onSubmit(values: CatalogFileFormValues) {
    setError(null);
    setSubmitting(true);
    try {
      await updateCatalogFile(id, toCatalogFileRequest(values));
      await queryClient.invalidateQueries({ queryKey: ["catalogFiles"] });
      await queryClient.invalidateQueries({ queryKey: ["catalogFile", id] });
      showSuccessToast(t("catalog.toast.updated"));
      navigate("/catalog-files", { replace: true });
    } catch (err) {
      setError(
        saveErrorMessage(err, t, {
          notFound: "catalog.fileGone",
          invalid: "catalog.validationError",
          conflict: "catalog.conflictError",
          failedStatus: "common.error.saveFailedStatus",
          failed: "common.error.saveFailedNetwork",
        }),
      );
    } finally {
      setSubmitting(false);
    }
  }

  const notFound = isError && fetchError instanceof ApiError && fetchError.status === 404;

  if (isLoading || isError) {
    return (
      <Paper withBorder shadow="sm" p="xl" radius="md" maw={560}>
        <Stack>
          <Title order={2}>{t("catalog.editFile")}</Title>
          <EditPageLoadState
            isLoading={isLoading}
            message={
              notFound
                ? t("catalog.fileNotFound")
                : t("catalog.loadFileFailed", {
                    suffix: fetchError instanceof ApiError ? ` (${fetchError.status})` : "",
                  })
            }
            backTo="/catalog-files"
            backLabel={t("catalog.backToList")}
          />
        </Stack>
      </Paper>
    );
  }

  return (
    // Same two-pane document layout as the create page (see web/CLAUDE.md).
    <CatalogFileEditor
      title={t("catalog.editFile")}
      submitLabel={t("common.action.save")}
      form={form}
      onSubmit={onSubmit}
      error={error}
      submitting={submitting}
    />
  );
}
