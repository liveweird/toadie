import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Navigate, useNavigate, useParams } from "react-router-dom";
import { Paper, Stack, Title } from "@mantine/core";
import { useForm } from "@mantine/form";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  getCatalogFile,
  softRejectionFindings,
  updateCatalogFile,
  type CatalogFileRequest,
  type DocumentCheckFinding,
} from "../api/catalogFiles";
import { ApiError } from "../api/http";
import CatalogFileEditor from "../components/CatalogFileEditor";
import SaveAnywayModal from "../components/SaveAnywayModal";
import EditPageLoadState from "../components/EditPageLoadState";
import {
  catalogFileFormValidation,
  emptyCatalogFileForm,
  fromCatalogFileResponse,
  toCatalogFileRequest,
  type CatalogFileFormValues,
} from "../utils/catalogFileForm";
import { loadErrorMessage, saveErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";

export default function EditCatalogFile() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const params = useParams<{ id: string }>();
  const id = Number(params.id);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  // A strict save rejected for SOFT findings parks the request here; the Save-anyway modal
  // lists the findings and confirming retries with the allowInvalid waiver.
  const [waiver, setWaiver] = useState<{
    request: CatalogFileRequest;
    findings: DocumentCheckFinding[];
  } | null>(null);

  // The shared form vocabulary (utils/catalogFileForm.ts); the field block owns the sections.
  const form = useForm<CatalogFileFormValues>({
    initialValues: emptyCatalogFileForm(),
    validate: catalogFileFormValidation(t),
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

  if (!idIsValid) return <Navigate to="/catalog-files" replace />;

  async function save(request: CatalogFileRequest, allowInvalid: boolean) {
    await updateCatalogFile(id, request, allowInvalid ? { allowInvalid: true } : undefined);
    await queryClient.invalidateQueries({ queryKey: ["catalogFiles"] });
    await queryClient.invalidateQueries({ queryKey: ["catalogFiles", "detail", id] });
    showSuccessToast(t("catalog.toast.updated"));
    navigate("/catalog-files", { replace: true });
  }

  const mapError = (err: unknown) =>
    saveErrorMessage(err, t, {
      notFound: "catalog.fileGone",
      invalid: "catalog.validationError",
      conflict: "catalog.conflictError",
      failedStatus: "common.error.saveFailedStatus",
      failed: "common.error.saveFailedNetwork",
    });

  async function onSubmit(values: CatalogFileFormValues) {
    setError(null);
    setSubmitting(true);
    const request = toCatalogFileRequest(values);
    try {
      await save(request, false);
    } catch (err) {
      const findings = await softRejectionFindings(err, request);
      if (findings) setWaiver({ request, findings });
      else setError(mapError(err));
    } finally {
      setSubmitting(false);
    }
  }

  async function onSaveAnyway() {
    if (!waiver) return;
    setSubmitting(true);
    try {
      await save(waiver.request, true);
    } catch (err) {
      setError(mapError(err));
    } finally {
      setWaiver(null);
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
            message={notFound ? t("catalog.fileNotFound") : loadErrorMessage(fetchError, t)}
            backTo="/catalog-files"
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
        onSubmit={onSubmit}
        error={error}
        submitting={submitting}
      />
      <SaveAnywayModal
        findings={waiver?.findings ?? null}
        onCancel={() => setWaiver(null)}
        onConfirm={onSaveAnyway}
        saving={submitting}
      />
    </>
  );
}
