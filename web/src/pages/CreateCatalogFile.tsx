import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useNavigate } from "react-router-dom";
import { useForm } from "@mantine/form";
import { useQueryClient } from "@tanstack/react-query";
import { createCatalogFile } from "../api/catalogFiles";
import CatalogFileEditor from "../components/CatalogFileEditor";
import {
  catalogFileFormValidation,
  emptyCatalogFileForm,
  toCatalogFileRequest,
  type CatalogFileFormValues,
} from "../utils/catalogFileForm";
import { saveErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";

export default function CreateCatalogFile() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  // The shared form vocabulary (utils/catalogFileForm.ts); the field block owns the sections.
  const form = useForm<CatalogFileFormValues>({
    initialValues: emptyCatalogFileForm(),
    validate: catalogFileFormValidation(t),
  });

  async function onSubmit(values: CatalogFileFormValues) {
    setError(null);
    setSubmitting(true);
    try {
      await createCatalogFile(toCatalogFileRequest(values));
      await queryClient.invalidateQueries({ queryKey: ["catalogFiles"] });
      showSuccessToast(t("catalog.toast.created"));
      navigate("/catalog-files", { replace: true });
    } catch (err) {
      setError(
        saveErrorMessage(err, t, {
          invalid: "catalog.validationError",
          conflict: "catalog.conflictError",
          failedStatus: "common.error.createFailedStatus",
          failed: "common.error.createFailedNetwork",
        }),
      );
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <CatalogFileEditor
      title={t("catalog.createFile")}
      submitLabel={t("common.action.create")}
      form={form}
      onSubmit={onSubmit}
      error={error}
      submitting={submitting}
      showSelfNote
    />
  );
}
