import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useNavigate } from "react-router-dom";
import { useForm } from "@mantine/form";
import { useQueryClient } from "@tanstack/react-query";
import {
  createCatalogFile,
  softRejectionFindings,
  type CatalogFileRequest,
  type DocumentCheckFinding,
} from "../api/catalogFiles";
import CatalogFileEditor from "../components/CatalogFileEditor";
import SaveAnywayModal from "../components/SaveAnywayModal";
import {
  catalogFileFormValidation,
  emptyCatalogFileForm,
  toCatalogFileRequest,
  type CatalogFileFormValues,
} from "../utils/catalogFileForm";
import { saveErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";
import { catalogFilesPath } from "../utils/catalogFileLinks";

export default function CreateCatalogFile() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  // A strict save rejected for SOFT findings parks the request here; the Save-anyway modal
  // lists the findings and confirming retries with the allowInvalid waiver.
  const [waiver, setWaiver] = useState<{
    request: CatalogFileRequest;
    sourceUrl: string | undefined;
    findings: DocumentCheckFinding[];
  } | null>(null);

  // The shared form vocabulary (utils/catalogFileForm.ts); the field block owns the sections.
  const form = useForm<CatalogFileFormValues>({
    initialValues: emptyCatalogFileForm(),
    validate: catalogFileFormValidation(t),
  });

  async function save(request: CatalogFileRequest, sourceUrl: string | undefined, allowInvalid: boolean) {
    await createCatalogFile({ ...request, sourceUrl }, allowInvalid ? { allowInvalid: true } : undefined);
    await queryClient.invalidateQueries({ queryKey: ["catalogFiles"] });
    showSuccessToast(t("catalog.toast.created"));
    navigate(catalogFilesPath, { replace: true });
  }

  const mapError = (err: unknown) =>
    saveErrorMessage(err, t, {
      invalid: "catalog.validationError",
      conflict: "catalog.conflictError",
      failedStatus: "common.error.createFailedStatus",
      failed: "common.error.createFailedNetwork",
    });

  async function onSubmit(values: CatalogFileFormValues) {
    setError(null);
    setSubmitting(true);
    // The document stays pure (the /check body); the source reference rides beside it.
    const request = toCatalogFileRequest(values);
    const sourceUrl = values.sourceUrl.trim() || undefined;
    try {
      await save(request, sourceUrl, false);
    } catch (err) {
      const findings = await softRejectionFindings(err, request);
      if (findings) setWaiver({ request, sourceUrl, findings });
      else setError(mapError(err));
    } finally {
      setSubmitting(false);
    }
  }

  async function onSaveAnyway() {
    if (!waiver) return;
    setSubmitting(true);
    try {
      await save(waiver.request, waiver.sourceUrl, true);
    } catch (err) {
      setError(mapError(err));
    } finally {
      setWaiver(null);
      setSubmitting(false);
    }
  }

  return (
    <>
      <CatalogFileEditor
        title={t("catalog.createFile")}
        submitLabel={t("common.action.create")}
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
